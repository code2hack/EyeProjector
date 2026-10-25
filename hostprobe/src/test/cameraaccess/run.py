#!/usr/bin/env python3
"""Compile the exact production close owner with delayed fake callbacks; cached JVM only, no SDK."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
suite = Path(__file__).resolve().parent
root = suite.parents[3]
source = root / 'hostprobe/src/main/kotlin/com/code2hack/eyeprojector/hostprobe/CameraAccessActivity.kt'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
text = source.read_text()
owner = text.split('// BEGIN CAMERA_CLOSE_OWNER', 1)[1].split('\n', 1)[1].split('// END CAMERA_CLOSE_OWNER', 1)[0]
actual = out / 'CameraCloseOwner.kt'
actual.write_text('package com.code2hack.eyeprojector.hostprobe\n' + owner)
tests = out / 'Scenarios.kt'
tests.write_bytes((suite / 'Scenarios.kt').read_bytes())
(out / 'CameraAccessActivity.kt').write_bytes(source.read_bytes())
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
coordinates = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
               ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
               ('org.jetbrains', 'annotations', '13.0')]
jars = []
for group, artifact, version in coordinates:
    matches = list((cache / group / artifact / version).glob('*/*.jar'))
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one existing cached JAR: ' + artifact + '; no download attempted')
    jars.extend(matches)
cp = os.pathsep.join(map(str, jars))
java = '/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
jar = out / 'owner-tests.jar'
commands = {
    'compile': [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-jvm-target', '17', '-classpath', cp, '-d', str(jar), str(actual), str(tests)],
    'tests': [java, '-cp', str(jar) + os.pathsep + cp, 'com.code2hack.eyeprojector.hostprobe.ScenariosKt'],
}
record = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'scope': 'Exact production CameraCloseOwner/mode declaration; fake callbacks only. Activity/Camera2/layout/imagery not executed.',
          'productionSource': {'path': str(source), 'sha256': sha(source)},
          'runner': {'path': str(Path(__file__).resolve()), 'sha256': sha(Path(__file__).resolve())},
          'scenarios': {'path': str(suite / 'Scenarios.kt'), 'sha256': sha(tests)},
          'compiledOwnerSha256': sha(actual), 'commands': commands,
          'dependencies': [{'path': str(p), 'sha256': sha(p)} for p in jars], 'exit': None}
(out / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
try:
    for phase, timeout in [('compile', 90), ('tests', 20)]:
        with (out / (phase + '.log')).open('w') as log:
            result = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        record[phase + 'Exit'] = result.returncode
        print((out / (phase + '.log')).read_text(), end='', flush=True)
        record['exit'] = result.returncode
        if result.returncode:
            break
except subprocess.TimeoutExpired as error:
    record['exit'] = 124
    record['error'] = str(error)
finally:
    record['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    record['sourceUnchanged'] = sha(source) == record['productionSource']['sha256']
    record['logs'] = {p.name: sha(p) for p in out.glob('*.log')}
    if jar.exists():
        record['artifactSha256'] = sha(jar)
    (out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(record['exit'] if record['sourceUnchanged'] else 2)
