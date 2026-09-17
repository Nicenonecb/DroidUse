#!/usr/bin/env python3
"""Exercise debug PhoneBridge gestures against our synthetic controls, never user content."""
import argparse,json,re,subprocess,time,urllib.request,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);args=p.parse_args()
adb=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',args.serial]
def shell(*words):return subprocess.check_output(adb+['shell',*words],text=True)
port=subprocess.check_output(adb+['forward','tcp:0','tcp:18765'],text=True).strip()
token=(root/'secrets/bridge-token').read_text().strip()
def call(route,body):
 req=urllib.request.Request('http://127.0.0.1:'+port+route,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=30) as f:return json.load(f)
started=time.time();session=None
try:
 begin=call('/begin',{'budgetMs':60000});session=begin['sessionId']
 assert {'double_tap','long_press','drag','multi_touch'} <= set(begin['supportedActions'])
 def request(route,**kw):return call(route,dict(sessionId=session,**kw))
 frame=request('/observe');display=frame['display'];assert display>0
 shell('am','start','--display',str(display),'-n','dev.droiduse.probe/.LabActivity')
 time.sleep(1)
 shell('am','broadcast','-a','dev.droiduse.lab.COMMAND','-p','dev.droiduse.probe','--es','op','controls')
 def logs():
  result=[]
  for line in shell('logcat','-d','-v','epoch','-s','DroidUseLab:I','*:S').splitlines():
   fields=line.strip().split()
   if fields and fields[0].replace('.','',1).isdigit() and float(fields[0])>=started and f'display={display} ' in line:result.append(line)
  return '\n'.join(result)
 bounds={}
 for line in logs().splitlines():
  m=re.search(r'BOUNDS (\w+) (\d+) (\d+) (\d+) (\d+)',line)
  if m:bounds[m[1]]=list(map(int,m.groups()[1:]))
 x,y,w,h=bounds['button'];sx,sy,sw,sh=bounds['slider']
 actions=[{'kind':'double_tap','x':x+w//2,'y':y+h//2},
          {'kind':'long_press','x':x+w//2,'y':y+h//2,'durationMs':800},
          {'kind':'drag','x1':sx+25,'y1':sy+sh//2,'x2':sx+sw-25,'y2':sy+sh//2,'holdMs':100,'durationMs':700}]
 def path(points):return [{'x':x,'y':y} for x,y in points]
 actions += [
  {'kind':'multi_touch','durationMs':500,'fingers':[path([(260,650),(180,650)]),path([(460,650),(540,650)])]},
  {'kind':'multi_touch','durationMs':500,'fingers':[path([(260,650),(289,579),(360,550)]),path([(460,650),(431,721),(360,750)])]},
  {'kind':'multi_touch','durationMs':500,'fingers':[path([(260,650),(300,700)]),path([(460,650),(500,700)])]}
 ]
 replies=[]
 for action in actions:
  request('/heartbeat');frame=request('/observe')
  replies.append(request('/action',requestId=str(uuid.uuid4()),frameId=frame['id'],action=action)['code'])
  time.sleep(.4)
 evidence=logs();assert replies==['EXECUTED']*6,replies
 assert 'DOUBLE_TAP' in evidence and 'LONG_PRESS' in evidence,evidence
 values=[int(x) for x in re.findall(r'SLIDER=(\d+)',evidence)];assert values and max(values)>=90,evidence
 multi=[tuple(map(float,m)) for m in re.findall(r'MULTI scale=([\d.]+) angle=([\d.\-]+) dx=([\d.\-]+) dy=([\d.\-]+)',evidence)]
 assert len(multi)==3,multi
 assert abs(multi[0][0]-1.8)<.02 and abs(multi[1][1]-90)<1 and abs(multi[2][2]-40)<1 and abs(multi[2][3]-50)<1,multi
 out=root/'build/runtime-service-evidence' ;out.mkdir(parents=True,exist_ok=True)
 (out/'gesture-smoke.json').write_text(json.dumps({'display':display,'replies':replies,'doubleTap':True,'longPress':True,'sliderMax':max(values),'multi':multi},indent=2))
 (out/'gesture-smoke.log').write_text(evidence)
 print('PASS: double tap, long press, drag, pinch, rotation, two-finger pan on virtual display',display)
finally:
 try:
  if session:call('/cancel',{'sessionId':session})
 finally:subprocess.run(adb+['forward','--remove','tcp:'+port],check=True)
