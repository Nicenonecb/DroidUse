#!/usr/bin/env python3
"""Small fixture tests; no downloads, real builds, or device operations."""
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = pathlib.Path(__file__).resolve().parent


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = pathlib.Path(self.tmp.name)
        self.run = self.base / 'run'
        self.run.mkdir()
        self.product = self.base / 'product'
        self.product.mkdir()
        self.dest = self.base / 'delivery'
        for name, value in {'exit-code.txt': '0', 'manifest.xml': '<manifest/>',
                            'target.txt': 'lineage_oriole-test-userdebug',
                            'product-out.txt': str(self.product), 'started.txt': 'test',
                            'source-status.txt': ''}.items():
            (self.run / name).write_text(value)
        os.utime(self.run / 'started.txt', (1000, 1000))
        self.zip = self.product / 'lineage-test-oriole.zip'
        self.zip.write_bytes(b'fixture ROM')

    def export(self):
        return subprocess.run([sys.executable, str(TOOLS / 'export-rom.py'),
                               str(self.run), str(self.dest)], capture_output=True)

    def test_success_and_checksum(self):
        self.assertEqual(self.export().returncode, 0)
        self.assertTrue((self.dest / 'COMPLETE').exists())
        expected = hashlib.sha256(b'fixture ROM').hexdigest()
        self.assertIn(expected, (self.dest / 'SHA256SUMS').read_text())

    def test_failed_build_rejected(self):
        (self.run / 'exit-code.txt').write_text('42')
        self.assertNotEqual(self.export().returncode, 0)
        self.assertFalse(self.dest.exists())

    def test_stale_zip_rejected(self):
        os.utime(self.zip, (900, 900))
        self.assertNotEqual(self.export().returncode, 0)
        self.assertFalse(self.dest.exists())

    def test_existing_destination_preserved(self):
        self.dest.mkdir()
        (self.dest / 'keep').write_text('untouched')
        self.assertNotEqual(self.export().returncode, 0)
        self.assertEqual((self.dest / 'keep').read_text(), 'untouched')

    def test_symlink_rejected(self):
        (self.product / 'boot.img').symlink_to(self.zip)
        self.assertNotEqual(self.export().returncode, 0)
        self.assertFalse(self.dest.exists())


class PreflightTests(unittest.TestCase):
    def test_incomplete_tree_rejected(self):
        spec = importlib.util.spec_from_file_location('romcheck', TOOLS / 'check-rom-tree.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(sys, 'argv', ['check', directory]), patch.object(pathlib.Path, 'glob', return_value=[]):
                self.assertEqual(module.main(), 3)


class BackendStagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = pathlib.Path(self.tmp.name)
        self.dest = self.base / 'backend'
        self.certificate = 'ab' * 32

    def stage(self, destination=None, certificate=None):
        return subprocess.run([
            sys.executable, str(TOOLS / 'stage-rom-backend.py'),
            '--certificate-sha256', certificate or self.certificate,
            '--certificate-purpose', 'engineering',
            '--output', str(destination or self.dest),
        ], capture_output=True, text=True)

    def test_source_payload_is_complete_and_integrity_protected(self):
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.dest / 'COMPLETE').exists())
        self.assertEqual(
            (self.dest / 'config/executor-cert.sha256').read_text().strip(),
            self.certificate,
        )
        verification = json.loads((self.dest / 'verification.json').read_text())
        self.assertFalse(verification['backendReady'])
        self.assertEqual(verification['certificatePurpose'], 'engineering')
        self.assertFalse(verification['activeSePolicyMarkerStaged'])
        self.assertIn('Never ship it in a final candidate',
                      (self.dest / 'config/README.md').read_text())
        self.assertFalse((self.dest / 'config/sepolicy-version').exists())
        self.assertTrue((self.dest / 'contract/src/main/aidl/dev/droiduse/system/IDroidUseSystem.aidl').exists())
        self.assertTrue((self.dest / 'runtime/src/dev/droiduse/executor/SystemSession.java').exists())
        self.assertTrue((self.dest / 'service/src/com/android/server/droiduse/DroidUseManagerService.java').exists())
        self.assertTrue((self.dest / 'contract/src/main/aidl/dev/droiduse/system/ActionTarget.aidl').exists())
        self.assertTrue((self.dest / 'framework/src/com/android/server/wm/DroidUseWindowSnapshot.java').exists())
        self.assertTrue((self.dest / 'framework/0010-m3-window-and-picker-routing.patch').exists())
        self.assertTrue((self.dest / 'framework/0011-m3-isolated-editor-clipboard.patch').exists())
        self.assertTrue((self.dest / 'framework/core/java/android/view/inputmethod/DroidUseEditorActions.java').exists())
        self.assertTrue((self.dest / 'service/src/com/android/server/droiduse/DroidUseClipboard.java').exists())
        for line in (self.dest / 'SHA256SUMS').read_text().splitlines():
            expected, relative = line.split('  ', 1)
            self.assertEqual(hashlib.sha256((self.dest / relative).read_bytes()).hexdigest(), expected)

    def test_invalid_certificate_is_rejected(self):
        result = self.stage(certificate='not-a-digest')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.dest.exists())

    def test_existing_destination_is_preserved(self):
        self.dest.mkdir()
        (self.dest / 'keep').write_text('untouched')
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.dest / 'keep').read_text(), 'untouched')

    def test_active_rom_tree_is_rejected(self):
        rom = self.base / 'android'
        (rom / '.repo').mkdir(parents=True)
        result = self.stage(destination=rom / 'vendor/droiduse')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((rom / 'vendor/droiduse').exists())


@unittest.skipUnless(sys.platform == 'linux' and os.geteuid() != 0,
                     'build harness runs on Linux as a non-root user')
class BuildTests(unittest.TestCase):
    def test_pipeline_failure_is_recorded(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            shutil.copy2(TOOLS / 'build-baseline.sh', base / 'build-baseline.sh')
            (base / 'check-build-tree.sh').write_text('#!/bin/bash\nexit 0\n')
            root = base / 'source'
            (root / '.repo/repo').mkdir(parents=True)
            (root / 'build').mkdir()
            (root / '.repo/repo/repo').write_text(
                'import sys,pathlib\n'
                'if sys.argv[1] == "manifest": pathlib.Path(sys.argv[-1]).write_text("<manifest/>")\n')
            (root / 'build/envsetup.sh').write_text(
                'lunch() { export TARGET_PRODUCT=lineage_oriole; }\n'
                'm() { echo fixture-build-failure; return 42; }\n')
            env = dict(os.environ, ROM_RUNS_DIR=str(base / 'runs'))
            result = subprocess.run(['bash', str(base / 'build-baseline.sh'), str(root),
                                     'lineage_oriole-test-userdebug', '--build'],
                                    env=env, capture_output=True)
            self.assertEqual(result.returncode, 42, result.stderr)
            run = next((base / 'runs').iterdir())
            self.assertEqual((run / 'exit-code.txt').read_text().strip(), '42')
            self.assertIn('fixture-build-failure', (run / 'build.log').read_text())
            self.assertFalse((run / 'product-out.txt').exists())


if __name__ == '__main__':
    unittest.main()
