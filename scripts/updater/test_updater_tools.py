"""Build-tool tests, not platform packaged E2E claims. Run: python -m unittest discover -s scripts/updater."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import updater_tools as tools


class UpdaterToolsTest(unittest.TestCase):
    def image(self, directory, platform, version='2.1.1'):
        directory.mkdir()
        (directory / 'app-update.properties').write_text(
            f'applicationId=fm-ai-assistent\ndistribution=desktop\nversion={version}\n'
            f'os={platform.split("-")[0]}\narch=x64\ngame=FM27\n')
        windows = platform.startswith('windows')
        launcher = directory / ('FM AI Assistent.exe' if windows else 'bin/FM AI Assistent')
        launcher.parent.mkdir(exist_ok=True); launcher.write_text('fixture')
        (directory / ('runtime' if windows else 'lib/runtime')).mkdir(parents=True)
        (directory / 'updater').mkdir(); (directory / 'updater/helper.jar').write_text('fixture')
        cfg = directory / ('app/FM AI Assistent.cfg' if windows else 'lib/app/FM AI Assistent.cfg')
        cfg.parent.mkdir(parents=True, exist_ok=True)
        cfg.write_text(f'java-options=-Djpackage.app-version={version}\n')
        return directory

    def test_merge_platforms_and_final_checksums(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(strict=True)
            for platform in ('windows-x64', 'linux-x64'):
                image = self.image(root / platform, platform)
                args = argparse.Namespace(app_image=str(image), output_directory=str(root / 'releases'),
                                          version='2.1.1', platform=platform, game='FM27', notes='notes')
                tools.release(args)
            manifest = json.loads((root / 'releases/update-manifest.json').read_text())
            self.assertEqual(2, len(manifest['assets']))
            for asset in manifest['assets']:
                file = root / 'releases' / asset['file']
                self.assertEqual(file.stat().st_size, asset['sizeBytes'])
                self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(), asset['sha256'])
            tools.aggregate(argparse.Namespace(directory=str(root / 'releases'), version='2.1.1'))
            self.assertEqual(2, len(json.loads((root / 'releases/update-manifest.json').read_text())['assets']))

    def test_wait_reaps_launcher_and_reports_helper_error(self):
        with tempfile.TemporaryDirectory() as temp:
            # Windows temporary paths can contain short names or junctions.
            root = Path(temp).resolve(strict=True)
            platform = 'windows-x64' if tools.os.name == 'nt' else 'linux-x64'
            old = self.image(root / 'old', platform, '2.1.0')
            new = self.image(root / 'new', platform)
            sandbox = root / 'sandbox'
            process = Mock(pid=123)

            def start(*args, **kwargs):
                tx = sandbox / 'profile/.fm-ai-assistent/updater/test-transaction'
                tx.mkdir(parents=True)
                (tx / 'transaction.properties').write_text('state=DATA_REVIEW_REQUIRED')
                (tx / 'error.txt').write_text('New application did not acknowledge readiness')
                return process

            args = argparse.Namespace(old_image=str(old), new_image=str(new), sandbox=str(sandbox),
                                      automated=False, wait=True, timeout=2, failure=None)
            with patch.object(tools.subprocess, 'Popen', side_effect=start):
                with self.assertRaisesRegex(RuntimeError, 'New application did not acknowledge readiness'):
                    tools.test(args)
            process.poll.assert_called_once()

    def test_refuses_version_mismatch_and_unsafe_sandbox(self):
        with tempfile.TemporaryDirectory() as temp:
            image = self.image(Path(temp) / 'image', 'windows-x64')
            with self.assertRaises(ValueError):
                tools.validate(image, '2.1.2', 'windows-x64', 'FM27')
            for directory in (Path.home(), tools.ROOT, tools.ROOT / 'sandbox', Path(temp)):
                with self.assertRaises(ValueError):
                    tools.test(argparse.Namespace(sandbox=str(directory)))


if __name__ == '__main__':
    unittest.main()
