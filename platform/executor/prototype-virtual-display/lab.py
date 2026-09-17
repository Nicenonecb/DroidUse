#!/usr/bin/env python3
"""Run a bounded two-UID isolation experiment; restores IME and test permissions."""
import argparse
import json
import queue
import re
import shlex
import subprocess
import threading
import time
from pathlib import Path
from probe import ADB, BUILD, run

FG = 'dev.droiduse.foreground'
BG = 'dev.droiduse.probe'
IME = BG + '/.ProbeIme'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
adb = [str(ADB), '-s', args.serial]
out = BUILD / 'isolation'
out.mkdir(exist_ok=True)
(out / 'steps.txt').write_text('')

def shell(*cmd, check=True):
    result = subprocess.run(adb + ['shell', shlex.join(cmd)], text=True, capture_output=True, check=check)
    return result.stdout.strip()

def command(pkg, op):
    shell('am', 'broadcast', '-a', 'dev.droiduse.lab.COMMAND', '-p', pkg, '--es', 'op', op)
    time.sleep(.25)

def mark(name):
    print(name, flush=True)
    with (out / 'steps.txt').open('a') as f:
        f.write(f'{time.time():.3f} {name}\n')

def focus(name):
    dump = shell('dumpsys', 'input')
    lines = dump.splitlines()
    start = next((i for i, s in enumerate(lines) if 'FocusedDisplayId:' in s), 0)
    (out / (name + '-focus.txt')).write_text('\n'.join(lines[start:start+10]))

def commit(target, text):
    shell('am', 'broadcast', '-a', 'dev.droiduse.probe.COMMIT', '-p', BG,
          '--es', 'target', target, '--es', 'text', text)
    time.sleep(.3)

host = None
logs = None
logfile = None
old_ime = shell('settings', 'get', 'secure', 'default_input_method')
old_enabled = shell('settings', 'get', 'secure', 'enabled_input_methods')
(out / 'restore.json').write_text(json.dumps({'default_input_method': old_ime,
                                             'enabled_input_methods': old_enabled}, indent=2))
