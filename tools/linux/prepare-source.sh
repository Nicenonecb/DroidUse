#!/usr/bin/env bash
# Explicit opt-in to the large source download. Never flashes or applies patches.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Run on x86_64 Linux.'; exit 2; }
root=${1:?Usage: prepare-source.sh /empty/source/directory [--sync]}
manifest_revision=705406eb22c0efc833a7821ca198e3f0c79b4115
if [[ ${2:-} != --sync ]]; then
    printf 'Preview: LineageOS manifest %s -> %s. This downloads hundreds of GB.\n' "$manifest_revision" "$root"
    echo 'Add --sync to execute. Framework/device/vendor revisions still require reconciliation before patched builds.'
    exit 0
fi
command -v repo >/dev/null
mkdir -p "$root"
[[ -z $(ls -A "$root") ]] || { echo 'Use an empty directory; existing checkout will not be modified.'; exit 3; }
free_kb=$(df -Pk "$root" | awk 'END {print $4}')
[[ $free_kb -ge 419430400 ]] || { echo 'Less than 400 GiB available; source sync not started.'; exit 3; }
cd "$root"
repo init -u https://github.com/LineageOS/android.git -b "$manifest_revision" --git-lfs --no-clone-bundle
repo sync -c -j8 --no-clone-bundle --no-tags
repo manifest -r -o droiduse-manifest.lock.xml
printf 'Base manifest synced and snapshot saved. Device/vendor repositories may still be missing; verify the Pixel 6 build instructions next.\n'
