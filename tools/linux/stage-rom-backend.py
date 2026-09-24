#!/usr/bin/env python3
"""Stage the DroidUse ROM backend sources for review; never edits an AOSP tree."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCE_GROUPS = (
    (ROOT / "contracts/system-api" / "Android.bp", Path("contract/Android.bp")),
    (ROOT / "contracts/system-api" / "README.md", Path("contract/README.md")),
    (ROOT / "contracts/system-api" / "src/main/aidl", Path("contract/src/main/aidl")),
    (ROOT / "contracts/system-api" / "src/main/java", Path("contract/src/main/java")),
    (
        ROOT / "platform/executor/runtime/src/main/java/dev/droiduse/executor/SystemSession.java",
        Path("runtime/src/dev/droiduse/executor/SystemSession.java"),
    ),
    (ROOT / "platform/rom/service/README.md", Path("service/README.md")),
    (ROOT / "platform/rom/service/src", Path("service/src")),
    (ROOT / "platform/rom/framework/src", Path("framework/src")),
    (ROOT / "platform/rom/framework/core", Path("framework/core")),
    (ROOT / "platform/rom/patches/server/0010-m3-window-and-picker-routing.patch",
        Path("framework/0010-m3-window-and-picker-routing.patch")),
    (ROOT / "platform/rom/patches/server/0011-m3-isolated-editor-clipboard.patch",
        Path("framework/0011-m3-isolated-editor-clipboard.patch")),
)


def copy_source(source: Path, destination: Path) -> None:
    if not source.exists() or source.is_symlink():
        raise ValueError(f"missing or linked source payload: {source.relative_to(ROOT)}")
    if source.is_dir():
        for item in sorted(source.rglob("*")):
            if item.is_symlink():
                raise ValueError(f"linked source payload is not allowed: {item.relative_to(ROOT)}")
            if item.is_file():
                target = destination / item.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(item, target)
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_checksums(staging: Path) -> None:
    files = sorted(
        path for path in staging.rglob("*")
        if path.is_file() and path.name not in {"SHA256SUMS", "COMPLETE"}
    )
    lines = [f"{sha256(path)}  {path.relative_to(staging).as_posix()}" for path in files]
    (staging / "SHA256SUMS").write_text("\n".join(lines) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--certificate-sha256",
        required=True,
        help="Pinned Executor certificate SHA-256 digest; never a private key",
    )
    parser.add_argument(
        "--certificate-purpose", required=True, choices=("engineering", "release"),
        help="Declare whether the public digest is temporary engineering trust or final release trust",
    )
    parser.add_argument(
        "--output", required=True, type=Path,
        help="New staging directory outside the active ROM source tree",
    )
    args = parser.parse_args()

    certificate = args.certificate_sha256.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", certificate):
        parser.error("certificate must be a SHA-256 hex digest")

    output = args.output.resolve()
    if output.exists():
        parser.error("output already exists; choose a new directory")
    if any((parent / ".repo").exists() for parent in (output, *output.parents)):
        parser.error("stage outside the active ROM source tree")

    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(prefix=".rom-backend-", dir=output.parent) as temporary:
            staging = Path(temporary) / "droiduse-backend"
            staging.mkdir()
            for source, relative in SOURCE_GROUPS:
                copy_source(source, staging / relative)

            config = staging / "config"
            config.mkdir()
            (config / "executor-cert.sha256").write_text(certificate + "\n")
            warning = (
                "This is temporary engineering trust. Never ship it in a final candidate."
                if args.certificate_purpose == "engineering"
                else "This is declared release trust; independently verify the final signed APK before integration."
            )
            (config / "README.md").write_text(
                "The certificate digest is public trust metadata. Install it only after the "
                "matching APK has been verified.\n\n"
                f"{warning}\n\n"
                "No active sepolicy-version marker is staged. Create that marker in the ROM "
                "product only after enforcing SELinux policy and denial tests pass.\n"
            )
            (staging / "integration-plan.json").write_text(json.dumps({
                "target": {"device": "oriole", "android": 16, "lineage": "23.2"},
                "serviceName": "droiduse",
                "executorPackage": "dev.droiduse.executor",
                "certificatePurpose": args.certificate_purpose,
                "capabilityCount": 31,
                "sourcePayloads": ["contract", "runtime", "service", "framework", "config"],
                "nextChecks": [
                    "map sources into the locked frameworks/base and product layout",
                    "compile the typed AIDL, runtime state machine, and system service with Soong",
                    "register the SystemServer service and public client lookup",
                    "implement branch-specific framework adapters",
                    "add enforcing SELinux labels and least-privilege rules",
                    "run negative caller, cleanup, reboot, and device probes",
                ],
            }, indent=2) + "\n")
            (staging / "verification.json").write_text(json.dumps({
                "backendReady": False,
                "status": "Source payload staged; AOSP compilation, SELinux policy, and device behavior are not verified",
                "containsPrivateSigningKey": False,
                "certificatePurpose": args.certificate_purpose,
                "activeSePolicyMarkerStaged": False,
            }, indent=2) + "\n")
            write_checksums(staging)

            output.mkdir()
            try:
                for child in staging.iterdir():
                    shutil.move(str(child), output / child.name)
                (output / "COMPLETE").write_text(
                    "ROM backend source staging completed; this is not a ROM build result.\n"
                )
            except Exception:
                shutil.rmtree(output)
                raise
        print(f"Staged DroidUse ROM backend sources: {output}")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Cannot stage: {error}\n")


if __name__ == "__main__":
    main()
