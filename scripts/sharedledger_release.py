#!/usr/bin/env python3
"""Configure signing or build, verify and publish a SharedLedger release."""
import argparse
import base64
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zipfile import ZipFile

from generate_update_manifest import (Apk, MAX_APK, MAX_CODE, PACKAGE, REPO, SHA256, VERSION,
                                      ReleaseError, apk_metadata, asset_url, manifest_url, release_notes,
                                      run, sha256, signature, write_assets)
from github_release import COMMIT, GitHubPublication, source_snapshot

ROOT = Path(__file__).resolve().parent.parent
SUPPORT = Path.home() / "Library/Application Support/SharedLedger"
KEYCHAIN_SERVICE = "dev.sharedledger.release-signing"
GITHUB_API = "https://api.github.com"
ASSET_HOSTS = {"release-assets.githubusercontent.com", "objects.githubusercontent.com",
               "github-releases.githubusercontent.com"}
CODE_PROPERTY = "sharedLedgerBaseVersionCode"


def properties(path):
    fields = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^\s*([A-Za-z][\w.-]*)\s*[=:]\s*(.*)$", line)
            if match:
                fields[match[1]] = re.sub(r"\\([ \\:=])", r"\1", match[2])
    return fields


def update_repository(explicit=None):
    config = properties(ROOT / "updates.properties")
    repo = explicit or os.environ.get("UPDATE_GITHUB_REPOSITORY") or config.get("UPDATE_GITHUB_REPOSITORY")
    if not repo:
        remote = run("git", "-C", ROOT, "remote", "get-url", "origin")
        match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)([^/]+/[^/]+?)(?:\.git)?/?", remote)
        repo = match[1] if match else ""
    if not REPO.fullmatch(repo):
        raise ReleaseError("Set a public GitHub release repository with setup-updates.sh --repo OWNER/REPOSITORY.")
    configured = os.environ.get("UPDATE_MANIFEST_URL") or config.get("UPDATE_MANIFEST_URL")
    if explicit is None and configured and configured != manifest_url(repo):
        raise ReleaseError("The configured manifest URL does not match the GitHub release repository.")
    return repo


def secure_url(url, hosts):
    parsed = urlsplit(url)
    return (parsed.scheme == "https" and parsed.hostname in hosts and parsed.port in (None, 443)
            and not parsed.username and not parsed.password and not parsed.fragment)


class GitHubRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not secure_url(newurl, ASSET_HOSTS | {"github.com"}):
            raise ReleaseError("GitHub redirected to an unexpected asset host.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def public_response(url):
    if not secure_url(url, {"github.com", "api.github.com"}):
        raise ReleaseError("Release requests must use GitHub HTTPS.")
    return build_opener(GitHubRedirect()).open(
        Request(url, headers={"User-Agent": "SharedLedger-release", "Cache-Control": "no-cache"}), timeout=30)


def public_json(url, limit=8 * 1024 * 1024):
    with public_response(url) as response:
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ReleaseError("GitHub metadata exceeded the size limit.")
    return json.loads(body)


def check_public(repo):
    try:
        repo_info = public_json(f"{GITHUB_API}/repos/{repo}")
    except HTTPError as error:
        if error.code == 404:
            raise ReleaseError("The release repository must exist and be public. Use --repo for a separate public repository.") from None
        raise
    if repo_info.get("private") or repo_info.get("full_name", "").lower() != repo.lower():
        raise ReleaseError("The release repository must be publicly accessible.")


@dataclass(frozen=True)
class AndroidTools:
    java: Path
    sdk: Path
    build: Path

    @property
    def env(self):
        return dict(os.environ, JAVA_HOME=str(self.java), ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                    PATH=str(self.java / "bin") + os.pathsep + os.environ.get("PATH", ""))

    @classmethod
    def detect(cls):
        local = properties(ROOT / "local.properties")
        shared_tools = SUPPORT / "tools"
        pocket_tools = SUPPORT.parent / "Pocket FX/tools"
        java_home = subprocess.run(["/usr/libexec/java_home", "-v", "17"], capture_output=True, text=True) if sys.platform == "darwin" else None
        path_javac = shutil.which("javac")
        java_candidates = [os.environ.get("JAVA_HOME"), java_home.stdout.strip() if java_home else None,
                           Path(path_javac).resolve().parent.parent if path_javac else None,
                           shared_tools / "jdk-17", pocket_tools / "jdk-17",
                           "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
                           "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"]
        java = None
        for candidate in filter(None, java_candidates):
            jdk = Path(candidate).expanduser()
            if (jdk / "bin/javac").is_file() and (jdk / "bin/keytool").is_file():
                probe = subprocess.run([str(jdk / "bin/javac"), "-version"], capture_output=True, text=True)
                if probe.returncode == 0 and re.search(r"\bjavac 17(?:\.|\s|$)", probe.stdout + probe.stderr):
                    java = jdk.resolve()
                    break
        if java is None:
            raise ReleaseError("Install JDK 17 or set JAVA_HOME to an existing JDK 17.")
        for candidate in filter(None, [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                                       local.get("sdk.dir"), Path.home() / "Library/Android/sdk",
                                       Path.home() / "Android/Sdk", shared_tools / "android-sdk", pocket_tools / "android-sdk"]):
            sdk = Path(candidate).expanduser().resolve()
            build = sdk / "build-tools/35.0.0"
            if (sdk / "platforms/android-35/android.jar").is_file() and all(
                    os.access(build / tool, os.X_OK) for tool in ("aapt2", "apksigner")):
                return cls(java, sdk, build)
        raise ReleaseError("Install Android SDK Platform 35 and Build Tools 35.0.0 or point ANDROID_HOME to them.")


def config_path():
    explicit = os.environ.get("RELEASE_SIGNING_FILE")
    if explicit:
        return Path(explicit).expanduser().resolve()
    standard = SUPPORT / "signing.properties"
    legacy = Path.home() / ".config/sharedledger-signing/signing.properties"
    return legacy if legacy.is_file() and not standard.exists() else standard


def private_path(path):
    resolved = path.resolve()
    if ROOT in (resolved, *resolved.parents):
        raise ReleaseError("Keep signing configuration and the keystore outside the Git checkout.")
    if resolved.exists() and (resolved.stat().st_mode & 0o077 or resolved.stat().st_uid != os.getuid()):
        raise ReleaseError(f"Restrict {resolved.name} to your account with chmod 600.")
    return resolved


def keychain_password(service):
    proc = subprocess.run(["security", "find-generic-password", "-s", service, "-w"], capture_output=True, text=True)
    if proc.returncode != 0 or not proc.stdout.rstrip("\n"):
        raise ReleaseError("The SharedLedger signing password is unavailable in your login Keychain.")
    return proc.stdout.rstrip("\n")


def signing_environment(tools, path):
    path = private_path(path)
    if not path.is_file():
        raise ReleaseError("Release signing is unconfigured. Run ./scripts/setup-updates.sh first.")
    config = properties(path)
    if not config.get("storeFile") or not config.get("keyAlias"):
        raise ReleaseError("Signing properties must include storeFile and keyAlias.")
    key = private_path(path.parent / config["storeFile"])
    alias = config["keyAlias"]
    if not key.is_file():
        raise ReleaseError("The configured SharedLedger release keystore is missing.")
    if config.get("keychainService"):
        store = private = keychain_password(config["keychainService"])
    else:
        store = config.get("storePassword")
        private = config.get("keyPassword")
    if not store or not private:
        raise ReleaseError("The signing configuration is missing its passwords.")
    env = dict(tools.env, RELEASE_SIGNING_FILE=str(path), SHAREDLEDGER_STORE_PASSWORD=store,
               SHAREDLEDGER_KEY_PASSWORD=private)
    keytool = tools.java / "bin/keytool"
    common = ("-keystore", key, "-alias", alias, "-storepass:env", "SHAREDLEDGER_STORE_PASSWORD")
    details = run(keytool, "-list", "-v", *common, env=env)
    if "cn=android debug" in details.lower():
        raise ReleaseError("Use a dedicated SharedLedger release key. This keystore contains an Android debug key.")
    run(keytool, "-certreq", *common, "-keypass:env", "SHAREDLEDGER_KEY_PASSWORD", env=env)
    certificate = run(keytool, "-exportcert", "-rfc", *common, env=env)
    return env, hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate)).hexdigest()


