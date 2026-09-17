#!/usr/bin/env python3
"""Disposable Android multi-display experiment; requires JDK 17 and SDK 36."""
import argparse
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / 'build'
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
TOOLS = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
ADB = SDK / 'platform-tools/adb'

def run(*args, **kwargs):
    kwargs.setdefault('stdin', subprocess.DEVNULL)
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def build():
    BUILD.mkdir(exist_ok=True)
    (BUILD / "video-assets").mkdir(exist_ok=True)
    classes = BUILD / 'classes'
    classes.mkdir(exist_ok=True)
    run('javac', '-source', '17', '-target', '17', '-classpath', ANDROID,
        '-d', classes, *sorted((ROOT / 'src').rglob('*.java')))
    run(TOOLS / 'd8', '--lib', ANDROID, '--min-api', '30', '--output', BUILD,
        *sorted(classes.rglob('*.class')))
    with zipfile.ZipFile(BUILD / 'host.jar', 'w') as jar:
        jar.write(BUILD / 'classes.dex', 'classes.dex')
    run(TOOLS / 'aapt2', 'compile', '--dir', ROOT / 'res', '-o', BUILD / 'resources.zip')
    run(TOOLS / 'aapt2', 'link', '-I', ANDROID, '--manifest', ROOT / 'AndroidManifest.xml',
        BUILD / 'resources.zip', '-A', BUILD / 'video-assets',
        '-o', BUILD / 'unsigned.apk')
    with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a') as apk:
        apk.write(BUILD / 'classes.dex', 'classes.dex')
    key = BUILD / 'debug.keystore'
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=DroidUse Prototype',
            '-keyalg', 'RSA', '-validity', '3650')
    run(TOOLS / 'zipalign', '-f', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk')
    run(TOOLS / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android',
        '--out', BUILD / 'probe.apk', BUILD / 'aligned.apk')
    run(TOOLS / 'apksigner', 'verify', BUILD / 'probe.apk')
    # Same test code under a distinct UID: resource contention cannot be tested within one UID.
    manifest = ET.parse(ROOT / 'AndroidManifest.xml')
    manifest.getroot().set('package', 'dev.droiduse.foreground')
    app = manifest.getroot().find('application')
    for child in list(app):
        if child.tag == 'service' or child.get('{http://schemas.android.com/apk/res/android}name') == '.ProbeActivity':
            app.remove(child)
    manifest.write(BUILD / 'ForegroundManifest.xml', encoding='utf-8', xml_declaration=True)
    run(TOOLS / 'aapt2', 'link', '-I', ANDROID, '--manifest', BUILD / 'ForegroundManifest.xml',
        '-o', BUILD / 'foreground-unsigned.apk')
    with zipfile.ZipFile(BUILD / 'foreground-unsigned.apk', 'a') as apk:
        apk.write(BUILD / 'classes.dex', 'classes.dex')
    run(TOOLS / 'zipalign', '-f', '4', BUILD / 'foreground-unsigned.apk', BUILD / 'foreground-aligned.apk')
    run(TOOLS / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android',
        '--out', BUILD / 'foreground.apk', BUILD / 'foreground-aligned.apk')
    run(TOOLS / 'apksigner', 'verify', BUILD / 'foreground.apk')
    print('BUILD OK:', BUILD / 'probe.apk')

def experiment(serial):
    adb = [str(ADB), '-s', serial]
    run(*adb, 'get-state')
    run(*adb, 'install', '-r', BUILD / 'probe.apk')
    run(*adb, 'push', BUILD / 'host.jar', '/data/local/tmp/droiduse-probe.jar')
    proc = subprocess.Popen(adb + ['shell', 'CLASSPATH=/data/local/tmp/droiduse-probe.jar',
                            'app_process', '/', 'dev.droiduse.probe.DisplayHost'],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)
    output = queue.Queue()
    def consume():
        for line in proc.stdout:
            print('[phone]', line.rstrip(), flush=True)
            output.put(line.rstrip())
        output.put('PROCESS_EXITED')
    threading.Thread(target=consume, daemon=True).start()
    def wait_for(pattern):
        import time
        deadline = time.monotonic() + 20
        while True:
            line = output.get(timeout=max(0.01, deadline - time.monotonic()))
            if line == 'PROCESS_EXITED':
                raise RuntimeError('Display host exited; inspect the phone error above')
            match = re.search(pattern, line)
            if match:
                return match
    try:
        display = int(wait_for(r'DISPLAY_ID=(\d+)').group(1))
        if display == 0:
            raise RuntimeError('Refusing to target the physical display')
        run(*adb, 'shell', 'am', 'start', '--display', str(display), '-n',
            'dev.droiduse.probe/.ProbeActivity')
        print(f'Virtual display {display}. Use your phone normally.')
        print('Commands: capture | tap X Y (720x1280 coordinates) | status | quit')
        while True:
            parts = input('probe> ').split()
            if not parts:
                continue
            if parts == ['quit']:
                break
            if parts == ['capture']:
                proc.stdin.write('capture\n'); proc.stdin.flush()
                match = wait_for(r'^(CAPTURED|NO_FRAME)$')
                if match.group(1) == 'CAPTURED':
                    run(*adb, 'pull', '/data/local/tmp/droiduse-probe.png', BUILD / 'virtual.png')
            elif len(parts) == 3 and parts[0] == 'tap':
                x, y = int(parts[1]), int(parts[2])
                if 0 <= x < 720 and 0 <= y < 1280:
                    run(*adb, 'shell', 'input', '-d', str(display), 'tap', str(x), str(y))
            elif parts == ['status']:
                result = run(*adb, 'shell', 'dumpsys', 'window', 'displays', capture_output=True, text=True)
                for line in result.stdout.splitlines():
                    if any(term in line for term in ('Display:', 'mCurrentFocus', 'mFocusedApp', 'mTopFocusedDisplayId')):
                        print(line)
            else:
                print('Unknown command')
    finally:
        if proc.poll() is None:
            try:
                proc.stdin.write('quit\n'); proc.stdin.flush()
                proc.wait(timeout=5)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                proc.terminate()
        run(*adb, 'shell', 'am', 'force-stop', 'dev.droiduse.probe')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'run'])
    parser.add_argument('--serial', help='Required for run; explicitly select the test device')
    args = parser.parse_args()
    if args.action == 'build':
        build()
    elif not args.serial:
        parser.error('run requires --serial')
    else:
        experiment(args.serial)
