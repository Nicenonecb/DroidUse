#!/usr/bin/env python3
"""Read-only checkout checks. Never sync, extract blobs or patch sources."""
import json
import pathlib
import subprocess
import sys


def main():
    root = pathlib.Path(sys.argv[1]).resolve()
    # Repo sync can modify checkout structure while we inspect it. Do not race it.
    for proc in pathlib.Path('/proc').glob('[0-9]*/cmdline'):
        try:
            args = proc.read_bytes().split(b'\0')
        except (OSError, PermissionError):
            continue
        if b'sync' in args and any(a.endswith((b'/repo', b'/main.py')) for a in args):
            print('BLOCKED: Repo sync is running; wait until it finishes. No source files changed.')
            return 3
    required = [
        '.repo/repo/repo', 'build/envsetup.sh', 'build/soong/soong_ui.bash',
        'device/google/raviole/lineage_oriole.mk',
        'device/google/raviole/device-oriole.mk',
        'device/google/gs101/common.mk',
        'vendor/google/oriole/oriole-vendor.mk',
        'vendor/lineage/config/common_full_phone.mk',
    ]
    errors = [f'MISSING: {p}' for p in required if not (root / p).is_file()]
    for p in ['device/google/gs-common', 'device/google/raviole-kernels/6.1']:
        if not (root / p).is_dir() or not any((root / p).iterdir()):
            errors.append(f'MISSING/EMPTY: {p}')
    # Follow the checked-out dependency files, rather than assuming the list is frozen.
    pending = ['device/google/raviole']
    seen = set()
    while pending:
        rel = pending.pop()
        if rel in seen:
            continue
        seen.add(rel)
        directory = (root / rel).resolve()
        if not directory.is_relative_to(root):
            errors.append(f'INVALID dependency outside checkout: {rel}')
            continue
        if not directory.is_dir():
            errors.append(f'MISSING dependency: {rel}')
            continue
        depfile = directory / 'lineage.dependencies'
        if depfile.is_file():
            try:
                deps = json.loads(depfile.read_text())
                for dep in deps:
                    path = dep['target_path']
                    if not isinstance(path, str):
                        raise ValueError('target_path is not a string')
                    pending.append(path)
            except (ValueError, KeyError, TypeError) as exc:
                errors.append(f'INVALID {rel}/lineage.dependencies: {type(exc).__name__}')
    if errors:
        print('\n'.join(errors))
        print('Do not build yet. Missing vendor files require the matching official extraction workflow.')
        return 3
    repo = root / '.repo/repo/repo'
    listing = subprocess.run([sys.executable, str(repo), 'list', '-p'], cwd=root,
                             text=True, capture_output=True)
    if listing.returncode or not listing.stdout.strip():
        print('BLOCKED: Repo project list unavailable; finish initialization/sync first.')
        return 3
    paths = listing.stdout.splitlines()
    for rel in paths:
        directory = (root / rel).resolve()
        if not directory.is_relative_to(root) or not (directory / '.git').exists():
            errors.append(f'CHECKOUT MISSING: {rel}')
            continue
        result = subprocess.run(['git', '-C', str(directory), 'rev-parse', '--verify', 'HEAD'],
                                capture_output=True)
        if result.returncode:
            errors.append(f'CHECKOUT HEAD INVALID: {rel}')
    if errors:
        print('\n'.join(errors))
        return 3
    print(f'Checked {len(paths)} project HEADs and {len(seen)} device dependency paths.')
    print('Vendor file presence is not binary/firmware validation; lunch and compilation remain required.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
