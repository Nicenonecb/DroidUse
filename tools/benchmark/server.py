#!/usr/bin/env python3
"""Authenticated loopback bridge, exposed to this test phone only via adb reverse."""
import argparse,hmac,json,threading,time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from adb_backend import Backend,ROOT
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);args=p.parse_args()
token=(ROOT/'secrets/bridge-token').read_text().strip();lock=threading.RLock();backend=None;session=None
shutdown=threading.Event()
def expire_sessions():
 global backend,session
 while not shutdown.wait(.5):
  with lock:expired=backend if backend is not None and time.monotonic()-backend.start>=240 else None
  if expired is None:continue
  try:expired.cancel()
  except Exception:pass
  finally:
   with lock:
    if backend is expired:backend=None;session=None
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  global backend,session
  if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):self.send_error(403);return
  try:
   length=int(self.headers.get('Content-Length','0'))
   if not 0<=length<=65536:raise ValueError('body too large')
   body=json.loads(self.rfile.read(length) or b'{}')
   if self.path=='/begin':
    with lock:
     if backend is not None:raise RuntimeError('BUSY')
     candidate=Backend(args.serial)
     try:reply=candidate.begin()
     except Exception:candidate.cancel();raise
     backend=candidate;session=reply['sessionId']
   else:
    with lock:
     if backend is None or body.get('sessionId')!=session:raise RuntimeError('invalid session')
     current=backend
    if self.path=='/observe':reply=current.observe()
    elif self.path=='/action':reply={'code':current.action(body['requestId'],body['frameId'],body['action'])}
    elif self.path in ('/pause','/resume'):current.set_paused(self.path=='/pause');reply={'code':'PAUSED' if self.path=='/pause' else 'RESUMED'}
    elif self.path=='/cancel':
     current.cancel();reply={'code':'CANCELLED','elapsed':time.monotonic()-current.start,'report':str(current.out/'report.json')}
     with lock:backend=None;session=None
    else:raise ValueError('unknown endpoint')
   raw=json.dumps(reply,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
  except Exception as e:
   raw=json.dumps({'error':str(e)[:200]},ensure_ascii=False).encode();self.send_response(409);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
threading.Thread(target=expire_sessions,daemon=True).start()
try:ThreadingHTTPServer(('127.0.0.1',8765),Handler).serve_forever()
finally:
 shutdown.set()
 if backend:backend.cancel()
