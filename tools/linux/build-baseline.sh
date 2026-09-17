#!/usr/bin/env bash
# Build-only entry point after a complete, reviewed device checkout exists.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Run on x86_64 Linux.'; exit 2; }
root=${1:?Usage: build-baseline.sh /android/source exact-lunch-target [--build]}
target=${2:?Provide the exact target from the checked-out Pixel 6 build configuration}
[[ $target == lineage_oriole-* && $target != *[[:space:]]* ]] || { echo 'Only an explicit Pixel 6 lineage_oriole target is accepted.'; exit 2; }
bash "$(dirname "$0")/check-build-tree.sh" "$root"
if [[ ${3:-} != --build ]]; then printf 'Preview only: lunch %s and m bacon -j8. Add --build to run.\n' "$target"; exit 0; fi
cd "$root"
repo manifest -r -o droiduse-manifest.before-build.xml
# AOSP environment scripts are not guaranteed nounset-clean.
set +u
source build/envsetup.sh
lunch "$target"
m bacon -j8
printf 'Build finished. No device has been flashed.\n'
