#!/usr/bin/env python3
"""A small authorized vision/latency probe; never logs credentials."""
import base64,concurrent.futures,json,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
config=json.loads((ROOT/'secrets/model.json').read_text())
image=base64.b64encode((ROOT/'build/validation/home.png').read_bytes()).decode()
def probe(model):
 start=time.monotonic()
 payload={'model':model,'messages':[{'role':'user','content':[{'type':'image_url','image_url':{'url':'data:image/png;base64,'+image}},{'type':'text','text':'只输出JSON：{"app":"截图标题","buttons":["截图内可见按钮"]}。'}]}],'max_tokens':180,'enable_thinking':False}
 try:
  request=urllib.request.Request(config['baseUrl']+'/chat/completions',data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+config['apiKey'],'Content-Type':'application/json'})
  with urllib.request.urlopen(request,timeout=45) as response:data=json.load(response)
  return {'model':model,'seconds':round(time.monotonic()-start,3),'content':data['choices'][0]['message']['content'],'usage':data.get('usage')}
 except urllib.error.HTTPError as e:
  return {'model':model,'seconds':round(time.monotonic()-start,3),'http_status':e.code,'error':e.read(2000).decode().replace(config['apiKey'],'[REDACTED]')}
 except Exception as e:return {'model':model,'seconds':round(time.monotonic()-start,3),'error':type(e).__name__}
if __name__=='__main__':
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(probe,['qwen3.8-max-0902','qwen3.8-max','kimi-k3']))
 (ROOT/'build/validation/model-selection.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
 print(json.dumps(results,ensure_ascii=False,indent=2))
