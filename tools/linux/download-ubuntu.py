#!/usr/bin/env python3
"""Discover an official amd64 Desktop ISO; download only with --download. No USB writes."""
import argparse, hashlib, re, urllib.request
from pathlib import Path
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--download', action='store_true')
parser.add_argument('--directory', type=Path, default=Path.home() / 'Downloads')
args = parser.parse_args()
base = 'https://releases.ubuntu.com/24.04/'
with urllib.request.urlopen(base + 'SHA256SUMS', timeout=30) as response:
    manifest = response.read().decode('ascii')
images = re.findall(r'^([0-9a-f]{64})\s+\*?(ubuntu-24\.04(?:\.\d+)*-desktop-amd64\.iso)$', manifest, re.M)
if not images:
    raise SystemExit('No official desktop image found; inspect release listing manually.')
expected, filename = max(images, key=lambda item: tuple(map(int, item[1].split('-')[1].split('.'))))
print(base + filename)
print('SHA256:', expected)
if not args.download:
    print('Preview only. Add --download to fetch the multi-GB ISO.')
    raise SystemExit(0)
args.directory.mkdir(parents=True, exist_ok=True)
target = args.directory / filename
part = target.with_suffix('.iso.part')
def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''): h.update(chunk)
    return h.hexdigest()
if target.exists():
    if digest(target) == expected:
        print('Already downloaded and verified:', target); raise SystemExit(0)
    raise SystemExit('Existing ISO differs from official hash; not overwritten.')
with urllib.request.urlopen(base + filename, timeout=60) as response, part.open('wb') as output:
    while chunk := response.read(1024 * 1024): output.write(chunk)
if digest(part) != expected:
    raise SystemExit('Checksum mismatch; .part retained for inspection, do not use it.')
part.rename(target)
print('Verified against HTTPS SHA256SUMS:', target)