def generate_key(tools, path):
    path = private_path(path)
    if sys.platform != "darwin":
        raise ReleaseError("Automatic signing setup uses the macOS login Keychain. Configure signing.properties externally on other systems.")
    if path != SUPPORT / "signing.properties":
        raise ReleaseError("Key generation uses the default SharedLedger support directory. Unset RELEASE_SIGNING_FILE to generate there.")
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    key = path.parent / "sharedledger-release.p12"
    if path.exists() or key.exists():
        raise ReleaseError("An existing signing file or keystore must be recovered and reused.")
    existing = subprocess.run(["security", "find-generic-password", "-s", KEYCHAIN_SERVICE],
                              capture_output=True)
    if existing.returncode == 0:
        raise ReleaseError("A SharedLedger signing password already exists in Keychain. Recover its keystore rather than creating a new identity.")
    password = secrets.token_hex(32)
    alias = "sharedledger-release"
    with tempfile.TemporaryDirectory(prefix=".new-signing-", dir=path.parent) as staging:
        candidate = Path(staging) / key.name
        env = dict(tools.env, SHAREDLEDGER_STORE_PASSWORD=password)
        run(tools.java / "bin/keytool", "-genkeypair", "-keystore", candidate, "-storetype", "PKCS12",
            "-alias", alias, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=SharedLedger Release", "-storepass:env", "SHAREDLEDGER_STORE_PASSWORD",
            "-keypass:env", "SHAREDLEDGER_STORE_PASSWORD", env=env)
        # Pass the password over stdin to keep it out of command arguments and terminal history.
        user = str(os.getuid())
        run("security", "-i", input_text=f"add-generic-password -a {user} -s {KEYCHAIN_SERVICE} -w {password}\n")
        if keychain_password(KEYCHAIN_SERVICE) != password:
            raise ReleaseError("Keychain did not save the new signing password.")
        candidate.chmod(0o600)
        os.link(candidate, key)
        with path.open("x", encoding="utf-8") as output:
            output.write(f"storeFile={key.name}\nkeyAlias={alias}\nkeychainService={KEYCHAIN_SERVICE}\n")


def cloud_settings(env):
    cloud = properties(ROOT / "cloud.properties")
    url, key = [env.get(name, cloud.get(name, "")) for name in ("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY")]
    origin = urlsplit(url)
    try:
        claims = key.split(".")[1]
        anon = json.loads(base64.urlsafe_b64decode(claims + "=" * (-len(claims) % 4))).get("role") == "anon"
    except (IndexError, ValueError, AttributeError):
        anon = False
    if (origin.scheme != "https" or not origin.hostname or origin.username or origin.password or origin.query
            or origin.fragment or origin.path not in ("", "/") or "your-project-ref" in url):
        raise ReleaseError("Production release requires SUPABASE_URL with the configured HTTPS project origin.")
    if not (re.fullmatch(r"sb_publishable_[A-Za-z0-9_-]{16,}", key) and "replace_me" not in key or anon):
        raise ReleaseError("Production release requires SUPABASE_PUBLISHABLE_KEY with a public client key.")
    firebase_file = ROOT / "app/google-services.json"
    if not firebase_file.is_file():
        raise ReleaseError("Production release requires app/google-services.json for dev.sharedledger.")
    try:
        firebase = json.loads(firebase_file.read_text(encoding="utf-8"))
        info = firebase["project_info"]
        client, = [obj for obj in firebase["client"] if obj["client_info"]["android_client_info"]["package_name"] == PACKAGE]
        app_id = client["client_info"]["mobilesdk_app_id"]
        project_id = info["project_id"]
        api_key = client["api_key"][0]["current_key"]
        if (not all(isinstance(v, str) and v for v in (app_id, project_id, api_key))
                or not app_id.startswith(f"1:{info['project_number']}:android:") or "private_key" in firebase):
            raise ValueError()
    except (ValueError, TypeError, KeyError, IndexError):
        raise ReleaseError("Use the Firebase Android client JSON for dev.sharedledger.") from None
    return url, key, (app_id, project_id, api_key)


