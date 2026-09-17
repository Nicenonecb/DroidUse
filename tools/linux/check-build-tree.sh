#!/usr/bin/env bash
# Read-only build readiness check. Does not select a manifest or download source.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Requires x86_64 Linux.'; exit 2; }
root=${1:?Usage: check-build-tree.sh /path/to/android}
[[ -d $root ]] || { echo 'Source directory missing.'; exit 2; }
free_kb=$(df -Pk "$root" | awk 'END {print $4}')
mem_kb=$(awk '/MemTotal:/ {print $2}' /proc/meminfo)
printf 'Available disk: %s KiB; physical RAM: %s KiB\n' "$free_kb" "$mem_kb"
[[ $free_kb -ge 419430400 ]] || echo 'Disk below 400 GiB: review source/out usage before proceeding.'
[[ $mem_kb -ge 60000000 ]] || echo 'RAM below roughly 64 GB: concurrency and swap need review.'
[[ -d $root/.repo && -f $root/build/envsetup.sh ]] || { echo 'Complete Repo checkout not present.'; exit 3; }
[[ -d $root/device/google/raviole ]] || { echo 'Pixel 6 device tree missing.'; exit 3; }
git -C "$root/frameworks/base" rev-parse HEAD
printf 'Tree detected. Verify repo manifest -r, vendor blobs and firmware before lunch/build. No build or flash performed.\n'
