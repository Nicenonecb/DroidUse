#!/usr/bin/env python3
"""One-time-per-boot debug bootstrap. The task itself runs on the phone, not this script."""
import argparse,io,os,subprocess,tempfile,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);args=p.parse_args()
adb=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',args.serial]
apk=root/'apps/assistant/build/outputs/apk/debug/assistant-debug.apk'
def run(*parts,**kwargs):return subprocess.run(adb+list(parts),check=True,**kwargs)
# Read artifacts from the exact APK being installed; credentials never appear in command arguments.
with zipfile.ZipFile(apk) as z,tempfile.TemporaryDirectory(prefix='droiduse-phone-') as temporary:
 # Refuse mixed artifacts: a newer test APK with an older bundled helper hides test failures.
 with zipfile.ZipFile(io.BytesIO(z.read('assets/phone-executor.jar'))) as host, zipfile.ZipFile(root/'platform/executor/prototype-virtual-display/build/probe.apk') as probe:
  if host.read('classes.dex') != probe.read('classes.dex'):
   raise SystemExit('Helper/test APK mismatch. Rebuild assistant APK after probe.py build.')
 for asset,name in [('phone-executor.jar','droiduse-phone.jar'),('bridge-token','droiduse-phone.token')]:
  path=Path(temporary)/name;path.write_bytes(z.read('assets/'+asset));path.chmod(0o600)
  run('push',str(path),'/data/local/tmp/'+name,stdout=subprocess.DEVNULL)
 run('shell','chmod','600','/data/local/tmp/droiduse-phone.token')
run('install','--no-incremental','-r',str(root/'platform/executor/prototype-virtual-display/build/probe.apk'))
run('install','--no-incremental','-r',str(apk))
# Stop only this project's previous daemon, if one exists. It has a shutdown hook for its virtual display.
processes=subprocess.check_output(adb+['shell','ps','-A','-o','PID,ARGS'],text=True)
for line in processes.splitlines():
 if 'dev.droiduse.probe.PhoneBridge ' in line:
  run('shell','kill','-TERM',line.strip().split()[0])
# The new phone daemon is detached from the USB shell, with no inherited stdin/stdout connection.
run('shell',"CLASSPATH=/data/local/tmp/droiduse-phone.jar nohup app_process / dev.droiduse.probe.PhoneBridge /data/local/tmp/droiduse-phone.token </dev/null >/data/local/tmp/droiduse-phone.log 2>&1 &")
# Remove only our old desktop bridge mapping. No port forwarding is used by the new runtime.
subprocess.run(adb+['reverse','--remove','tcp:8765'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
run('shell','am','start','-n','dev.droiduse.assistant/.MainActivity')
print('Bootstrap sent. Check /data/local/tmp/droiduse-phone.log for PHONE_BRIDGE_READY. No desktop server is needed.')
