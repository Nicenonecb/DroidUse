#!/usr/bin/env python3
"""Run isolated OCR instrumentation processes and collect private phone evidence."""
import argparse,datetime,io,json,subprocess,tarfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--models',nargs='+',choices=['tiny','small','mlkit'],default=['tiny','small','mlkit']);args=p.parse_args()
adb=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',args.serial]
out=root/'build/ocr-benchmark'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S');out.mkdir(parents=True)
for model in args.models:
 print('Running',model,flush=True)
 command=adb+['shell','am','instrument','-w','-e','class','dev.droiduse.assistant.PaddleOcrBenchmarkTest','-e','ocrModel',model,'dev.droiduse.assistant.test/androidx.test.runner.AndroidJUnitRunner']
 result=subprocess.run(command,capture_output=True,text=True,timeout=600)
 (out/(model+'.log')).write_text(result.stdout+result.stderr)
 if result.returncode or 'OK (1 test)' not in result.stdout:raise RuntimeError('Benchmark failed; see '+str(out/(model+'.log')))
 raw=subprocess.check_output(adb+['exec-out','run-as','dev.droiduse.assistant','tar','cf','-',f'no_backup/ocr-benchmark-{model}'])
 with tarfile.open(fileobj=io.BytesIO(raw)) as tar:tar.extractall(out,filter='data')
 summary=json.loads((out/f'no_backup/ocr-benchmark-{model}/summary.json').read_text())
 print(json.dumps(summary,ensure_ascii=False),flush=True)
print('Evidence:',out)
