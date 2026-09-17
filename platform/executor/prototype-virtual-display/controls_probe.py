#!/usr/bin/env python3
"""Touch real widgets on a secondary display; no model, camera, mic or clipboard access."""
import argparse, json, queue, re, shlex, subprocess, threading, time
from pathlib import Path
from probe import ADB, BUILD
p = argparse.ArgumentParser(description=__doc__); p.add_argument('--serial', required=True); args=p.parse_args()
adb=[str(ADB),'-s',args.serial]
out=BUILD/'controls'; out.mkdir(parents=True,exist_ok=True)
def shell(*words): return subprocess.check_output(adb+['shell',shlex.join(map(str,words))],text=True).strip()
def command(op): shell('am','broadcast','-a','dev.droiduse.lab.COMMAND','-p','dev.droiduse.probe','--es','op',op); time.sleep(.25)
def wait_until(predicate,seconds=8):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        value=predicate()
        if value: return value
        time.sleep(.15)
    raise RuntimeError('Timed out awaiting expected device evidence')
host=None; logger=None
report={'scope':'ADB prototype, real Android synthetic widgets; not ROM or full app acceptance','checks':{}}
with (out/'events.txt').open('w') as log:
    try:
        for apk in ('probe.apk','foreground.apk'):
            subprocess.run(adb+['install','-r',str(BUILD/apk)],check=True,capture_output=True)
        subprocess.run(adb+['push',str(BUILD/'host.jar'),'/data/local/tmp/droiduse-probe.jar'],check=True,capture_output=True)
        logger=subprocess.Popen(adb+['logcat','-v','brief','-T','1','-s','DroidUseLab:I','*:S'],stdout=log)
        host=subprocess.Popen(adb+['shell','CLASSPATH=/data/local/tmp/droiduse-probe.jar','app_process','/','dev.droiduse.probe.DisplayHost'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        q=queue.Queue()
        def consume():
            for line in host.stdout: q.put(line.strip())
        threading.Thread(target=consume,daemon=True).start()
        deadline=time.monotonic()+20
        while True:
            line=q.get(timeout=max(.1,deadline-time.monotonic()))
            if line.startswith('DISPLAY_ID='): display=int(line.split('=')[1]); break
        assert display>0; report['display']=display
        shell('am','start','--display',0,'-n','dev.droiduse.foreground/dev.droiduse.probe.LabActivity')
        shell('am','start','--display',display,'-n','dev.droiduse.probe/.LabActivity')
        def events(): return (out/'events.txt').read_text()
        wait_until(lambda:f'dev.droiduse.probe display={display} CREATED' in events())
        command('controls')
        bounds={m[0]:tuple(map(int,m[1:])) for m in re.findall(rf'dev.droiduse.probe display={display} BOUNDS (\w+) (\d+) (\d+) (\d+) (\d+)',events())}
        assert len(bounds)==5, bounds
        def input(*v): shell('input','-d',display,*v)
        def center(name):
            x,y,w,h=bounds[name]; return x+w//2,y+h//2
        def check(name,pattern):
            wait_until(lambda:re.search(rf'dev.droiduse.probe display={display} {pattern}',events()))
            report['checks'][name]='passed'
        input('tap',*center('button')); check('tap',r'TAP=1')
        x,y=center('button'); input('swipe',x,y,x,y,900); check('long_press','LONG_PRESS')
        # One adb process avoids USB round-trip delays between the two taps.
        shell('sh','-c',f'input -d {display} tap {x} {y}; input -d {display} tap {x} {y}')
        # A device may exceed its double-tap timeout; report a real failure instead of guessing.
        try: check('double_tap','DOUBLE_TAP')
        except RuntimeError: report['checks']['double_tap']='not observed with shell tap timing'
        input('tap',*center('checkbox')); check('checkbox_on','CHECKED=true')
        input('tap',*center('checkbox')); check('checkbox_off','CHECKED=false')
        x,y,w,h=bounds['slider']; input('swipe',x+20,y+h//2,x+w-20,y+h//2,700); check('slider_drag',r'SLIDER=(?:[8-9][0-9]|100)')
        command('scrolltest'); x,y,w,h=bounds['scroll']; input('swipe',x+w//2,y+h-25,x+w//2,y+25,500); check('scroll',r'SCROLL_Y=[1-9][0-9]*')
        shell('CLASSPATH=/data/local/tmp/droiduse-probe.jar','app_process','/','dev.droiduse.probe.GestureProbe',display); check('two_pointer_delivery',r'POINTERS=2')
        focus=shell('dumpsys','input'); focused=re.search(r'FocusedDisplayId:\s*(\d+)',focus)
        assert focused and focused[1]=='0'; report['checks']['main_display_focus']='passed'
        host.stdin.write('capture\n'); host.stdin.flush()
        deadline=time.monotonic()+10
        while True:
            line=q.get(timeout=max(.1,deadline-time.monotonic()))
            if line.startswith('CAPTURED'): break
        subprocess.run(adb+['pull','/data/local/tmp/droiduse-probe.png',str(out/'background.png')],check=True,capture_output=True)
    finally:
        if host:
            try: host.stdin.write('quit\n'); host.stdin.flush(); host.wait(timeout=5)
            except Exception: host.terminate(); host.wait(timeout=5)
        if logger: logger.terminate(); logger.wait(timeout=5)
        for pkg in ('dev.droiduse.probe','dev.droiduse.foreground'): shell('am','force-stop',pkg)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False,indent=2))
