#!/usr/bin/env python3
"""Collect existing phone evidence after a run; does not execute task actions."""
import argparse,io,json,re,subprocess,tarfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);args=p.parse_args()
adb=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',args.serial]
listing=subprocess.check_output(adb+['shell','run-as','dev.droiduse.assistant','ls','no_backup'],text=True).splitlines()
run=max((x for x in listing if re.fullmatch(r'phone-run-\d+',x)),key=lambda x:int(x.rsplit('-',1)[1]))
metric=max((x for x in listing if re.fullmatch(r'task-metrics-\d+\.jsonl',x)),key=lambda x:int(x.split('-')[-1].split('.')[0]))
id=metric.split('-')[-1].split('.')[0]
names=[run]+[x for x in [metric,f'task-decisions-{id}.jsonl',f'task-result-{id}.json',f'task-journal-{id}.jsonl'] if x in listing]
archive=subprocess.check_output(adb+['exec-out','run-as','dev.droiduse.assistant','tar','cf','-']+['no_backup/'+x for x in names])
out=root/'build/phone-benchmark'/run;out.mkdir(parents=True,exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:tar.extractall(out,filter='data')
result=out/'no_backup'/f'task-result-{id}.json'
print('Evidence:',out)
if result.exists():
 data=json.loads(result.read_text());print('State:',data['state'],'Elapsed:',data['elapsedMs']/1000,'seconds')
else:print('Task has not saved a final result yet')
reading=out/'no_backup'/run/'reading.json'
if reading.exists():
 data=json.loads(reading.read_text());print('Reading complete:',data.get('complete'),'Pages:',len(data.get('chapters',[])))
for name in ['reading-error.json','transport-error.json']:
 error=out/'no_backup'/run/name
 if error.exists():print(name,error.read_text()[:500])
