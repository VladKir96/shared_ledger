"""Publish the existing SharedLedger release artifacts through an authenticated GitHub CLI."""

import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
from urllib.parse import quote

from generate_update_manifest import REPO, ReleaseError, sha256

COMMIT = re.compile(r"[0-9a-f]{40}")
ORIGIN = re.compile(r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)([^/]+/[^/]+?)(?:\.git)?/?")


def command(*args, cwd=None, env=None):
    proc = subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        details = (proc.stderr or proc.stdout).strip()
        for key in ("GH_TOKEN", "GITHUB_TOKEN"):
            secret = (env or os.environ).get(key)
            if secret:
                details = details.replace(secret, "[redacted]")
        raise ReleaseError(f"{Path(args[0]).name} {args[1]} failed (exit {proc.returncode}). {details}")
    return proc.stdout.strip()


def source_snapshot(root):
    if shutil.which("git") is None:
        raise ReleaseError("Install Git before preparing a release.")
    remotes = [command("git", "remote", "get-url", "--all", *option, "origin", cwd=root) for option in ((), ("--push",))]
    matches = [ORIGIN.fullmatch(url) for url in remotes]
    if any(match is None for match in matches) or matches[0][1].lower() != matches[1][1].lower() or not REPO.fullmatch(matches[0][1]):
        raise ReleaseError("Set origin fetch and push to the same GitHub source repository.")
    head = command("git", "rev-parse", "HEAD", cwd=root)
    clean = not command("git", "status", "--porcelain=v1", "--untracked-files=normal", cwd=root)
    return dict(sourceRepository=matches[0][1], sourceCommit=head if clean else None)