def verify_embedded(apk, tools, env, cloud, manifest, fingerprint):
    url, key, firebase = cloud
    with ZipFile(apk) as contents:
        dex = [contents.read(name) for name in contents.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
    if not all(any(value.encode() in entry for entry in dex) for value in (url, key, manifest, fingerprint)):
        raise ReleaseError("The signed APK lacks the intended Supabase or updater configuration.")
    resources = run(tools.build / "aapt2", "dump", "resources", apk, env=env)
    if not all(value in resources for value in firebase):
        raise ReleaseError("The signed APK lacks the intended Firebase client resources.")


def version_code(base, published, staged, installed, override=None):
    floor = max([base, installed, *published, *staged])
    chosen = floor + 1 if override is None else override
    if not floor < chosen <= MAX_CODE:
        raise ReleaseError(f"versionCode must be greater than {floor} and at most {MAX_CODE}.")
    return chosen


def public_file(url, output):
    with public_response(url) as source, output.open("wb") as destination:
        received = 0
        for block in iter(lambda: source.read(1024 * 1024), b""):
            received += len(block)
            if received > MAX_APK:
                raise ReleaseError("A published APK exceeds the maximum supported size.")
            destination.write(block)


def published_versions(repo, tag, tools, staging, signer, *, resume=False):
    prefix = f"{GITHUB_API}/repos/{repo}"
    if not resume:
        try:
            public_json(f"{prefix}/git/ref/tags/{tag}")
        except HTTPError as error:
            if error.code != 404:
                raise
        else:
            raise ReleaseError(f"GitHub already has tag {tag}. Use a new version.")
    candidates = []
    for page in range(1, 101):
        releases = public_json(f"{prefix}/releases?per_page=100&page={page}")
        for release in releases:
            if release["draft"]:
                continue
            previous = release["tag_name"]
            if previous == tag:
                if resume:
                    continue
                raise ReleaseError(f"GitHub already has a release for {tag}.")
            names = {asset["name"] for asset in release["assets"]}
            apks = {name for name in names if name.lower().endswith(".apk")}
            if apks and "latest.json" in names:
                meta = public_json(asset_url(repo, previous, "latest.json"), 65536)
                code = meta.get("versionCode")
                asset = meta.get("apkAsset") or f"sharedledger-{meta.get('versionName')}.apk"
                url = asset_url(repo, previous, asset)
                digest = meta.get("sha256", "")
                if (type(code) is not int or not 1 <= code <= MAX_CODE or asset not in apks
                        or meta.get("packageName", PACKAGE) != PACKAGE or meta.get("apkUrl", url) != url
                        or not SHA256.fullmatch(digest)):
                    raise ReleaseError(f"Published metadata for {previous} is invalid.")
                candidates.append((code, url, digest))
                apks.remove(asset)
            for name in apks:
                url = asset_url(repo, previous, name)
                path = staging / "previous.apk"
                public_file(url, path)
                candidates.append((apk_metadata(path, tools.build, tools.env).code, url, sha256(path)))
        if len(releases) < 100:
            break
    else:
        raise ReleaseError("GitHub release history exceeded the pagination limit.")
    if candidates:
        highest = max(code for code, _, _ in candidates)
        for code, url, digest in candidates:
            if code == highest:
                path = staging / "previous.apk"
                public_file(url, path)
                if sha256(path) != digest or apk_metadata(path, tools.build, tools.env).code != code or signature(path, tools.build, tools.env) != signer:
                    raise ReleaseError("The newest published APK does not match its manifest or your signing key.")
    return [code for code, _, _ in candidates]


def installed_versions(tools, staging, signer):
    adb = tools.sdk / "platform-tools/adb"
    if not adb.is_file():
        return []
    listing = run(adb, "devices", env=tools.env)
    codes = []
    for line in listing.splitlines():
        fields = line.split()
        if len(fields) != 2 or fields[1] != "device":
            continue
        device = fields[0]
        result = subprocess.run([str(adb), "-s", device, "shell", "pm", "path", PACKAGE],
                                env=tools.env, capture_output=True, text=True)
        paths = [line[8:].strip() for line in result.stdout.splitlines() if line.startswith("package:")]
        if not paths:
            continue
        installed = staging / "installed.apk"
        run(adb, "-s", device, "pull", paths[0], installed, env=tools.env)
        meta = apk_metadata(installed, tools.build, tools.env)
        codes.append(meta.code)
        matches = signature(installed, tools.build, tools.env) == signer
        note = "same signing certificate" if matches else "different signing certificate. See the backup migration guide"
        print(f"Connected Android has SharedLedger {meta.version} ({meta.code}), {note}.")
    return codes


@contextmanager
def release_lock():
    directory = ROOT / "build/sharedledger-releases"
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ReleaseError("Another SharedLedger setup or build is running.") from None
        yield directory


def setup(args):
    repo = update_repository(args.repo)
    check_public(repo)
    tools = AndroidTools.detect()
    path = config_path()
    with release_lock():
        if not path.exists():
            approve = args.generate_key or (sys.stdin.isatty() and input("Create a permanent SharedLedger signing key in the login Keychain? Type yes: ").strip() == "yes")
            if not approve:
                raise ReleaseError("No signing key configured. Use an existing SharedLedger key or rerun with --generate-key to confirm.")
            generate_key(tools, path)
        _, signer = signing_environment(tools, path)
        updates = ROOT / "updates.properties"
        current = updates.read_text(encoding="utf-8").splitlines() if updates.exists() else []
        # Preserve advanced local settings, such as an explicit debug test fingerprint.
        others = [line for line in current if not re.match(r"^\s*(UPDATE_GITHUB_REPOSITORY|UPDATE_MANIFEST_URL)\s*[=:]", line)]
        text = "\n".join([*others, f"UPDATE_GITHUB_REPOSITORY={repo}", f"UPDATE_MANIFEST_URL={manifest_url(repo)}"]) + "\n"
        if not updates.exists() or updates.read_text(encoding="utf-8") != text:
            with tempfile.NamedTemporaryFile(mode="w", dir=ROOT / "build", encoding="utf-8", delete=False) as file:
                file.write(text)
            Path(file.name).replace(updates)
    print(f"Repository {repo}\nManifest {manifest_url(repo)}\nSigning certificate SHA-256 {signer}")
    print(f"Signing configuration {path}")
    print("Back up the signing key and its password, then run ./scripts/release.sh VERSION.")


def release(args):
    if not VERSION.fullmatch(args.version):
        raise ReleaseError("Use a stable version in X.Y.Z format, for example 0.3.0.")
    if args.version_code is not None and not 1 <= args.version_code <= MAX_CODE:
        raise ReleaseError("The requested versionCode is outside Android's supported range.")
    resume = getattr(args, "resume", False)
    local_only = getattr(args, "no_publish", False)
    if resume and (local_only or args.notes is not None or args.version_code is not None):
        raise ReleaseError("--resume uses the prepared APK, versionCode and notes. Omit other release flags.")
    repo = update_repository()
    manifest = manifest_url(repo)
    tools = AndroidTools.detect()
    env, signer = signing_environment(tools, config_path())
    cloud = cloud_settings(env)
    check_public(repo)
    env.update(UPDATE_GITHUB_REPOSITORY=repo, UPDATE_MANIFEST_URL=manifest)
    tag = f"v{args.version}"
    with release_lock() as directory, tempfile.TemporaryDirectory(prefix=".staging-", dir=directory) as temp:
        stage = Path(temp)
        target = directory / tag
        if target.exists() and not resume:
            raise ReleaseError(f"Artifacts for {tag} already exist at {target}. Use --resume for a matching prepared release.")
        if resume and not (target / "release-info.json").is_file():
            raise ReleaseError(f"No artifacts with source provenance are staged for {tag}. Prepare a release with this workflow first.")

        source = source_snapshot(ROOT)
        publication = None if local_only else GitHubPublication(ROOT, repo, SUPPORT.parent / "Pocket FX/tools/gh")
        target_commit = None
        if publication and not resume:
            publication.source_ready(source)
            target_commit = publication.target_commit(source)
            if publication.find_release(tag):
                raise ReleaseError(f"GitHub already has a draft or published release for {tag}.")
            publication.check_tags(source, tag, target_commit, resume=False)
        apk = target / f"sharedledger-{args.version}.apk"
        index = target / "latest.json"
        provenance = target / "release-info.json"
        if resume:
            info = json.loads(provenance.read_text(encoding="utf-8"))
            commit = info.get("sourceCommit")
            if (not isinstance(commit, str) or not COMMIT.fullmatch(commit)
                    or info.get("sourceRepository") != source["sourceRepository"] or info.get("releaseRepository") != repo):
                raise ReleaseError("The staged release does not record a verified source commit for these repositories.")
            source = dict(source, sourceCommit=commit)
            publication.check_source(source, tag, resume=True)
            meta = verify_staged(apk, index, info, args.version, repo, tools, env, signer, cloud)
            target_commit = info.get("releaseCommit") or publication.target_commit(source)
            if not COMMIT.fullmatch(target_commit):
                raise ReleaseError("The recorded release commit is invalid.")
            if not info.get("releaseCommit"):
                info["releaseCommit"] = target_commit
                record_release(provenance, info)
        else:
            previous = published_versions(repo, tag, tools, stage, signer)
            meta = prepare_release(args, repo, manifest, source, target_commit, directory, target, stage,
                                   previous, tools, env, signer, cloud)

        release_url = None
        if publication:
            def check_version():
                previous = published_versions(repo, tag, tools, stage, signer, resume=True)
                if meta["versionCode"] <= max(previous, default=0):
                    raise ReleaseError("Another release has a newer or equal versionCode. Prepare a new version.")

            try:
                if resume:
                    publication.check_tags(source, tag, target_commit, resume=True)
                if not (existing := publication.find_release(tag)) or existing["draft"]:
                    check_version()
                release_url = publication.publish(source, target_commit, tag, apk, index, meta, stage, check_version,
                    lambda: verify_published(repo, tag, apk, meta, stage), resume=resume)
            except (ReleaseError, OSError, URLError, ValueError, KeyError) as error:
                raise ReleaseError(f"{error}\nArtifacts retained at {target}. Retry with ./scripts/release.sh {args.version} --resume.") from None

    print(f"Version {args.version} (versionCode {meta['versionCode']})\nGit tag {tag}")
    print(f"APK {apk}\nAPK SHA-256 {meta['sha256']}")
    print(f"Signing verified with SHA-256 certificate {signer}\nMetadata {index}\nUpdate manifest {manifest}")
    print("Tests passed: release workflow unit tests, Android unit tests and lint" + (" (from the staged build)." if resume else "."))
    if release_url:
        print(f"Publication verified as latest stable.\nGitHub Release {release_url}")
    else:
        print("Publication status: local build only.")


def prepare_release(args, repo, manifest, source, target_commit, directory, target, stage, previous, tools, env, signer, cloud):
    baseline = int(properties(ROOT / "gradle.properties")[CODE_PROPERTY])
    staged = [json.loads(path.read_text(encoding="utf-8"))["versionCode"] for path in directory.glob("v*/latest.json")]
    debug = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    if debug.is_file():
        baseline = max(baseline, apk_metadata(debug, tools.build, env).code)
    connected = installed_versions(tools, stage, signer)
    code = version_code(baseline, previous, staged, max(connected, default=0), args.version_code)
    notes = release_notes(args.notes)
    print(f"Preparing SharedLedger {args.version} (versionCode {code}) for {repo}.", flush=True)
    if subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests"], cwd=ROOT).returncode != 0:
        raise ReleaseError("Release workflow tests failed.")
    command = [ROOT / "gradlew", "--no-daemon", "--max-workers=2", "testDebugUnitTest", "lintDebug", "assembleRelease",
               f"-PsharedLedgerReleaseVersionName={args.version}", f"-PsharedLedgerReleaseVersionCode={code}",
               "-PsharedLedgerDistribution=true"]
    if subprocess.run([str(part) for part in command], cwd=ROOT, env=env).returncode != 0:
        raise ReleaseError("Gradle tests or the signed release build failed.")
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    if not apk.is_file():
        raise ReleaseError("The signed app-release.apk was not generated.")
    if source_snapshot(ROOT) != source:
        raise ReleaseError("The source revision or working tree status changed during the build.")
    verify_embedded(apk, tools, env, cloud, manifest, signer)
    artifacts = stage / "artifacts"
    artifacts.mkdir()
    meta = write_assets(apk, artifacts, repo, signer, tools.build, notes, env, expected=Apk(PACKAGE, code, args.version, 26))
    record_release(artifacts / "release-info.json", dict(source, releaseRepository=repo, releaseCommit=target_commit,
        manifestSha256=sha256(artifacts / "latest.json"), apkSha256=meta["sha256"], versionCode=code))
    artifacts.rename(target)
    return meta


def record_release(path, info):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, encoding="utf-8", delete=False) as output:
        json.dump(info, output, indent=2)
        output.write("\n")
    Path(output.name).replace(path)


