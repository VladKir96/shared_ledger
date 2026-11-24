"""Offline tests for SharedLedger's release workflow."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generate_update_manifest as manifests
import sharedledger_release as release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        root_patch = patch.object(release, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

    def test_invalid_version_rejected_before_build(self):
        for value in ("v0.3.0", "0.03.0", "0.3", "0.3.0-rc1", "../0.3.0"):
            with self.subTest(value=value), self.assertRaisesRegex(manifests.ReleaseError, "X.Y.Z"):
                release.release(argparse.Namespace(version=value, version_code=None, notes=None))

    def test_version_code_must_exceed_every_source(self):
        args = (2, [23, 12], [28], 15)
        self.assertEqual(release.version_code(*args), 29)
        self.assertEqual(release.version_code(*args, override=33), 33)
        for code in (0, 23, 28, manifests.MAX_CODE + 1):
            with self.assertRaises(manifests.ReleaseError):
                release.version_code(*args, override=code)

    def test_missing_signing_configuration(self):
        config = self.root.parent / (self.root.name + "-absent.properties")
        with self.assertRaisesRegex(manifests.ReleaseError, "signing is unconfigured"):
            release.signing_environment(None, config)

    def test_missing_supabase_or_firebase_blocks_release(self):
        with self.assertRaisesRegex(manifests.ReleaseError, "SUPABASE_URL"):
            release.cloud_settings({})
        cloud = {"SUPABASE_URL": "https://fixture.example.test", "SUPABASE_PUBLISHABLE_KEY": "sb_secret_dummy"}
        with self.assertRaisesRegex(manifests.ReleaseError, "SUPABASE_PUBLISHABLE_KEY"):
            release.cloud_settings(cloud)
        cloud["SUPABASE_PUBLISHABLE_KEY"] = "sb_publishable_" + "a" * 36
        with self.assertRaisesRegex(manifests.ReleaseError, "google-services.json"):
            release.cloud_settings(cloud)

    def test_existing_key_and_setup_are_idempotent(self):
        signing_config = self.root / "test-signing.properties"
        signing_config.write_text("existing key reference", encoding="utf-8")
        with patch.object(release, "config_path", return_value=signing_config), \
                patch.object(release, "check_public"), patch.object(release.AndroidTools, "detect"), \
                patch.object(release, "signing_environment", return_value=({}, "a" * 64)), \
                patch.object(release, "generate_key") as generate:
            for _ in range(2):
                with patch("sys.stdout"):
                    release.setup(argparse.Namespace(repo="fixture/ledger", generate_key=True))
        generate.assert_not_called()
        self.assertEqual(signing_config.read_text(), "existing key reference")
        fields = release.properties(self.root / "updates.properties")
        self.assertEqual(fields["UPDATE_MANIFEST_URL"], manifests.manifest_url("fixture/ledger"))

    def test_generates_matching_manifest_without_overwriting_assets(self):
        apk = self.root / "source.apk"
        apk.write_bytes(b"fixture APK bytes")
        output = self.root / "dist"
        output.mkdir()
        signer = "b" * 64
        identity = manifests.Apk(manifests.PACKAGE, 3, "0.3.0", 26)
        with patch.object(manifests, "apk_metadata", return_value=identity), \
                patch.object(manifests, "signature", return_value=signer):
            args = (apk, output, "fixture/ledger", signer, self.root, ["Fixes"])
            metadata = manifests.write_assets(*args, expected=identity)
            with self.assertRaisesRegex(manifests.ReleaseError, "already exists"):
                manifests.write_assets(*args, expected=identity)
            with self.assertRaisesRegex(manifests.ReleaseError, "different certificate"):
                manifests.write_assets(apk, output, "fixture/ledger", "c" * 64, self.root, [])
        staged = output / "sharedledger-0.3.0.apk"
        self.assertEqual(staged.read_bytes(), apk.read_bytes())
        self.assertEqual(metadata["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(metadata["apkSize"], apk.stat().st_size)
        self.assertEqual(metadata["apkUrl"], "https://github.com/fixture/ledger/releases/download/v0.3.0/sharedledger-0.3.0.apk")
        self.assertEqual(json.loads((output / "latest.json").read_text()), metadata)

    def test_release_refuses_an_existing_local_version(self):
        target = self.root / "build/sharedledger-releases/v0.3.0"
        target.mkdir(parents=True)
        (target / "latest.json").write_text("retain me")
        with patch.object(release, "update_repository", return_value="fixture/ledger"), \
                patch.object(release.AndroidTools, "detect"), \
                patch.object(release, "signing_environment", return_value=({}, "a" * 64)), \
                patch.object(release, "cloud_settings"), patch.object(release, "check_public"), \
                patch.object(release, "published_versions") as inventory:
            with self.assertRaisesRegex(manifests.ReleaseError, "already exist"):
                release.release(argparse.Namespace(version="0.3.0", version_code=None, notes=None))
        inventory.assert_not_called()
        self.assertEqual((target / "latest.json").read_text(), "retain me")


if __name__ == "__main__":
    unittest.main()
