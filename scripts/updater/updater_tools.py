#!/usr/bin/env python3
"""Offline packaging and isolated packaged-launcher tests. Python 3.10+; no third party modules."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
NAME = 'FM AI Assistent'


def metadata(image):
    text = (image / 'app-update.properties').read_text(encoding='utf-8')
    return dict(line.split('=', 1) for line in text.splitlines() if '=' in line and not line.startswith('#'))


def validate(image, version, platform, game):
    m = metadata(image)
    expected = dict(applicationId='fm-ai-assistent', distribution='desktop', version=version,
                    os=platform.split('-')[0], arch='x64', game=game)
    if any(m.get(k) != v for k, v in expected.items()):
        raise ValueError(f'Packaged identity differs from requested release: {m}')
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('A stable packaged version is required')
    launcher = image / ('FM AI Assistent.exe' if platform == 'windows-x64' else 'bin/FM AI Assistent')
    runtime = image / ('runtime' if platform == 'windows-x64' else 'lib/runtime')
    if not launcher.is_file() or not runtime.is_dir() or not (image / 'updater/helper.jar').is_file():
        raise ValueError('Incomplete app-image')
    cfg = image / ('app/FM AI Assistent.cfg' if platform == 'windows-x64' else 'lib/app/FM AI Assistent.cfg')
    if f'java-options=-Djpackage.app-version={version}' not in cfg.read_text().splitlines():
        raise ValueError('jpackage embedded version differs from requested version')
    for p in image.rglob('*'):
        if p.is_symlink() or not (p.is_file() or p.is_dir()):
            raise ValueError(f'Links and special files are unsupported: {p}')


def release(args):
    image = Path(args.app_image).resolve(strict=True)
    validate(image, args.version, args.platform, args.game)
    out = Path(args.output_directory).resolve()
    if out == image or out.is_relative_to(image):
        raise ValueError('Output cannot be inside app-image')
    out.mkdir(parents=True, exist_ok=True)
    extension = '.zip' if args.platform == 'windows-x64' else '.tar.gz'
    filename = f'fm-ai-assistent-desktop-{args.platform}{extension}'
    archive = out / filename
    if extension == '.zip':
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            for p in sorted(image.rglob('*')):
                z.write(p, Path(NAME) / p.relative_to(image))
    else:
        # Dereference is disabled; validation above rejects links. Preserve modes.
        with tarfile.open(archive, 'w:gz') as tar:
            tar.add(image, arcname=NAME)
    manifest_file = out / 'update-manifest.json'
    manifest = dict(schemaVersion=1, applicationId='fm-ai-assistent', version=args.version,
                    channel='stable', supportedGames=[args.game], releaseNotes=args.notes, assets=[])
    if manifest_file.exists():
        old = json.loads(manifest_file.read_text())
        if old['version'] != args.version or old['supportedGames'] != [args.game]:
            raise ValueError('Output manifest belongs to a different release')
        manifest = old
    digest = sha256(archive)
    entry = dict(distribution='desktop', os=args.platform.split('-')[0], arch='x64', file=filename,
                 sha256=digest, sizeBytes=archive.stat().st_size)
    manifest['assets'] = [a for a in manifest['assets'] if a['file'] != filename] + [entry]
    manifest_file.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(manifest_file)
    return manifest


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def aggregate(args):
    directory = Path(args.directory)
    manifest = dict(schemaVersion=1, applicationId='fm-ai-assistent', version=args.version,
                    channel='stable', supportedGames=['FM27'], releaseNotes=Path('RELEASE_NOTES.md').read_text()[:16000], assets=[])
    for platform in ('linux-x64', 'windows-x64'):
        filename = f'fm-ai-assistent-desktop-{platform}' + ('.zip' if platform.startswith('windows') else '.tar.gz')
        archive = directory / filename
        # Check the metadata inside the final artifact, without extracting untrusted files.
        if filename.endswith('.zip'):
            with zipfile.ZipFile(archive) as z:
                info = z.getinfo(f'{NAME}/app-update.properties')
                if info.file_size > 65536:
                    raise ValueError('Oversized packaged metadata')
                raw = z.read(info)
        else:
            with tarfile.open(archive) as t:
                members = [m for m in t if m.name.removeprefix('./') == f'{NAME}/app-update.properties']
                if len(members) != 1 or members[0].size > 65536:
                    raise ValueError('Missing or oversized packaged metadata')
                raw = t.extractfile(members[0]).read(65537)
        m = dict(line.split('=', 1) for line in raw.decode().splitlines() if '=' in line and not line.startswith('#'))
        if any(m.get(k) != v for k, v in dict(applicationId='fm-ai-assistent', version=args.version, game='FM27', os=platform.split('-')[0], arch='x64', distribution='desktop').items()):
            raise ValueError('Final artifact metadata mismatch')
        manifest['assets'].append(dict(distribution='desktop', os=m['os'], arch='x64', file=filename,
                                       sha256=sha256(archive), sizeBytes=archive.stat().st_size))
    (directory / 'update-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


def test(args):
    sandbox = Path(args.sandbox).absolute()
    if sandbox.is_symlink() or sandbox.resolve() != sandbox:
        raise ValueError('Sandbox must be canonical and cannot be a link')
    home = Path.home().resolve()
    if (sandbox == home or sandbox in home.parents or sandbox == ROOT or sandbox in ROOT.parents
            or sandbox.is_relative_to(ROOT) or len(sandbox.parts) < 3):
        raise ValueError('Refusing protected sandbox path')
    if sandbox.exists():
        raise ValueError('Use a NEW, nonexistent sandbox; existing installations are never overwritten')
    old = Path(args.old_image).resolve(strict=True)
    new = Path(args.new_image).resolve(strict=True)
    platform = 'windows-x64' if os.name == 'nt' else 'linux-x64'
    old_version, new_version = metadata(old)['version'], metadata(new)['version']
    if tuple(map(int, new_version.split('.'))) <= tuple(map(int, old_version.split('.'))):
        raise ValueError('New image must have a strictly newer packaged version')
    validate(old, old_version, platform, 'FM27'); validate(new, new_version, platform, 'FM27')
    installed = sandbox / 'installed' / NAME
    installed.parent.mkdir(parents=True)
    shutil.copytree(old, installed)
    profile = sandbox / 'profile'; profile.mkdir()
    (profile / '.fmai-updater-test-profile').touch()
    data = profile / '.fm-ai-assistent'; data.mkdir()
    (data / 'preservation-sentinel.txt').write_text('preserve me')
    args.app_image = str(new); args.output_directory = str(sandbox / 'releases')
    args.version = new_version; args.platform = platform; args.game = 'FM27'; args.notes = 'Offline packaged updater test'
    manifest = release(args)
    if args.failure == 'checksum':
        manifest['assets'][0]['sha256'] = '0' * 64
        (sandbox / 'releases/update-manifest.json').write_text(json.dumps(manifest))
    command = [str(installed / ('FM AI Assistent.exe' if os.name == 'nt' else 'bin/FM AI Assistent')),
               '--app.updates.source=local', '--app.updates.developer-mode=true',
               f'--app.updates.local-directory={sandbox / "releases"}', f'--app.updates.test-home={profile}']
    if args.automated:
        command.append('--app.updates.test-install=true')
    print('Launcher argument array:', json.dumps(command), flush=True)
    with (sandbox / 'original-app.log').open('w') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    print(f'Original launcher PID: {process.pid}. Logs: {sandbox}', flush=True)
    if not args.wait:
        print('Click Download & install once; use --wait to validate the resulting transaction.')
        return
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        journals = list((data / 'updater').glob('*/transaction.properties')) if (data / 'updater').exists() else []
        for journal in journals:
            text = journal.read_text()
            if 'state=COMPLETED' in text:
                assert metadata(installed)['version'] == new_version
                assert (data / 'preservation-sentinel.txt').read_text() == 'preserve me'
                assert (journal.parent / 'healthy.properties').is_file()
                assert (journal.parent / 'data-backup/preservation-sentinel.txt').is_file()
                assert list(installed.parent.glob(f'{NAME}.backup-*'))
                if os.name != 'nt':
                    assert os.access(installed / 'bin/FM AI Assistent', os.X_OK)
                print(f'PASS: packaged {old_version} -> {new_version}, health acknowledgement, backup and data preservation. Close the new desktop app after inspection.')
                return
        if args.failure == 'checksum' and 'Update checksum or size mismatch' in (sandbox / 'original-app.log').read_text(errors='replace'):
            assert metadata(installed)['version'] == old_version
            assert process.poll() is None
            print('PASS: corrupt checksum rejected; old packaged application remains running. Close it after inspection.')
            return
        time.sleep(1)
    raise TimeoutError(f'No successful expected result. Inspect {sandbox}; app processes are left for diagnosis.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('create')
    create.add_argument('--version', required=True); create.add_argument('--game', choices=['FM27'], required=True)
    create.add_argument('--platform', choices=['windows-x64', 'linux-x64'], required=True)
    create.add_argument('--app-image', required=True); create.add_argument('--output-directory', required=True)
    create.add_argument('--notes', default='Local development release')
    smoke = commands.add_parser('test')
    smoke.add_argument('--sandbox', required=True); smoke.add_argument('--old-image', required=True); smoke.add_argument('--new-image', required=True)
    smoke.add_argument('--automated', action='store_true', help='Explicit installation consent in isolated developer test profile only')
    smoke.add_argument('--wait', action='store_true'); smoke.add_argument('--timeout', type=int, default=600)
    smoke.add_argument('--failure', choices=['checksum'])
    combined = commands.add_parser('aggregate')
    combined.add_argument('--version', required=True); combined.add_argument('--directory', required=True)
    build_parser = commands.add_parser('build')
    build_parser.add_argument('--output-directory', required=True)
    build_parser.add_argument('--old-version', default='2.1.0'); build_parser.add_argument('--new-version', default='2.1.1')
    args = parser.parse_args()
    {'create': release, 'test': test, 'aggregate': aggregate, 'build': build}[args.command](args)


def build(args):
    output = Path(args.output_directory).absolute()
    if output.exists() or output.resolve().is_relative_to(ROOT):
        raise ValueError('Build output must be a new directory outside the checkout')
    for version in (args.old_version, args.new_version):
        if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
            raise ValueError('Stable build versions required')
    output.mkdir(parents=True)
    for label, version in [('old', args.old_version), ('new', args.new_version)]:
        checkout = output / label
        shutil.copytree(ROOT, checkout, ignore=shutil.ignore_patterns('.git', 'target', 'node_modules', '.idea',
                        '.agents', '.codex', '*.log', '__pycache__'))
        wrapper = str(checkout / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
        commands = [[wrapper, '--batch-mode', '--no-transfer-progress', 'org.codehaus.mojo:versions-maven-plugin:2.20.1:set',
                     f'-DnewVersion={version}', '-DgenerateBackupPoms=false'],
                    [wrapper, '--batch-mode', '--no-transfer-progress', '-DskipTests', 'clean', 'verify', '-Pdesktop']]
        with (output / f'{label}-build.log').open('w') as log:
            for command in commands:
                print('Building', label, version, 'log:', log.name, flush=True)
                subprocess.run(command, cwd=checkout, stdout=log, stderr=subprocess.STDOUT, check=True)
        print('Packaged image:', checkout / 'target/dist' / NAME, flush=True)


if __name__ == '__main__':
    main()