def verify_staged(apk, index, info, version, repo, tools, env, signer, cloud):
    meta = json.loads(index.read_text(encoding="utf-8"))
    code = meta["versionCode"]
    if (type(code) is not int or code != info["versionCode"] or sha256(index) != info["manifestSha256"]
            or not apk.is_file() or apk.stat().st_size != meta["apkSize"] or sha256(apk) != info["apkSha256"]
            or info["apkSha256"] != meta["sha256"] or meta["versionName"] != version
            or meta["apkUrl"] != asset_url(repo, f"v{version}", apk.name) or meta["packageName"] != PACKAGE
            or apk_metadata(apk, tools.build, env) != Apk(PACKAGE, code, version, meta["minSdk"])
            or signature(apk, tools.build, env) != signer):
        raise ReleaseError("The staged APK or manifest no longer matches the verified release.")
    verify_embedded(apk, tools, env, cloud, manifest_url(repo), signer)
    return meta


def verify_published(repo, tag, apk, meta, stage):
    stable = manifest_url(repo)
    versioned = asset_url(repo, tag, "latest.json")
    for delay in (0, 1, 2, 4, 8, 16):
        if delay:
            time.sleep(delay)
        try:
            release = public_json(f"{GITHUB_API}/repos/{repo}/releases/latest")
            if (release.get("tag_name") != tag or release.get("draft") is not False or release.get("prerelease") is not False):
                raise ReleaseError(f"GitHub's public latest stable release is not {tag}.")
            if public_json(versioned, 65536) != meta:
                raise ReleaseError("The public versioned update manifest differs from the staged release.")
            if public_json(stable, 65536) != meta:
                raise ReleaseError("The stable update URL is still serving a different manifest.")
            downloaded = stage / "published.apk"
            public_file(meta["apkUrl"], downloaded)
            if downloaded.stat().st_size != apk.stat().st_size or sha256(downloaded) != meta["sha256"]:
                raise ReleaseError("The public APK does not match the verified release.")
            return
        except (ReleaseError, URLError, ValueError):
            if delay == 16:
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    initial = commands.add_parser("setup")
    initial.add_argument("--repo", help="Public release repository in OWNER/REPOSITORY format")
    initial.add_argument("--generate-key", action="store_true", help="Confirm generation of a new permanent key when none exists")
    build = commands.add_parser("release")
    build.add_argument("version")
    build.add_argument("--version-code", type=int)
    build.add_argument("--notes", type=Path)
    mode = build.add_mutually_exclusive_group()
    mode.add_argument("--no-publish", action="store_true", help="Build a signed release locally without GitHub publication")
    mode.add_argument("--resume", action="store_true", help="Retry publication of this version's verified artifacts")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        (setup if args.command == "setup" else release)(args)
    except (ReleaseError, OSError, ValueError, URLError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, f"SharedLedger release: {error}\n")


if __name__ == "__main__":
    main()
