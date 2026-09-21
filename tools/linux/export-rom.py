#!/usr/bin/env python3
"""Export a successful build's deliverables into a NEW directory, never flash."""
import argparse
import hashlib
import pathlib
import shutil
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=pathlib.Path, help='build-baseline.sh run directory')
    parser.add_argument('destination', type=pathlib.Path, help='must not already exist')
    args = parser.parse_args()
    run = args.run.resolve()
    if not (run / 'exit-code.txt').is_file() or (run / 'exit-code.txt').read_text().strip() != '0':
        parser.error('build has not finished successfully')
    for name in ['manifest.xml', 'target.txt', 'product-out.txt', 'started.txt', 'source-status.txt']:
        if not (run / name).is_file():
            parser.error(f'missing build record: {name}')
    product = pathlib.Path((run / 'product-out.txt').read_text().strip()).resolve()
    packages = sorted(product.glob('lineage-*-oriole*.zip'), key=lambda p: p.stat().st_mtime)
    if not packages:
        parser.error('no Pixel 6 ROM zip found')
    newest = packages[-1]
    if newest.stat().st_mtime < (run / 'started.txt').stat().st_mtime:
        parser.error('ROM zip predates this run; refusing to export an old build as new')
    selected = [newest]
    # Copy only install-related images that exist; the install guide decides what is required.
    for name in ['boot.img', 'dtbo.img', 'vendor_boot.img', 'vbmeta.img', 'recovery.img']:
        p = product / name
        if p.is_file():
            selected.append(p)
    selected += [run / n for n in ['manifest.xml', 'target.txt', 'source-status.txt',
                                  'started.txt', 'finished.txt', 'exit-code.txt',
                                  'resources.txt', 'build.log'] if (run / n).is_file()]
    if any(p.is_symlink() for p in selected):
        parser.error('unexpected symlink among selected artifacts')
    dest = args.destination.resolve()
    if dest.exists():
        parser.error('destination already exists; choose a new directory')
    dest.mkdir(parents=True)
    # An interrupted copy has no COMPLETE marker. Never treat it as a ready package.
    checksums = []
    for source in selected:
        target = dest / source.name
        shutil.copy2(source, target)
        digest = hashlib.sha256()
        with target.open('rb') as f:
            for chunk in iter(lambda: f.read(8 * 1024 * 1024), b''):
                digest.update(chunk)
        checksums.append(f'{digest.hexdigest()}  {target.name}\n')
    (dest / 'SHA256SUMS').write_text(''.join(checksums))
    (dest / 'COMPLETE').write_text('Export complete. Not a certification of bootability or firmware compatibility.\n')
    print(f'Exported {len(selected)} files to {dest}')


if __name__ == '__main__':
    main()
