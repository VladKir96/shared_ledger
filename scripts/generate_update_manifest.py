#!/usr/bin/env python3
"""Build SharedLedger update metadata from a verified Android APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from typing import NamedTuple

PACKAGE = "dev.sharedledger"
MAX_CODE = 2_100_000_000
MAX_APK = 250 * 1024 * 1024
VERSION = re.compile(r"(0|[1-9][0-9]{0,18})\.(0|[1-9][0-9]{0,18})\.(0|[1-9][0-9]{0,18})")
REPO = re.compile(r"[A-Za-z0-9-]+/[A-Za-z0-9_][A-Za-z0-9_.-]*")
SHA256 = re.compile(r"[0-9a-f]{64}")


class ReleaseError(Exception):
    pass


class Apk(NamedTuple):
    package: str
    code: int
    version: str
    min_sdk: int


def run(*command, env=None, input_text=None):
    result = subprocess.run([str(item) for item in command], env=env, input=input_text,
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise ReleaseError(f"{Path(command[0]).name} failed (exit {result.returncode}).")
    return (result.stdout or "").strip()


def apk_metadata(apk, build_tools, env=None):
    badging = run(build_tools / "aapt2", "dump", "badging", apk, env=env)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or not minimum or package[1] != PACKAGE or not 1 <= int(package[2]) <= MAX_CODE:
        raise ReleaseError("APK package metadata is missing or does not match dev.sharedledger.")
    return Apk(package[1], int(package[2]), package[3], int(minimum[1]))


def signature(apk, build_tools, env=None):
    report = run(build_tools / "apksigner", "verify", "--print-certs", apk, env=env)
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", report, re.M)
    if len(signers) != 1:
        raise ReleaseError("SharedLedger APKs require a single signing certificate.")
    return signers[0]


def sha256(apk):
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_url(repo):
    return f"https://github.com/{repo}/releases/latest/download/latest.json"


def asset_url(repo, tag, name):
    from urllib.parse import quote
    return f"https://github.com/{repo}/releases/download/{quote(tag, safe='')}/{quote(name, safe='')}"


def release_notes(path):
    if path is None:
        return []
    notes = [line.lstrip("-* ").strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(notes) > 30 or any(not line or len(line) > 400 for line in notes):
        raise ReleaseError("Use at most 30 release-note lines of 1–400 characters.")
    return notes


def write_assets(apk, output, repo, fingerprint, build_tools, notes, env=None, expected=None):
    if not REPO.fullmatch(repo):
        raise ReleaseError("Use a GitHub repository in OWNER/REPOSITORY format.")
    actual = apk_metadata(apk, build_tools, env)
    if actual.min_sdk < 1 or not VERSION.fullmatch(actual.version) or expected is not None and actual != expected:
        raise ReleaseError("APK version or Android compatibility differs from the expected build.")
    if signature(apk, build_tools, env) != fingerprint:
        raise ReleaseError("The APK is signed with a different certificate.")
    size = apk.stat().st_size
    if not 1 <= size <= MAX_APK:
        raise ReleaseError("The APK exceeds the updater's supported size.")
    name = f"sharedledger-{actual.version}.apk"
    metadata = dict(versionName=actual.version, versionCode=actual.code, packageName=actual.package,
                    minSdk=actual.min_sdk, apkUrl=asset_url(repo, f"v{actual.version}", name), apkSize=size,
                    sha256=sha256(apk), releaseNotes=notes)
    manifest = output / "latest.json"
    target = output / name
    if target.exists() or manifest.exists():
        raise ReleaseError("APK or latest.json already exists. Keep the version's original assets.")
    with target.open("xb") as destination, apk.open("rb") as source:
        shutil.copyfileobj(source, destination)
    if sha256(target) != metadata["sha256"]:
        raise ReleaseError("The staged APK checksum does not match.")
    with manifest.open("x", encoding="utf-8") as index:
        json.dump(metadata, index, indent=2, ensure_ascii=False)
        index.write("\n")
    if json.loads(manifest.read_text(encoding="utf-8")) != metadata:
        raise ReleaseError("Generated metadata could not be validated.")
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--signer", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    try:
        signer = args.signer.lower().replace(":", "")
        if not SHA256.fullmatch(signer):
            raise ReleaseError("Use a SHA-256 signing certificate fingerprint.")
        args.output.mkdir(parents=True, exist_ok=True)
        write_assets(args.apk, args.output, args.repository, signer, args.build_tools, release_notes(args.notes))
    except (ReleaseError, OSError, ValueError) as error:
        parser.exit(1, f"SharedLedger manifest: {error}\n")


if __name__ == "__main__":
    main()
