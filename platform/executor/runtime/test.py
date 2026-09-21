#!/usr/bin/env python3
"""Compile real Kotlin sources and run deterministic protocol scenarios on the host JVM."""
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parent
compiler = os.environ.get('KOTLINC') or shutil.which('kotlinc') or '/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc'
if not Path(compiler).is_file():
    raise SystemExit('Set KOTLINC to a Kotlin compiler (tested with Kotlin 2.3.10 / JDK 17)')
build = root / 'build'
build.mkdir(exist_ok=True)
java_sources = sorted((root / 'src').rglob('*.java'))
classes = build / 'classes'
classes.mkdir(exist_ok=True)
if java_sources:
    subprocess.run(['javac', '-source', '17', '-target', '17', '-d', str(classes),
                    *map(str, java_sources)], check=True)
kotlin_sources = sorted((root / 'src').rglob('*.kt')) + sorted((root / 'test').rglob('*.kt'))
subprocess.run([compiler, *map(str, kotlin_sources), '-classpath', str(classes),
                '-jvm-target', '17', '-include-runtime', '-d', str(build / 'tests.jar')], check=True)
if java_sources:
    subprocess.run(['jar', 'uf', str(build / 'tests.jar'), '-C', str(classes), '.'], check=True)
subprocess.run(['java', '-jar', str(build / 'tests.jar')], check=True)
