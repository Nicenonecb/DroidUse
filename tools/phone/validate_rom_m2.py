#!/usr/bin/env python3
"""Run installed M2 instrumentation and process-death checks on one test phone.

Requires the engineering ROM and matching debug/test APKs. Does not flash, unlock,
change SELinux, upload screenshots, or invoke a model. Evidence stays in build/.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('build/m2-validation'))
    parser.add_argument('--death-only', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
    attached = [line.split()[0] for line in devices if line.endswith('\tdevice')]
    if len(attached) != 1:
        raise SystemExit('Exactly one authorized phone is required')
    adb = ['adb', '-s', attached[0]]

    def command(*parts):
        return subprocess.check_output(adb + list(parts), text=True, stderr=subprocess.STDOUT, timeout=60)

    def state():
        return command('shell', 'dumpsys', 'droiduse')

    if 'activeSession=none' not in state():
        raise SystemExit('An existing session is active; stop it before testing')

    def instrument(package, classes, filename):
        try:
            output = command('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes,
                             package + '.test/androidx.test.runner.AndroidJUnitRunner')
        except subprocess.TimeoutExpired as error:
            partial = error.output or b''
            if isinstance(partial, bytes):
                partial = partial.decode(errors='replace')
            (args.output / filename).write_text(partial + '\nHOST_TIMEOUT\n')
            raise RuntimeError('Instrumentation timed out: ' + filename) from error
        (args.output / filename).write_text(output)
        if not re.search(r'OK \(\d+ tests?\)', output) or 'FAILURES!!!' in output:
            raise RuntimeError('Instrumentation failed: ' + filename)
        print('PASS ' + filename, flush=True)

    if not args.death_only:
        instrument('dev.droiduse.executor', 'dev.droiduse.executor.app.CaptureFileTest', 'capture-suite.txt')
        instrument('dev.droiduse.executor', ','.join('dev.droiduse.executor.app.RomM2Test#' + method for method in (
            'openCaptureInputAndClose', 'repeatedSessionsReleaseDisplays',
            'pauseRejectsInputAndResumeRestoresObservation',
            'staticDisplaySupportsRepeatedFreshCaptures', 'secureWindowPixelsAreNotCaptured')), 'rom-suite.txt')
        instrument('dev.droiduse.assistant', ','.join([
            'dev.droiduse.assistant.RomExecutorTest#assistantExecutesTapTextSwipeAndCancels',
            'dev.droiduse.assistant.RomExecutorTest#metadataAndLifecycleAreEnforced',
            'dev.droiduse.assistant.RomExecutorTest#assistantCannotCallRomDirectly',
            'dev.droiduse.assistant.TargetAdapterTest',
            'dev.droiduse.assistant.DeviceTest#binderSessionRequiresExplicitTarget',
        ]), 'assistant-suite.txt')

    for package in ('dev.droiduse.assistant', 'dev.droiduse.executor'):
        label = package.rsplit('.', 1)[1]
        with (args.output / (label + '-death-instrumentation.txt')).open('w') as output:
            proc = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'dev.droiduse.assistant.RomExecutorTest#holdSessionForProcessDeath',
                'dev.droiduse.assistant.test/androidx.test.runner.AndroidJUnitRunner'], stdout=output,
                stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 15
                active = state()
                while 'state=ACTIVE' not in active and time.monotonic() < deadline and proc.poll() is None:
                    time.sleep(.2)
                    active = state()
                if 'state=ACTIVE' not in active:
                    raise RuntimeError(label + ': no active session before kill')
                display_id = int(re.search(r'displayId=(\d+)', active).group(1))
                command('shell', 'am', 'force-stop', package)
                deadline = time.monotonic() + 15
                after = state()
                while 'activeSession=none' not in after and time.monotonic() < deadline:
                    time.sleep(.2)
                    after = state()
                (args.output / (label + '-death-state.txt')).write_text(active + '\nAFTER KILL\n' + after)
                if 'activeSession=none' not in after:
                    raise RuntimeError(label + ': session leaked after kill')
                displays = command('shell', 'dumpsys', 'display')
                (args.output / (label + '-death-displays.txt')).write_text(displays)
                listed = command('shell', 'cmd', 'display', 'get-displays')
                if re.search(r'^Display id ' + str(display_id) + r':', listed, re.MULTILINE):
                    raise RuntimeError(label + ': virtual display leaked after kill')
                print('PASS ' + label + ' death cleanup', flush=True)
            finally:
                if proc.poll() is None:
                    command('shell', 'am', 'force-stop', 'dev.droiduse.assistant')
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.terminate()
                    proc.wait(timeout=5)
    (args.output / 'final-state.txt').write_text(state())


if __name__ == '__main__':
    main()
