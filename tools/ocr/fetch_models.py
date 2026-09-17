#!/usr/bin/env python3
"""Fetch pinned official PP-OCRv6 archives; weights remain outside source control."""
import hashlib,json,subprocess,tarfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
models=root/'build/paddle-source/models';models.mkdir(parents=True,exist_ok=True)
for name,meta in json.loads(Path(__file__).with_name('models.json').read_text()).items():
 archive=models/(name+'.tar')
 if not archive.exists():subprocess.run(['curl','--fail','--location','--retry','2','--max-time','300',meta['url'],'-o',str(archive)],check=True)
 if hashlib.sha256(archive.read_bytes()).hexdigest()!=meta['sha256']:raise RuntimeError('Model hash mismatch: '+name)
 with tarfile.open(archive) as tar:tar.extractall(models,filter='data')
 print(name,'verified')
