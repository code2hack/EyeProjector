#!/usr/bin/env python3
"""Build the isolated unsigned instrumentation APK; run only in the Manager's SDK slot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--build-tools', default='34.0.0')
    parser.add_argument('--platform', default='35')
    args = parser.parse_args()
    if not args.sdk:
        parser.error('supply --sdk or ANDROID_HOME')
    sdk = args.sdk.resolve()
    tools = sdk / 'build-tools' / args.build_tools
    android = sdk / 'platforms' / ('android-' + args.platform) / 'android.jar'
    output = ROOT / 'build'
    if output.exists():
        shutil.rmtree(output)
    for name in ('classes', 'dex'):
        (output / name).mkdir(parents=True)
    subprocess.run(['javac', '--release', '17', '-classpath', str(android), '-d',
                    str(output / 'classes'), *map(str, sorted((ROOT / 'src').rglob('*.java')))], check=True)
    classes = output / 'classes.jar'
    with zipfile.ZipFile(classes, 'w') as jar:
        for path in sorted((output / 'classes').rglob('*.class')):
            jar.write(path, str(path.relative_to(output / 'classes')))
    subprocess.run([str(tools / 'd8'), '--min-api', '32', '--lib', str(android),
                    '--output', str(output / 'dex'), str(classes)], check=True)
    unaligned = output / 'unaligned.apk'
    subprocess.run([str(tools / 'aapt2'), 'link', '-o', str(unaligned), '-I', str(android),
                    '--manifest', str(ROOT / 'AndroidManifest.xml'), '--version-code', '1',
                    '--version-name', 'issue-111'], check=True)
    with zipfile.ZipFile(unaligned, 'a') as apk:
        for path in sorted((output / 'dex').glob('*.dex')):
            apk.write(path, path.name)
    artifact = output / 'ui-target-access-unsigned.apk'
    subprocess.run([str(tools / 'zipalign'), '-f', '4', str(unaligned), str(artifact)], check=True)
    print(json.dumps({'artifact': str(artifact), 'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
                      'signed': False, 'deviceExecution': False}))


if __name__ == '__main__':
    main()