try:
    run(*adb, 'install', '-r', BUILD / 'probe.apk')
    run(*adb, 'install', '-r', BUILD / 'foreground.apk')
    for pkg in (FG, BG):
        for op in ('TAKE_AUDIO_FOCUS', 'PLAY_AUDIO', 'RECORD_AUDIO', 'CAMERA'):
            shell('cmd', 'appops', 'set', '--uid', pkg, op, 'allow')
            shell('cmd', 'appops', 'set', pkg, op, 'allow')
    run(*adb, 'push', BUILD / 'host.jar', '/data/local/tmp/droiduse-probe.jar')
    logfile = (out / 'events.txt').open('w')
    logs = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1', '-s', 'DroidUseLab:I', '*:S'], stdout=logfile)
    host = subprocess.Popen(adb + ['shell', 'CLASSPATH=/data/local/tmp/droiduse-probe.jar',
        'app_process', '/', 'dev.droiduse.probe.DisplayHost'], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    q = queue.Queue()
    def consume():
        for line in host.stdout:
            q.put(line.strip())
        q.put('EXIT')
    threading.Thread(target=consume, daemon=True).start()
    def wait(prefix):
        deadline = time.monotonic() + 20
        while True:
            line = q.get(timeout=max(.01, deadline-time.monotonic()))
            if line.startswith(prefix): return line
            if line == 'EXIT': raise RuntimeError('Host exited')
    display = wait('DISPLAY_ID=').split('=')[1]
    if int(display) <= 0: raise RuntimeError('Invalid virtual display')
    def shot(name):
        host.stdin.write('capture\n'); host.stdin.flush()
        wait('CAPTURED')
        run(*adb, 'pull', '/data/local/tmp/droiduse-probe.png', out / (name + '.png'), stdout=subprocess.DEVNULL)
    def start(pkg, target_display):
        shell('am', 'start', '--display', target_display, '-n', pkg + '/dev.droiduse.probe.LabActivity')
        time.sleep(.6)
    start(FG, '0')
    start(BG, display)
    mark('DISPLAY=' + display)
    focus('initial')
    shell('CLASSPATH=/data/local/tmp/droiduse-probe.jar', 'app_process', '/', 'dev.droiduse.probe.GestureProbe', display)
    shell('input', '-d', display, 'tap', '350', '169')
    shell('input', '-d', display, 'swipe', '350', '169', '350', '169', '900')
    focus('gestures')
    # Two independent editable windows; do not use the user's clipboard.
    shell('ime', 'enable', IME)
    shell('ime', 'set', IME)
    time.sleep(.5)
    command(FG, 'edit')
    commit(FG, '主屏中文甲')
    command(FG, 'inspect')
    command(BG, 'edit')
    commit(BG, '后台中文乙')
    command(FG, 'inspect'); command(BG, 'inspect')
    focus('after-background-ime'); shot('background-ime')
    command(FG, 'edit'); commit(FG, '主屏继续丙'); command(FG, 'inspect')
    mark('IME_TEST_COMPLETE')
    # Restore the user's keyboard immediately after the input experiment.
    shell('ime', 'set', old_ime)
    shell('settings', 'put', 'secure', 'enabled_input_methods', old_enabled)
    command(BG, 'dialog'); shot('dialog'); focus('dialog')
    shell('input', '-d', display, 'keyevent', 'KEYCODE_BACK')
    command(BG, 'jump')
    # Launch animation is not a settled result; wait for the target window.
    for _ in range(30):
        tasks = shell('dumpsys', 'activity', 'activities')
        section = re.search(r'Display #' + display + r'\b.*?(?=\nDisplay #|\Z)', tasks, re.S)
        if section and 'com.baidu.searchbox' in section.group(0):
            break
        time.sleep(.2)
    else:
        raise RuntimeError('Baidu did not launch on the virtual display')
    time.sleep(1)
    focus('cross-app'); shot('cross-app')
    shell('input', '-d', display, 'keyevent', 'KEYCODE_BACK')
    start(BG, display)
    shell('am', 'force-stop', FG)
    start(FG, '0')
    focus('resource-baseline')
    if FG not in (out / 'resource-baseline-focus.txt').read_text():
        raise RuntimeError('Foreground control is not focused; resource verdict would be invalid')
    mark('NAVIGATION_TEST_COMPLETE')
    # Main silent media owns focus. Background then tries permanent and duck focus.
    command(FG, 'focus'); command(FG, 'play')
    command(BG, 'focus'); command(BG, 'play')
    time.sleep(3)
    command(BG, 'stop'); command(FG, 'focus'); command(BG, 'duck')
    time.sleep(3)
    mark('AUDIO_UNRESTRICTED_COMPLETE')
    command(BG, 'stop')
    shell('cmd', 'appops', 'set', '--uid', BG, 'TAKE_AUDIO_FOCUS', 'ignore')
    shell('cmd', 'appops', 'set', '--uid', BG, 'PLAY_AUDIO', 'ignore')
    command(FG, 'focus'); command(BG, 'focus'); command(BG, 'play')
    mark('AUDIO_APPOPS_CONTROL_COMPLETE')
    for op in ('TAKE_AUDIO_FOCUS', 'PLAY_AUDIO'):
        shell('cmd', 'appops', 'set', '--uid', BG, op, 'allow')
    command(BG, 'stop'); command(FG, 'stop')
    # Separate UID requests: check privilege granted independently of display.
    for pkg in (FG, BG):
        shell('pm', 'grant', pkg, 'android.permission.RECORD_AUDIO')
        shell('pm', 'grant', pkg, 'android.permission.CAMERA')
    command(FG, 'mic'); time.sleep(.6); command(BG, 'mic'); time.sleep(.8); command(FG, 'micstate')
    mark('MIC_BOTH_GRANTED_COMPLETE')
    command(BG, 'stop'); command(FG, 'stop')
    shell('cmd', 'appops', 'set', '--uid', BG, 'RECORD_AUDIO', 'ignore')
    command(FG, 'mic'); time.sleep(.6); command(BG, 'mic'); time.sleep(.8); command(FG, 'micstate')
    mark('MIC_APPOPS_CONTROL_COMPLETE')
    command(BG, 'stop'); command(FG, 'stop')
    shell('cmd', 'appops', 'set', '--uid', BG, 'RECORD_AUDIO', 'allow')
    command(BG, 'camera'); time.sleep(.8); command(BG, 'stop')
    command(FG, 'camera'); command(BG, 'camera'); time.sleep(.8)
    mark('CAMERA_BOTH_GRANTED_COMPLETE')
    command(BG, 'stop'); command(FG, 'stop')
    shell('cmd', 'appops', 'set', '--uid', BG, 'CAMERA', 'ignore')
    (out / 'camera-appops.txt').write_text(shell('cmd', 'appops', 'get', BG, 'CAMERA'))
    command(FG, 'camera'); command(BG, 'camera'); time.sleep(.8)
    mark('CAMERA_APPOPS_CONTROL_COMPLETE')
    command(BG, 'stop'); command(FG, 'stop')
    # Refuse before acquisition: compare runtime permission denial against AppOps ignoring.
    shell('pm', 'revoke', BG, 'android.permission.RECORD_AUDIO')
    shell('pm', 'revoke', BG, 'android.permission.CAMERA')
    start(BG, display)
    command(FG, 'mic'); time.sleep(.6); command(BG, 'mic'); time.sleep(.6)
    command(FG, 'stop'); command(BG, 'stop')
    command(FG, 'camera'); command(BG, 'camera'); time.sleep(.8)
    mark('RUNTIME_DENIAL_CONTROL_COMPLETE')
    command(BG, 'stop'); command(FG, 'stop')
    focus('final'); shot('final')
    shell('pm', 'clear-permission-flags', BG, 'android.permission.CAMERA', 'user-set', 'user-fixed')
    command(BG, 'permission'); time.sleep(.5)
    focus('permission-dialog'); shot('permission-dialog')
    (out / 'permission-windows.txt').write_text('\n'.join(line for line in shell('dumpsys', 'window', 'displays').splitlines()
        if 'mDisplayId=' in line or 'mCurrentFocus=' in line or 'mFocusedApp=' in line))
finally:
    shell('ime', 'set', old_ime, check=False)
    shell('settings', 'put', 'secure', 'enabled_input_methods', old_enabled, check=False)
    for pkg in (FG, BG):
        shell('am', 'force-stop', pkg, check=False)
        for permission in ('RECORD_AUDIO', 'CAMERA'):
            shell('pm', 'revoke', pkg, 'android.permission.' + permission, check=False)
        shell('cmd', 'appops', 'reset', pkg, check=False)
    if host is not None and host.poll() is None:
        try:
            host.stdin.write('quit\n'); host.stdin.flush(); host.wait(timeout=5)
        except (BrokenPipeError, subprocess.TimeoutExpired): host.terminate()
    if logs is not None:
        logs.terminate(); logs.wait(timeout=5)
    if logfile is not None: logfile.close()
    mark('CLEANUP_COMPLETE')
print('Evidence:', out)
