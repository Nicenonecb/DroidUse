#!/usr/bin/env python3
"""Generate a synthetic silent 1080p30 fixture; run probe.py build afterwards."""
import shutil,subprocess
from pathlib import Path
ffmpeg=shutil.which('ffmpeg')
if not ffmpeg:raise SystemExit('ffmpeg is required')
root=Path(__file__).resolve().parents[2]
out=root/'platform/executor/prototype-virtual-display/build/video-assets/video-test.mp4'
out.parent.mkdir(parents=True,exist_ok=True)
subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=1920x1080:rate=30',
 '-t','30','-c:v','libx264','-preset','veryfast','-crf','25','-pix_fmt','yuv420p','-movflags','+faststart','-an','-y',str(out)],check=True)
print(out)
