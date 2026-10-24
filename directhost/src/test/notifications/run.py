#!/usr/bin/env python3
"""Bounded, cached-only compilation of the actual metadata classifier and JUnit tests."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True, help='New directory for this closed run')
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=False)
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/notifications'
sources = [host / 'NotificationEvents.kt', host / 'AppNotificationStore.kt',
           root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/notifications/NotificationEventsTest.kt',
           suite / 'RunTests.kt']
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependencies = (
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21',
    'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21',
    'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
    'org.jetbrains/annotations/13.0',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
    'junit/junit/4.13.2',
    'org.hamcrest/hamcrest-core/1.3',
)
java = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
record = {'startedUtc': now(), 'finishedUtc': None, 'scope': 'Actual pure Kotlin metadata classifier and preference store; synthetic callback/snapshot inputs; no Android/activation/launch/device proof',
          'runner': {'path': str(Path(__file__).resolve()), 'sha256': digest(Path(__file__).resolve())},
          'compileExit': None, 'testsExit': None, 'timeoutStage': None, 'sources': [], 'dependencies': []}
artifact = output / 'notification-metadata-tests.jar'
exit_code = 1
try:
    jars = []
    for dependency in dependencies:
        matches = sorted((cache / dependency).rglob('*.jar'))
        if len(matches) != 1:
            raise RuntimeError('Expected one cached jar for ' + dependency + '; no download/build attempted')
        jars.extend(matches)
    classpath = os.pathsep.join(map(str, jars))
    record['dependencies'] = [{'path': str(path), 'sha256': digest(path)} for path in jars]
    record['java'] = {'path': str(java.resolve()), 'sha256': digest(java.resolve())}
    snapshots = []
    for index, source in enumerate(sources):
        snapshot = output / 'sources' / str(index) / source.name
        snapshot.parent.mkdir(parents=True)
        snapshot.write_bytes(source.read_bytes())
        snapshots.append(snapshot)
        record['sources'].append({'path': str(source), 'snapshot': str(snapshot), 'sha256': digest(snapshot)})
    compile_argv = [str(java), '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                    '-d', str(artifact)] + list(map(str, snapshots))
    tests_argv = [str(java), '-cp', str(artifact) + os.pathsep + classpath,
                  'com.code2hack.eyeprojector.directhost.notifications.RunTestsKt']
    record.update({'compileArgv': compile_argv, 'testsArgv': tests_argv})
    record['argvSha256'] = hashlib.sha256(json.dumps([compile_argv, tests_argv], separators=(',', ':')).encode()).hexdigest()
    (output / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
    with (output / 'compile.log').open('w') as log:
        compiled = subprocess.run(compile_argv, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    record['compileExit'] = compiled.returncode
    exit_code = compiled.returncode
    if not compiled.returncode:
        with (output / 'tests.log').open('w') as log:
            tests = subprocess.run(tests_argv, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        record['testsExit'] = tests.returncode
        exit_code = tests.returncode
        text = (output / 'tests.log').read_text()
        record['namedResults'] = [line for line in text.splitlines() if line.startswith(('PASS ', 'FAIL ', 'RESULT '))]
        print(text, end='')
    else:
        print((output / 'compile.log').read_text(), end='')
except subprocess.TimeoutExpired as error:
    record['timeoutStage'] = 'compile' if record['compileExit'] is None else 'tests'
    record['timeoutSeconds'] = error.timeout
    exit_code = 124
except Exception as error:
    record['error'] = str(error)
    print(str(error), file=sys.stderr)
finally:
    record['finishedUtc'] = now()
    record['sourcesUnchanged'] = all(digest(Path(item['path'])) == item['sha256'] for item in record['sources'])
    record['logs'] = [{'path': str(path), 'sha256': digest(path)} for path in (output / 'compile.log', output / 'tests.log') if path.is_file()]
    if artifact.is_file():
        record['artifact'] = {'path': str(artifact), 'sha256': digest(artifact)}
    record['exit'] = exit_code if record['sourcesUnchanged'] else 2
    (output / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
sys.exit(record['exit'])