class GitHubPublication:
    def __init__(self, root, repo, fallback_cli):
        self.root = root
        self.repo = repo
        self.gh = shutil.which("gh") or (fallback_cli if os.access(fallback_cli, os.X_OK) else None)
        if self.gh is None:
            raise ReleaseError("Install GitHub CLI with brew install gh, then run gh auth login --hostname github.com.")
        self.env = dict(os.environ, GH_HOST="github.com", GH_PROMPT_DISABLED="1", GIT_TERMINAL_PROMPT="0")
        self.env.pop("GH_DEBUG", None)
        try:
            self.api("user")
        except ReleaseError as error:
            raise ReleaseError(f"{error}\nRun gh auth login --hostname github.com with an account that can publish releases.") from None
        self.target = self.repository(repo)
        if self.target.get("private"):
            raise ReleaseError("The configured update repository must be public.")

    def cli(self, *args):
        return command(self.gh, *args, cwd=self.root, env=self.env)

    def api(self, endpoint, *args):
        return json.loads(self.cli("api", "--hostname", "github.com", endpoint, *args))

    def repository(self, repo):
        info = self.api(f"repos/{repo}")
        if info.get("full_name", "").lower() != repo.lower() or not info.get("permissions", {}).get("push"):
            raise ReleaseError(f"GitHub write access is required for {repo}.")
        return info

    def git(self, *args):
        return command("git", *args, cwd=self.root, env=self.env)

    def source_ready(self, source, tag=None):
        commit = source["sourceCommit"]
        if not commit or not COMMIT.fullmatch(commit):
            raise ReleaseError("Publish from a clean working tree. Commit the intended source and move unrelated untracked files outside the checkout.")
        self.repository(source["sourceRepository"])
        if tag and self.remote_tag("origin", tag) == commit:
            return
        branch = self.git("symbolic-ref", "HEAD")
        remote = self.git("ls-remote", "origin", branch).split()
        if remote != [commit, branch]:
            raise ReleaseError("Push the intended source commit to origin before publishing.")

    def target_commit(self, source):
        if source["sourceRepository"].lower() == self.repo.lower():
            return source["sourceCommit"]
        # An assets-only repository receives a release tag without acquiring the source tree.
        branch = quote(self.target["default_branch"], safe="")
        return self.api(f"repos/{self.repo}/commits/{branch}")["sha"]

    def find_release(self, tag):
        pages = self.api(f"repos/{self.repo}/releases?per_page=100", "--paginate", "--slurp")
        matches = [row for page in pages for row in page if row["tag_name"] == tag]
        if len(matches) > 1:
            raise ReleaseError(f"Multiple GitHub drafts or releases already use {tag}.")
        return matches[0] if matches else None

    def remote_tag(self, remote, tag):
        ref = f"refs/tags/{tag}"
        lines = self.git("ls-remote", remote, ref, f"{ref}^{{}}").splitlines()
        refs = dict(reversed(line.split()) for line in lines)
        return refs.get(f"{ref}^{{}}", refs.get(ref))

    def check_tags(self, source, tag, target_commit, resume, require_remote=False):
        local_refs = self.git("tag", "--list", tag)
        local = self.git("rev-parse", f"refs/tags/{tag}^{{commit}}") if local_refs else None
        origin = self.remote_tag("origin", tag)
        target = origin if source["sourceRepository"].lower() == self.repo.lower() else self.remote_tag(
            f"https://github.com/{self.repo}.git", tag
        )
        for actual, expected in ((local, source["sourceCommit"]), (origin, source["sourceCommit"]), (target, target_commit)):
            if actual and (not resume or actual != expected):
                raise ReleaseError(f"Tag {tag} already exists. Only resume a prepared release with matching tags.")
        if require_remote and (not origin or not target):
            raise ReleaseError(f"GitHub has not confirmed all required refs for {tag}.")

    def push_tags(self, source, tag, target_commit):
        self.check_tags(source, tag, target_commit, resume=True)
        ref = f"refs/tags/{tag}"
        if not self.git("tag", "--list", tag):
            self.git("tag", tag, source["sourceCommit"])
        if not self.remote_tag("origin", tag):
            helper = f"!{shlex.quote(str(self.gh))} auth git-credential"
            self.git("-c", "credential.helper=", "-c", f"credential.https://github.com.helper={helper}",
                     "push", "--no-follow-tags", "origin", f"{ref}:{ref}")
        if source["sourceRepository"].lower() != self.repo.lower():
            remote = f"https://github.com/{self.repo}.git"
            if not self.remote_tag(remote, tag):
                self.api(f"repos/{self.repo}/git/refs", "--method", "POST", "--field", f"ref={ref}",
                         "--field", f"sha={target_commit}")
        self.check_tags(source, tag, target_commit, resume=True, require_remote=True)

    def publish(self, source, target_commit, tag, apk, index, meta, staging, check_version, verify_public):
        marker = f"Source commit: {source['sourceCommit']}\nAPK SHA-256: {meta['sha256']}"
        prior = self.find_release(tag)
        if prior and marker not in (prior.get("body") or ""):
            raise ReleaseError(f"The existing GitHub release for {tag} does not match these staged artifacts.")
        if prior is None or prior["draft"]:
            self.source_ready(source, tag)
            self.push_tags(source, tag, target_commit)
        if prior is None:
            notes = staging / "release-notes.md"
            entries = meta["releaseNotes"] or [f"SharedLedger {tag} for Android."]
            notes.write_text("\n".join(f"- {entry}" for entry in entries) + "\n\n" + marker + "\n", encoding="utf-8")
            self.cli("release", "create", tag, "--repo", self.repo, "--verify-tag", "--draft", "--title",
                     f"SharedLedger {tag}", "--notes-file", notes)
            prior = self.find_release(tag)
            if prior is None or not prior["draft"] or marker not in (prior.get("body") or ""):
                raise ReleaseError("Unable to verify the newly created GitHub draft.")
        if prior["prerelease"]:
            raise ReleaseError(f"GitHub release {tag} is marked as a prerelease.")
        if prior["draft"]:
            self.upload_and_verify(tag, prior, (apk, index), staging)
            check_version()
            if source_snapshot(self.root) != source:
                raise ReleaseError("The source changed before publishing. Restore the staged source and resume.")
            self.cli("release", "edit", tag, "--repo", self.repo, "--draft=false", "--latest")
        released = self.find_release(tag)
        if released is None or released["draft"] or released["prerelease"]:
            raise ReleaseError(f"GitHub did not publish {tag} as a stable release.")
        self.check_tags(source, tag, target_commit, resume=True, require_remote=True)
        verify_public()
        return f"https://github.com/{self.repo}/releases/tag/{tag}"

    def upload_and_verify(self, tag, draft, assets, staging):
        existing = {asset["name"]: asset for asset in draft["assets"]}
        if existing.keys() - {path.name for path in assets}:
            raise ReleaseError("The existing draft contains unexpected release assets.")
        for path in assets:
            asset = existing.get(path.name)
            if asset and asset.get("state") == "starter" and asset["size"] == 0:
                self.cli("release", "delete-asset", tag, path.name, "--repo", self.repo, "--yes")
                asset = None
            if asset is None:
                self.cli("release", "upload", tag, path, "--repo", self.repo)
            elif asset["size"] != path.stat().st_size:
                raise ReleaseError(f"The existing draft asset {path.name} has a different size.")
        output = staging / "draft"
        output.mkdir()
        self.cli("release", "download", tag, "--repo", self.repo, "--dir", output,
                 "--pattern", assets[0].name, "--pattern", assets[1].name)
        for path in assets:
            copy = output / path.name
            if not copy.is_file() or sha256(copy) != sha256(path):
                raise ReleaseError(f"The uploaded draft asset {path.name} does not match the staged file.")
