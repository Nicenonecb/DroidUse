#!/usr/bin/env python3
"""Local-only experimental ADB backend; not production ROM isolation."""
import base64,hashlib,json,queue,re,secrets,shlex,subprocess,threading,time,uuid
from pathlib import Path
from reading import ReadingProgress
ROOT=Path(__file__).resolve().parents[2]
ADB=Path.home()/'Library/Android/sdk/platform-tools/adb'
BUILD=ROOT/'platform/executor/prototype-virtual-display/build'
PACKAGE='com.dragon.read'
class Backend:
 def __init__(self,serial):
  self.adb=[str(ADB),'-s',serial];self.host=None;self.display=None;self.mac=None;self.stopped=threading.Event();self.lock=threading.RLock();self.latest=None;self.seen=set();self.pages=[];self.context='';self.navigation=[];self.reading_started=False;self.paused=threading.Event();self.metrics=[];self.start=time.monotonic();self.out=ROOT/'build/benchmark'/time.strftime('%Y%m%d-%H%M%S');self.out.mkdir(parents=True,exist_ok=True)
 def timed(self,kind,fn):
  start=time.monotonic()
  try:return fn()
  finally:self.metrics.append({'kind':kind,'seconds':round(time.monotonic()-start,3),'elapsed':round(time.monotonic()-self.start,3)})
 def shell(self,*args):return subprocess.check_output(self.adb+['shell',shlex.join(map(str,args))],text=True,timeout=20).strip()
 def wait(self,prefix,timeout=15):
  deadline=time.monotonic()+timeout
  while True:
   line=self.q.get(timeout=max(.01,deadline-time.monotonic()))
   if line.startswith(prefix):return line
   if 'INJECTION_ERROR' in line or 'FRAME_ERROR' in line:raise RuntimeError('虚拟设备保护或截图失败')
   if line=='EXIT':raise RuntimeError('display host exited')
 def check_foreground(self):
  if 'mIsShowing=true' in self.shell('dumpsys','window','policy'):raise RuntimeError('手机已锁屏，请解锁后重试')
  dump=self.shell('dumpsys','activity','activities');display=None
  for line in dump.splitlines():
   match=re.match(r'Display #(\d+) ',line)
   if match:display=int(match.group(1))
   if display==0 and 'topResumedActivity=' in line:
    if PACKAGE+'/' in line:raise RuntimeError('用户正在主屏使用番茄小说，后台停止')
    self.foreground=line.strip()
 def begin(self):
  self.check_foreground()
  self.start=time.monotonic();self.mac='02:00:00:'+':'.join(f'{b:02x}' for b in secrets.token_bytes(3))
  self.shell('cmd','companiondevice','associate',0,'com.android.shell',self.mac,'android.app.role.COMPANION_DEVICE_APP_STREAMING','true')
  listing=self.shell('cmd','companiondevice','list',0);association=None
  for line in listing.splitlines():
   if self.mac.lower() in line.lower():association=int(re.search(r'\d+',line).group())
  if association is None:raise RuntimeError('Association ID not found')
  subprocess.run(self.adb+['push',str(BUILD/'host.jar'),'/data/local/tmp/droiduse-probe.jar'],check=True,capture_output=True)
  self.host=subprocess.Popen(self.adb+['shell','CLASSPATH=/data/local/tmp/droiduse-probe.jar','app_process','/','dev.droiduse.probe.DisplayHost',str(association)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1);self.q=queue.Queue()
  def consume():
   for line in self.host.stdout:self.q.put(line.strip())
   self.q.put('EXIT')
  threading.Thread(target=consume,daemon=True).start()
  self.display=int(self.wait('DISPLAY_ID=').split('=')[1]);assert self.display>0
  self.host.stdin.write('deny-reader-mic\n');self.host.stdin.flush();self.wait('DEVICE_MIC_REVOKE_RETURNED=')
  component=self.shell('cmd','package','resolve-activity','--brief',PACKAGE).splitlines()[-1];assert component.startswith(PACKAGE+'/')
  self.shell('am','start','--display',self.display,'-n',component);time.sleep(.7)
  return {'ready':True,'backend':'ADB_EXPERIMENT','sessionId':str(uuid.uuid4())}
 def observe(self):
  with self.lock:
   if self.stopped.is_set():raise RuntimeError('cancelled')
   self.check_foreground()
   self.host.stdin.write('capture\n');self.host.stdin.flush();self.wait('CAPTURED')
   path=self.out/f'{len(self.metrics):04d}.png'
   data=self.timed('capture',lambda:subprocess.check_output(self.adb+['exec-out','cat','/data/local/tmp/droiduse-probe.png'],timeout=10));path.write_bytes(data)
   ocr=self.timed('ocr',lambda:json.loads(subprocess.check_output([str(ROOT/'build/validation/droiduse-ocr'),str(path)],text=True,timeout=10)))
   rows=ocr['rows'];targets=ocr['targets']
   text='\n'.join(row['text'] for row in rows)
   if not self.reading_started and text.strip():
    self.navigation.append({'text':text[:6000],'evidence':str(path)});self.navigation=self.navigation[-20:]
   frame={'id':str(uuid.uuid4()),'display':self.display,'width':720,'height':1280,'rotation':0,'app':PACKAGE,'pngBase64':base64.b64encode(data).decode(),'text':text,'context':json.dumps({'reading':json.loads(self.context) if self.context else {},'navigation':self.navigation},ensure_ascii=False),'rows':rows,'targets':targets,'sha256':hashlib.sha256(data).hexdigest(),'path':str(path),'hostTime':time.monotonic()};self.latest=frame
   path.with_suffix('.json').write_text(json.dumps({'text':text,'rows':rows,'targets':targets},ensure_ascii=False));return frame
 def set_paused(self,value):
  if value:self.paused.set()
  else:self.paused.clear()
 def await_active(self):
  while self.paused.is_set():
   if self.stopped.wait(.1) or time.monotonic()-self.start>235:raise RuntimeError('暂停期间停止或超时')
  if self.stopped.is_set():raise RuntimeError('cancelled')
 def action(self,request_id,frame_id,action):
  with self.lock:
   if self.stopped.is_set():return 'ISOLATION_LOST'
   if self.display is None or self.display<=0 or self.host.poll() is not None:return 'ISOLATION_LOST'
   if request_id in self.seen:return 'UNKNOWN_OUTCOME'
   if not self.latest or frame_id!=self.latest['id'] or time.monotonic()-self.latest['hostTime']>30:return 'STALE_OBSERVATION'
   self.await_active()
   self.check_foreground()
   if frame_id!=self.latest['id'] or time.monotonic()-self.latest['hostTime']>30:return 'STALE_OBSERVATION'
   self.seen.add(request_id);kind=action['kind']
   if kind=='tap' and action.get('target'):
    matches=[r for r in self.latest['targets']+self.latest['rows'] if r['text']==action['target']]
    if not matches:return 'UNSUPPORTED'
    row=min(matches,key=lambda r:(r['x']-action['x'])**2+(r['y']-action['y'])**2)
    action=dict(action,x=row['x'],y=row['y'])
   def point(x,y):assert type(x)==int and type(y)==int and 0<=x<720 and 0<=y<1280
   if kind=='tap':point(action['x'],action['y']);self.timed('input',lambda:self.shell('input','-d',self.display,'tap',action['x'],action['y']))
   elif kind=='swipe':
    point(action['x1'],action['y1']);point(action['x2'],action['y2']);assert 100<=action['durationMs']<=1500
    self.timed('input',lambda:self.shell('input','-d',self.display,'swipe',action['x1'],action['y1'],action['x2'],action['y2'],action['durationMs']))
   elif kind=='back':self.timed('input',lambda:self.shell('input','-d',self.display,'keyevent','KEYCODE_BACK'))
   elif kind=='wait':
    assert type(action['durationMs'])==int and 200<=action['durationMs']<=2000
    self.stopped.wait(action['durationMs']/1000)
   elif kind=='read_chapters':self.read_chapters()
   else:return 'UNSUPPORTED'
   time.sleep(.5);return 'EXECUTED'
 def read_chapters(self):
  if not re.search(r'第\s*[一1]\s*章',self.latest['text']):raise RuntimeError('必须先打开第一章开头')
  self.reading_started=True
  progress=ReadingProgress();last=None;records=[]
  for index in range(140):
   self.await_active()
   if self.stopped.is_set():raise RuntimeError('cancelled')
   if time.monotonic()-self.start>215:break
   f=self.latest if index==0 else self.observe();text=f['text']
   if progress.accept(f['rows']):
    self.context=json.dumps({'complete':True,'chapters':records,'boundary':{'chapter':4,'page':progress.page,'evidence':f['path']},'verification':'页码从1连续到第四章起始页，已记录前三章'},ensure_ascii=False);self.pages=records;return
   if any(s in text for s in ['解锁本章','购买本章','验证码']):raise RuntimeError('需要人工处理阅读限制')
   if f['sha256']==last:raise RuntimeError('翻页未改变画面')
   last=f['sha256'];records.append({'chapter':progress.chapter,'page':index+1,'text':text,'evidence':f['path']});self.pages=records;self.context=json.dumps({'complete':False,'chapters':records},ensure_ascii=False)
   self.shell('input','-d',self.display,'swipe',620,650,100,650,160);time.sleep(.22)
  self.context=json.dumps({'complete':False,'chapters':records,'reason':'阅读上限或时间预算'},ensure_ascii=False);raise RuntimeError('未证实前三章完整读取')
 def cancel(self):
  self.stopped.set()
  with self.lock:
   if self.host and self.host.poll() is None:
    try:self.host.stdin.write('quit\n');self.host.stdin.flush();self.host.wait(timeout=5)
    except Exception:self.host.terminate()
   if self.mac:
    try:self.shell('cmd','companiondevice','disassociate',0,'com.android.shell',self.mac)
    finally:self.mac=None
   (self.out/'report.json').write_text(json.dumps({'elapsed_seconds':round(time.monotonic()-self.start,3),'metrics':self.metrics,'pages':self.pages,'context':self.context,'backend':'ADB_EXPERIMENT_NOT_ROM'},ensure_ascii=False,indent=2))
