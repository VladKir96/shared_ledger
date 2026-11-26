"""Exercise GitHub publication and recovery with in-memory GitHub and Git fixtures."""

from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import github_release as github
from generate_update_manifest import ReleaseError, asset_url, manifest_url, sha256
from sharedledger_release import verify_published


TAG = "v0.4.1"
SOURCE = {"sourceRepository": "fixture/ledger", "sourceCommit": "a" * 40}


class GitHubFixture(github.GitHubPublication):
    def __init__(self, root, repo=SOURCE["sourceRepository"]):
        self.root = root
        self.repo = repo
        self.gh = "gh"
        self.target = {"default_branch": "main"}
        self.refs = {}
        self.releases = []
        self.uploads = {}
        self.calls = []
        self.fail_once = None

    def api(self, endpoint, *args):
        if "/releases?" in endpoint:
            return [self.releases]
        if "/commits/" in endpoint:
            return {"sha": "b" * 40}
        if endpoint.endswith("/git/refs"):
            self.refs["assets"] = args[-1].split("=")[-1]
            return {"ref": f"refs/tags/{TAG}"}
        if endpoint == f"repos/{SOURCE['sourceRepository']}":
            return {"full_name": SOURCE["sourceRepository"], "permissions": {"push": True}}
        raise AssertionError(endpoint)

    def git(self, *args):
        self.calls.append(args)
        if args[0] == "symbolic-ref":
            return "refs/heads/main"
        if args[0] == "ls-remote":
            if args[2] == "refs/heads/main":
                return f"{SOURCE['sourceCommit']}\trefs/heads/main"
            key = "origin" if args[1] == "origin" else "assets"
            return f"{self.refs[key]}\trefs/tags/{TAG}" if key in self.refs else ""
        if args[:2] == ("tag", "--list"):
            return TAG if "local" in self.refs else ""
        if args[0] == "rev-parse":
            return self.refs["local"]
        if args[0] == "tag":
            self.refs["local"] = args[-1]
            return ""
        if "push" in args:
            self.assert_push(args)
            self.refs["origin"] = self.refs["local"]
            return ""
        raise AssertionError(args)

    @staticmethod
    def assert_push(args):
        assert args[-4:] == ("push", "--no-follow-tags", "origin", f"refs/tags/{TAG}:refs/tags/{TAG}")

    def cli(self, *args):
        self.calls.append(args)
        assert args[0] == "release" and args[args.index("--repo") + 1] == self.repo
        action = args[1]
        if action == "create":
            assert "--draft" in args and "--verify-tag" in args
            self.releases.append(dict(tag_name=TAG, draft=True, prerelease=False,
                                      body=args[-1].read_text(), assets=[]))
        elif action == "upload":
            path = args[3]
            if self.fail_once == path.name:
                self.fail_once = None
                if not any(asset["name"] == path.name for asset in self.releases[0]["assets"]):
                    self.releases[0]["assets"].append(dict(name=path.name, size=0, state="starter"))
                raise ReleaseError("simulated upload failure")
            self.uploads[path.name] = path.read_bytes()
            self.releases[0]["assets"].append(dict(name=path.name, size=path.stat().st_size, state="uploaded"))
        elif action == "delete-asset":
            self.releases[0]["assets"] = [asset for asset in self.releases[0]["assets"] if asset["name"] != args[3]]
        elif action == "download":
            output = args[args.index("--dir") + 1]
            for name, data in self.uploads.items():
                (output / name).write_bytes(data)
        elif action == "edit":
            assert "--draft=false" in args and "--latest" in args
            self.releases[0]["draft"] = False
        else:
            raise AssertionError(args)
        return ""


class PublishTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.apk = self.root / "sharedledger-0.4.1.apk"
        self.apk.write_bytes(b"signed test fixture")
        self.index = self.root / "latest.json"
        self.index.write_text("{}")
        self.meta = dict(sha256=sha256(self.apk), releaseNotes=["Simpler transactions"])
        self.version_check = Mock()
        self.public_check = Mock()
        self.client = GitHubFixture(self.root)
        mocked = patch.object(github, "source_snapshot", return_value=SOURCE)
        mocked.start()
        self.addCleanup(mocked.stop)

    def publish(self):
        with tempfile.TemporaryDirectory(dir=self.root) as stage:
            return self.client.publish(SOURCE, self.client.target_commit(SOURCE), TAG, self.apk, self.index,
                                       self.meta, Path(stage), self.version_check, self.public_check)

    def test_drafts_then_uploads_and_promotes_only_verified_assets(self):
        self.assertEqual(self.publish(), "https://github.com/fixture/ledger/releases/tag/v0.4.1")
        self.assertEqual(self.client.refs, {"local": SOURCE["sourceCommit"], "origin": SOURCE["sourceCommit"]})
        actions = [call[1] for call in self.client.calls if call[0] == "release"]
        self.assertEqual(actions, ["create", "upload", "upload", "download", "edit"])
        self.public_check.assert_called_once()
        self.version_check.assert_called_once()

    def test_upload_failure_leaves_single_draft_and_retry_preserves_same_bytes(self):
        self.client.fail_once = self.index.name
        with self.assertRaisesRegex(ReleaseError, "upload failure"):
            self.publish()
        self.assertTrue(self.client.releases[0]["draft"])
        self.public_check.assert_not_called()
        old_git = self.client.git
        def detached(*args):
            if args[0] == "symbolic-ref":
                raise AssertionError("A pushed source tag should allow detached recovery.")
            return old_git(*args)
        self.client.git = detached
        self.publish()
        self.publish()  # Recovery after a public verification error reads back the published release.
        self.assertEqual(len(self.client.releases), 1)
        self.assertEqual(len([call for call in self.client.calls if call[:2] == ("release", "create")]), 1)
        self.assertEqual(len([call for call in self.client.calls if call[:4] == ("release", "upload", TAG, self.apk)]), 1)
        self.assertEqual(self.client.uploads[self.index.name], self.index.read_bytes())

    def test_different_uploaded_bytes_stop_promotion_without_overwriting(self):
        self.client.fail_once = self.index.name
        with self.assertRaises(ReleaseError):
            self.publish()
        self.client.uploads[self.apk.name] = b"x" * self.apk.stat().st_size
        with self.assertRaisesRegex(ReleaseError, "does not match"):
            self.publish()
        self.assertTrue(self.client.releases[0]["draft"])
        self.assertEqual(self.client.uploads[self.apk.name], b"x" * self.apk.stat().st_size)

    def test_different_existing_tags_and_duplicate_releases_are_rejected(self):
        self.client.refs["origin"] = "b" * 40
        with self.assertRaisesRegex(ReleaseError, "already exists"):
            self.publish()
        self.client.releases = [{"tag_name": TAG}, {"tag_name": TAG}]
        with self.assertRaisesRegex(ReleaseError, "Multiple GitHub"):
            self.client.find_release(TAG)

    def test_separate_publication_repo_uses_its_own_commit(self):
        self.client = GitHubFixture(self.root, "fixture/apks")
        self.assertEqual(self.publish(), "https://github.com/fixture/apks/releases/tag/v0.4.1")
        self.assertEqual(self.client.refs["assets"], "b" * 40)
        self.assertEqual(self.client.refs["origin"], SOURCE["sourceCommit"])

    def test_pushes_exact_tag_using_a_real_local_git_repository(self):
        origin = self.root / "origin.git"
        checkout = self.root / "source"
        github.command("git", "init", "--bare", origin)
        github.command("git", "init", checkout)
        github.command("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                       "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "Release source", cwd=checkout)
        github.command("git", "remote", "add", "origin", origin, cwd=checkout)
        commit = github.command("git", "rev-parse", "HEAD", cwd=checkout)
        source = dict(SOURCE, sourceCommit=commit)
        client = object.__new__(github.GitHubPublication)
        client.root, client.repo, client.gh, client.env = checkout, self.client.repo, "gh", None
        client.push_tags(source, TAG, commit)
        client.push_tags(source, TAG, commit)
        self.assertEqual(github.command("git", "rev-parse", f"refs/tags/{TAG}", cwd=origin), commit)
        self.assertEqual(github.command("git", "for-each-ref", "--format=%(refname)", cwd=origin), f"refs/tags/{TAG}")

    def test_missing_authentication_explains_how_to_sign_in(self):
        with patch.object(github.shutil, "which", return_value="gh"), patch.object(
            github, "command", side_effect=ReleaseError("HTTP 401")
        ):
            with self.assertRaisesRegex(ReleaseError, "gh auth login"):
                github.GitHubPublication(self.root, self.client.repo, self.root / "gh")

    def test_public_verification_reads_stable_and_versioned_manifests_and_matches_apk(self):
        repo = self.client.repo
        self.meta["apkUrl"] = asset_url(repo, TAG, self.apk.name)
        with patch("sharedledger_release.public_json", return_value=self.meta) as get, patch(
            "sharedledger_release.public_file", side_effect=lambda url, output: shutil.copyfile(self.apk, output)
        ):
            verify_published(repo, TAG, self.apk, self.meta, self.root)
        self.assertEqual([call.args[0] for call in get.call_args_list],
                         [manifest_url(repo), asset_url(repo, TAG, self.index.name)])


if __name__ == "__main__":
    unittest.main()
