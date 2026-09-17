#!/usr/bin/env python3
"""Measure resource separation on an existing owned virtual display. Test APKs only."""
import argparse
import shlex
import subprocess
import time
from probe import ADB, BUILD, run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--display', required=True, type=int)
args = parser.parse_args()
if args.display <= 0: parser.error('Requires a virtual display')
adb = [str(ADB), '-s', args.serial]
fg, bg = 'dev.droiduse.foreground', 'dev.droiduse.probe'
out = BUILD / 'virtual-device'
out.mkdir(exist_ok=True)
def shell(*cmd):
    return subprocess.check_output(adb + ['shell', shlex.join(cmd)], text=True).strip()
def command(pkg, op):
    shell('am', 'broadcast', '-a', 'dev.droiduse.lab.COMMAND', '-p', pkg, '--es', 'op', op)
    time.sleep(.3)

run(*adb, 'install', '-r', BUILD / 'foreground.apk')
run(*adb, 'install', '-r', BUILD / 'probe.apk')
with (out / 'resources.txt').open('w') as f:
    logs = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1', '-s', 'DroidUseLab:I', '*:S'], stdout=f)
    try:
        for pkg, display in [(fg, 0), (bg, args.display)]:
            shell('am', 'force-stop', pkg)
            for permission in ['RECORD_AUDIO', 'CAMERA']:
                shell('pm', 'grant', pkg, 'android.permission.' + permission)
            for op in ['PLAY_AUDIO', 'TAKE_AUDIO_FOCUS', 'RECORD_AUDIO', 'CAMERA']:
                shell('cmd', 'appops', 'set', '--uid', pkg, op, 'allow')
            shell('am', 'start', '--display', str(display), '-n', pkg + '/dev.droiduse.probe.LabActivity')
            time.sleep(.6)
        command(fg, 'focus'); command(fg, 'play')
        command(bg, 'focus'); command(bg, 'play')
        time.sleep(3)
        command(fg, 'playstate'); command(bg, 'playstate')
        command(fg, 'mic'); time.sleep(.6)
        command(bg, 'mic'); time.sleep(1)
        command(fg, 'micstate'); command(bg, 'micstate')
        command(fg, 'camera'); command(bg, 'camera'); time.sleep(1)
        command(bg, 'camera-known'); time.sleep(1)
        dump = shell('dumpsys', 'input').splitlines()
        start = next(i for i, line in enumerate(dump) if 'FocusedDisplayId:' in line)
        (out/'focus.txt').write_text('\n'.join(dump[start:start+10]))
        (out/'device-policy.txt').write_text(shell('dumpsys', 'virtualdevice'))
        print('Resource measurements saved:', out, flush=True)
    finally:
        for pkg in [fg, bg]:
            command(pkg, 'stop')
            shell('am', 'force-stop', pkg)
            for permission in ['RECORD_AUDIO', 'CAMERA']:
                shell('pm', 'revoke', pkg, 'android.permission.' + permission)
            shell('cmd', 'appops', 'reset', pkg)
        logs.terminate(); logs.wait(timeout=5)
