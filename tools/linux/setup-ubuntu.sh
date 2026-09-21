#!/usr/bin/env bash
# Installs build tools only. No source sync, flashing, disk formatting or SSH changes.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Requires x86_64 Linux.'; exit 2; }
source /etc/os-release
[[ $ID == ubuntu && ( $VERSION_ID == 22.04 || $VERSION_ID == 24.04 ) ]] || { echo 'Prepared for Ubuntu 22.04/24.04 LTS; review package compatibility on other releases.'; exit 2; }
packages=(git git-lfs gnupg flex bison build-essential zip curl zlib1g-dev libc6-dev-i386 x11proto-core-dev libx11-dev lib32z1-dev libgl1-mesa-dev libxml2-utils xsltproc unzip fontconfig python3 python-is-python3 python3-lxml python3-yaml python3-protobuf protobuf-compiler repo adb fastboot openjdk-17-jdk rsync time tmux)
if [[ ${1:-} != --install ]]; then
  printf 'Preview only. Install with: bash %q --install\n' "$0"
  printf 'Packages: %s\n' "${packages[*]}"
  exit 0
fi
sudo apt-get update
sudo apt-get install -y "${packages[@]}"
repo version
java -version
printf 'Tools installed. Full Pixel 6 source manifest and vendor/firmware alignment still require verification.\n'
printf 'Ubuntu repo launcher may be too old for --git-lfs. Follow docs/rom-baseline-runbook.md to install the official launcher as the build user.\n'
