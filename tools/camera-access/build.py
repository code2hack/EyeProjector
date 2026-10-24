#!/usr/bin/env python3
"""Build only the isolated app_process dex JAR; no Gradle, download or device access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def verify_pin():
    pin = json.loads((ROOT / 'aosp-pin.json').read_text())
    for relative, expected in pin['files'].items():
        actual = hashlib.sha256((ROOT / 'aidl' / relative).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f'AOSP source differs: {relative}: {actual}')
    return pin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--build-tools', default='34.0.0')
    parser.add_argument('--platform', default='35')
    parser.add_argument('--verify-pin', action='store_true')
    args = parser.parse_args()
    pin = verify_pin()
    if args.verify_pin:
        print(json.dumps(pin, indent=2))
        return
    if not args.sdk:
        parser.error('supply --sdk or ANDROID_HOME')
    sdk = args.sdk.resolve()
    tools = sdk / 'build-tools' / args.build_tools
    platform = sdk / 'platforms' / ('android-' + args.platform)
    output = ROOT / 'build'
    if output.exists():
        shutil.rmtree(output)
    for name in ('generated', 'classes', 'dex'):
        (output / name).mkdir(parents=True)
    for source in sorted((ROOT / 'aidl').rglob('*.aidl')):
        subprocess.run([str(tools / 'aidl'), '-I' + str(ROOT / 'aidl'),
                        '-p' + str(platform / 'framework.aidl'),
                        '-o' + str(output / 'generated'), str(source)], check=True)
    sources = sorted((output / 'generated').rglob('*.java')) + sorted((ROOT / 'src').rglob('*.java'))
    subprocess.run(['javac', '-source', '17', '-target', '17', '-classpath',
                    str(platform / 'android.jar'), '-d', str(output / 'classes'),
                    *map(str, sources)], check=True)
    classes_jar = output / 'classes.jar'
    with zipfile.ZipFile(classes_jar, 'w') as jar:
        for source in sorted((output / 'classes').rglob('*.class')):
            jar.write(source, str(source.relative_to(output / 'classes')))
    subprocess.run([str(tools / 'd8'), '--min-api', '32', '--lib', str(platform / 'android.jar'),
                    '--output', str(output / 'dex'), str(classes_jar)], check=True)
    artifact = output / 'camera-access.jar'
    with zipfile.ZipFile(artifact, 'w') as jar:
        for source in sorted((output / 'dex').glob('*.dex')):
            jar.write(source, source.name)
    print(json.dumps({'artifact': str(artifact), 'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
                      'aospCommit': pin['commit']}))


if __name__ == '__main__':
    main()
