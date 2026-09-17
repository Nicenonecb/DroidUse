#!/usr/bin/env python3
"""Fetch pinned framework reference files if requested, verify hashes and patch applicability.
Does not download a full ROM, modify a checkout, compile or flash a device.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fetch', action='store_true')
parser.add_argument('--source', type=Path, default=root / 'build/source-reference')
args = parser.parse_args()
lock = json.loads((root / 'source-lock.json').read_text())
for name, expected in lock['files'].items():
    dest = args.source / name
    if not dest.exists() and args.fetch:
        url = f"https://raw.githubusercontent.com/LineageOS/android_frameworks_base/{lock['revision']}/{name}"
        data = urllib.request.urlopen(url, timeout=30).read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f'Hash mismatch from download: {name}')
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Missing/modified source (no files overwritten): {name}')
for patch in sorted((root / 'patches').glob('*.patch')):
    subprocess.run(['git', 'apply', '--check', str(patch)], cwd=args.source, check=True)
print(f"Verified {len(lock['files'])} pinned files and patch applicability; ROM compilation NOT performed")
