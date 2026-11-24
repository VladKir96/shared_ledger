#!/usr/bin/env python3
"""Stage an APK and matching GitHub Release metadata without publishing either."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def run(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/release/app-release.apk"))
    parser.add_argument("--output", type=Path, default=Path("app/build/releases"))
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--signer", required=True, help="Expected release certificate SHA-256")
    parser.add_argument("--build-tools", type=Path, required=True, help="Android SDK build-tools version directory")
    args = parser.parse_args()

    try:
        expected = args.signer.lower().replace(":", "")
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError("Supply a valid SHA-256 signing certificate fingerprint.")
        signer_report = run(args.build_tools / "apksigner", "verify", "--print-certs", args.apk)
        signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", signer_report, re.M)
        if signers != [expected]:
            raise ValueError("The APK is not signed by the expected single release certificate.")
        details = run(args.build_tools / "aapt2", "dump", "badging", args.apk)
        package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", details, re.M)
        minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", details, re.M)
        if not package or package[1] != "dev.sharedledger" or not minimum:
            raise ValueError("The APK lacks SharedLedger package or Android version metadata.")
        code = int(package[2])
        if not 1 <= code <= 2_100_000_000 or not re.fullmatch(r"[0-9][A-Za-z0-9._-]{0,63}", package[3]):
            raise ValueError("The APK version must be valid for a release asset filename.")
        notes = [line.lstrip("-* ").strip() for line in args.notes.read_text(encoding="utf-8").splitlines() if line.strip()]
        if not notes or len(notes) > 30 or any(len(line) > 400 for line in notes):
            raise ValueError("Provide 1–30 release-note lines of at most 400 characters.")
        name = f"sharedledger-v{package[3]}.apk"
        directory = args.output / package[3]
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / name
        shutil.copyfile(args.apk, target)
        digest = hashlib.sha256()
        with target.open("rb") as file:
            for chunk in iter(lambda: file.read(1024 * 1024), b""):
                digest.update(chunk)
        metadata = {"versionName": package[3], "versionCode": code, "minSdk": int(minimum[1]),
                    "apkAsset": name, "sha256": digest.hexdigest(), "releaseNotes": notes}
        temporary = directory / "latest.json.tmp"
        temporary.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, directory / "latest.json")
        print(directory.resolve())
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
