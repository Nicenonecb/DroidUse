#!/usr/bin/env bash
# Build-only entry point after a complete, reviewed device checkout exists.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Run on x86_64 Linux.'; exit 2; }
root=${1:?Usage: build-baseline.sh /android/source exact-lunch-target [--build]}
target=${2:?Provide the exact target from the checked-out Pixel 6 build configuration}
[[ $# -le 3 && ( ${3:-} == '' || ${3:-} == --build ) ]] || { echo 'Third argument must be --build or omitted.'; exit 2; }
[[ $target == lineage_oriole-* && $target != *[[:space:]]* ]] || { echo 'Only an explicit Pixel 6 lineage_oriole target is accepted.'; exit 2; }
jobs=${ROM_JOBS:-8}
[[ $jobs =~ ^[1-9][0-9]*$ && $jobs -le 16 ]] || { echo 'ROM_JOBS must be 1..16 for this builder.'; exit 2; }
root=$(cd "$root" && pwd -P)
bash "$(dirname "$0")/check-build-tree.sh" "$root"
if [[ ${3:-} != --build ]]; then printf 'Preview only: lunch %s, build_kernel, then m bacon -j%s. Add --build to run.\n' "$target" "$jobs"; exit 0; fi
[[ $EUID -ne 0 ]] || { echo 'Build as rombuild, not root.'; exit 2; }
command -v flock >/dev/null
command -v /usr/bin/time >/dev/null
exec 9>"$root/.droiduse-build.lock"
flock -n 9 || { echo 'Another managed build holds the lock.'; exit 3; }
run_parent=${ROM_RUNS_DIR:-$HOME/rom-build-runs}
mkdir -p "$run_parent"
run=$(mktemp -d "$run_parent/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
printf 'Build logs: %s\n' "$run"
printf '%s\n' "$target" > "$run/target.txt"
printf '%s\n' "$root" > "$run/source-root.txt"
date -u +%FT%TZ > "$run/started.txt"
trap 'status=$?; printf "%s\n" "$status" > "$run/exit-code.txt"; date -u +%FT%TZ > "$run/finished.txt"' EXIT
cd "$root"
python3 .repo/repo/repo manifest -r -o "$run/manifest.xml"
python3 .repo/repo/repo status > "$run/source-status.txt"
# For the first baseline, no framework/device patch should already be applied.
if grep -qvE '^(nothing to commit \(working directory clean\)|[[:space:]]*)$' "$run/source-status.txt"; then
    echo "Checkout has status entries; inspect $run/source-status.txt before building a baseline."
    exit 3
fi
# Soong expects the default output directory to stay relative to the source root.
# An absolute OUT_DIR breaks host-test packaging path validation.
unset OUT_DIR_COMMON_BASE
export OUT_DIR=out
/usr/bin/time -v -o "$run/resources.txt" bash -c '
    set -eo pipefail
    source build/envsetup.sh
    kernel_source="$ANDROID_BUILD_TOP/out-kernel/google/gs-6.1"
    [[ -d "$kernel_source/.repo" && -f "$kernel_source/build_raviole.sh" ]] || {
        echo "Sync the Pixel 6 kernel source before building."
        exit 3
    }
    # Use the already synchronized kernel tree; do not change pinned sources here.
    export SKIP_KERNEL_SYNC=true SKIP_KERNEL_BUILD=false
    # This LineageOS version builds and installs the kernel as part of lunch.
    lunch "$1"
    [[ $TARGET_PRODUCT == lineage_oriole ]] || exit 3
    m bacon -j"$2"
' bash "$target" "$jobs" 2>&1 | tee "$run/build.log"
printf '%s\n' "$root/out/target/product/oriole" > "$run/product-out.txt"
printf 'Build finished. No device has been flashed.\n'
