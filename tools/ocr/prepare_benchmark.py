#!/usr/bin/env python3
"""Prepare ignored on-device benchmark assets from previously captured evidence."""
from pathlib import Path
import json,shutil,hashlib
root=Path(__file__).resolve().parents[2]
out=root/'apps/assistant/build/generated/paddleBenchmark';out.mkdir(parents=True,exist_ok=True)
models=root/'build/paddle-source/models'
for size in ['tiny','small']:
 for stage in ['det','rec']:
  source=next(models.glob(f'PP-OCRv6_{size}_{stage}*/inference.onnx'))
  target=out/f'paddle/{size}/{stage}';target.mkdir(parents=True,exist_ok=True)
  shutil.copyfile(source,target/'inference.onnx')
  if stage=='rec':shutil.copyfile(source.with_name('inference.yml'),target/'inference.yml')
samples=[]
def add(run,name,id,expected=(),page=None,reading=False):
 source=root/f'build/phone-benchmark/phone-run-{run}/no_backup/phone-run-{run}/{name}.png'
 target=out/f'ocr-samples/{id}.png';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
 samples.append(dict(id=id,asset=f'ocr-samples/{id}.png',expected=list(expected),page=page,reading=reading,sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
add(86782187,'0007','old-heading',['第1章被强制营业的秦轩'],1)
add(87714857,'0007','failed-heading',['第1章别开生面的相亲'],1)
add(87613556,'0005','failed-button',['直播签到全网最抽象主播','9.1分'])
add(87865333,'0005','candidates',['让你去心动6你只跟姐姐打直球','9.2分','都市脑洞'])
add(87865333,'0006','detail',['9.2分','34.5万字','系统','都市脑洞'])
for page in range(1,41):add(87865333,f'{page+6:04d}',f'reading-{page:02d}',page=page,reading=True)
(out/'ocr-samples.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2))
print(f'Prepared {len(samples)} samples and tiny/small models in ignored build assets')
