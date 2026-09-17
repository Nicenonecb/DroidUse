#!/usr/bin/env bash
# Read-only: never changes partitions, packages, mounts, or settings.
set -euo pipefail
if [[ $(uname -s) != Linux ]]; then echo 'Run this on Ubuntu (including a live USB session).'; exit 2; fi
uname -sm
cat /etc/os-release
lscpu | head -25
free -h
lsblk -o NAME,SIZE,TYPE,FSTYPE,LABEL,MOUNTPOINTS,MODEL
printf '\nMounted filesystem capacity:\n'
df -hT "${1:-$PWD}"
printf '\nBoot mode: '
if [[ -d /sys/firmware/efi ]]; then echo UEFI; else echo Legacy; fi
printf '\nThis is an inventory only. Partition ownership must be checked before installation.\n'
