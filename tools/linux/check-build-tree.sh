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
python3 "$(dirname "$0")/check-rom-tree.py" "$root"
git -C "$root/frameworks/base" rev-parse HEAD
printf 'Structural checks passed, not a guarantee of build/boot compatibility. Verify device firmware separately. No build or flash performed.\n'
