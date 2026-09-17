#!/usr/bin/env bash
# Repeatable APK-only iteration; never flashes the phone.
set -euo pipefail
serial=${1:?Usage: tools/install-debug.sh adb-serial}
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
adb_bin=${ADB:-}
if [[ -z $adb_bin ]]; then
    sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [[ -z $sdk && -f local.properties ]]; then sdk=$(sed -n 's/^sdk.dir=//p' local.properties); fi
    adb_bin=${sdk:+$sdk/platform-tools/adb}
fi
[[ -n $adb_bin && -x $adb_bin ]] || { echo 'Set ADB to the adb executable, or configure local.properties sdk.dir.'; exit 2; }
[[ $("$adb_bin" -s "$serial" get-state) == device ]] || { echo 'Device is not authorized/connected.'; exit 2; }
./gradlew :assistant:assembleDebug :executor:assembleDebug
"$adb_bin" -s "$serial" install -r platform/executor/app/build/outputs/apk/debug/executor-debug.apk
"$adb_bin" -s "$serial" install -r apps/assistant/build/outputs/apk/debug/assistant-debug.apk
"$adb_bin" -s "$serial" shell am start -n dev.droiduse.assistant/.MainActivity
