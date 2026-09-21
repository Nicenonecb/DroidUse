#!/usr/bin/env python3
"""Validate signed APKs and stage opt-in ROM imports; never edits a source tree."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = Path(__file__).resolve().parents[2]


def run(*command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        # Tool output may contain manifest metadata; keep diagnostics local.
        raise ValueError(f"{Path(command[0]).name} {command[1]} failed (exit {result.returncode})")
    return result.stdout


def inspect(apk, package, certificate, analyzer, signer):
    certs = run(signer, "verify", "--print-certs", str(apk))
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", certs, re.M)
    if len(digests) != 1 or digests[0].lower() != certificate:
        raise ValueError(f"{package}: signer does not match the pinned release certificate")
    manifest = ET.fromstring(run(analyzer, "manifest", "print", str(apk)))
    app = manifest.find("application")
    if manifest.get("package") != package or app is None:
        raise ValueError(f"{package}: wrong package or missing application")
    if app.get(ANDROID + "debuggable", "false") != "false" or app.get(ANDROID + "testOnly", "false") != "false":
        raise ValueError(f"{package}: debug/test APK is not eligible")
    if manifest.get(ANDROID + "sharedUserId"):
        raise ValueError(f"{package}: shared UID is not allowed")
    permission = "dev.droiduse.permission.EXECUTE"
    if package == "dev.droiduse.executor":
        definitions = [item for item in manifest.findall("permission") if item.get(ANDROID + "name") == permission]
        services = [item for item in app.findall("service") if item.get(ANDROID + "name") == "dev.droiduse.executor.app.ExecutorService"]
        if (len(definitions) != 1 or definitions[0].get(ANDROID + "protectionLevel") not in {"signature", "0x2", "0x00000002"}
                or len(services) != 1 or services[0].get(ANDROID + "permission") != permission
                or services[0].get(ANDROID + "exported") != "true"):
            raise ValueError("executor: signature-protected Binder service declaration does not match")
    elif not any(item.get(ANDROID + "name") == permission for item in manifest.findall("uses-permission")):
        raise ValueError("assistant: missing executor permission request")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError(f"{package}: duplicate ZIP entries")
        forbidden = {"builtin-model.json", "bridge-token", "phone-executor.jar"}
        for name in names:
            path = Path(name)
            if (path.name in forbidden or "secrets" in path.parts
                    or path.suffix.lower() in {".pem", ".pk8", ".jks", ".keystore", ".p12"}):
                raise ValueError(f"{package}: private/debug artifact present: {name}")
        if package == "dev.droiduse.assistant":
            required = ["assets/paddle/tiny/det/inference.onnx",
                        "assets/paddle/tiny/rec/inference.onnx",
                        "assets/paddle/tiny/rec/inference.yml"]
            if any(name not in names or archive.getinfo(name).file_size == 0 for name in required):
                raise ValueError("assistant: tiny OCR assets are missing or empty")
            if not any(name.startswith("lib/arm64-v8a/") and name.endswith(".so") for name in names):
                raise ValueError("assistant: missing Pixel 6 arm64 native libraries")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"package": package, "versionCode": manifest.get(ANDROID + "versionCode"),
            "sha256": digest.hexdigest(),
            "bytes": apk.stat().st_size, "certificateSha256": certificate}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assistant", required=True, type=Path)
    parser.add_argument("--executor", required=True, type=Path)
    parser.add_argument("--certificate-sha256", required=True,
                        help="Expected dedicated release certificate digest; not a private key")
    parser.add_argument("--apkanalyzer", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--output", required=True, type=Path, help="New staging directory outside the ROM source tree")
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
        with tempfile.TemporaryDirectory(prefix=".rom-apps-", dir=output.parent) as temporary:
            staging = Path(temporary) / "droiduse"
            prebuilts = staging / "prebuilts"
            prebuilts.mkdir(parents=True)
            packages = []
            for source, module, package in (
                (args.assistant, "DroidUseAssistant", "dev.droiduse.assistant"),
                (args.executor, "DroidUseExecutor", "dev.droiduse.executor"),
            ):
                copied = prebuilts / f"{module}.apk"
                shutil.copyfile(source, copied)
                packages.append(inspect(copied, package, certificate, args.apkanalyzer, args.apksigner))
            for name in ("Android.bp", "product.mk"):
                shutil.copyfile(ROOT / "platform/rom/integration" / f"{name}.example", staging / name)
            (staging / "verification.json").write_text(json.dumps({
                "deviceAssumption": "oriole", "packages": packages,
                "status": "APK checks passed; Soong compilation and device behavior not verified",
                "backendReady": False,
            }, indent=2) + "\n")
            # Reserve the destination exclusively. Failed publication leaves no usable bundle.
            output.mkdir()
            try:
                for child in staging.iterdir():
                    shutil.move(str(child), output / child.name)
                (output / "COMPLETE").write_text("APK staging completed; not a ROM build result.\n")
            except Exception:
                shutil.rmtree(output)
                raise
        print(f"Staged Pixel 6 APK imports: {output}")
    except (OSError, ValueError, zipfile.BadZipFile, ET.ParseError) as error:
        parser.exit(1, f"Cannot stage: {error}\n")


if __name__ == "__main__":
    main()
