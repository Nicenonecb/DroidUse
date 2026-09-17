#!/usr/bin/env python3
"""Compare steady five-second video windows, excluding concurrency start/end transitions."""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parents[2]
out=root/'build/runtime-service-evidence/video'
phase=json.loads((out/'phase-times.json').read_text())
rows=[]
for line in (out/'playback.log').read_text().splitlines():
 m=re.search(r'^\s*(\d+\.\d+).*DroidUseVideo:\s*(\{.*\})$',line)
 if m:
  d=json.loads(m[2]);d['epoch']=float(m[1]);rows.append(d)
start,end=phase['concurrentStartEpoch'],phase['concurrentEndEpoch']
# Current playback session only; do not mix prior trials.
rows=[r for r in rows if r['epoch']>=start-120]
def summarize(selected):
 assert len(selected)>=3,selected
 frames='android.media.mediaplayer.frames';drops='android.media.mediaplayer.dropped'
 counts=[r for r in selected if isinstance(r.get(frames),int) and isinstance(r.get(drops),int)]
 return {'windows':len(selected),'allPlaying':all(r.get('playing') is True for r in selected),
  'allRendered':all(r.get('rendered') is True for r in selected),
  'allPrimaryFocus':all(r.get('windowFocus') is True and r.get('display')==0 for r in selected),
  'uiFrames':sum(r.get('uiFrames',0) for r in selected),'uiOver50ms':sum(r.get('uiOver50ms',0) for r in selected),
  'uiP95MsByWindow':[r.get('uiP95Ms') for r in selected],
  'decoderCounterReset':any(b[frames]<a[frames] for a,b in zip(counts,counts[1:])),
  'decoderFramesDelta':counts[-1][frames]-counts[0][frames] if len(counts)>=2 and all(b[frames]>=a[frames] for a,b in zip(counts,counts[1:])) else None,
  'decoderDroppedDelta':counts[-1][drops]-counts[0][drops] if len(counts)>=2 else None}
baseline=[r for r in rows if r['epoch']<start-2 and r.get('elapsedMs',0)>=10000][-6:]
concurrent=[r for r in rows if start+7<=r['epoch']<=end-3]
result={'baseline':summarize(baseline),'concurrent':summarize(concurrent),
 'background':json.loads((out/'ocr-summary.json').read_text())}
(out/'comparison.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
