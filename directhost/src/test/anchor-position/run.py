#!/usr/bin/env python3
"""Compile actual coordinate sources and run JUnit using read-only cached jars; no Gradle/SDK/device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--source-dir', type=Path, help='Exact repository snapshot to assess')
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = args.source_dir.resolve() if args.source_dir else suite.parents[3]
suite = root / 'directhost/src/test/anchor-position'
actual = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/anchor/position'
tests = root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/anchor/position'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/CoordinateAnchors.kt'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependencies = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
                'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
                'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
                'junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3']
jars = []
for dependency in dependencies:
    found = sorted((cache / dependency).glob('*/*.jar'))
    if len(found) != 1:
        parser.error(f'Existing host dependency missing/ambiguous: {dependency}; no downloads performed')
    jars.extend(found)
classpath = os.pathsep.join(map(str, jars))
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
sources = sorted(actual.glob('*.kt')) + sorted(tests.glob('*.kt')) + [protocol,
    root / 'protocol/src/test/kotlin/com/code2hack/eyeprojector/protocol/CoordinateAnchorsTest.kt',
    suite / 'CoordinateAnchorPreferencesHostTest.kt', suite / 'doubles/Context.kt']
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=True)
jar = out / 'coordinate-anchor-checks.jar'
command = [java, '-Xmx512m', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
           '-jvm-target', '17', '-no-stdlib', '-no-reflect', '-classpath', classpath,
           '-d', str(jar)] + list(map(str, sources))
run_command = [java, '-Xmx128m', '-cp', str(jar) + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
               'com.code2hack.eyeprojector.protocol.CoordinateAnchorsTest',
               'com.code2hack.eyeprojector.directhost.anchor.position.CoordinateAnchorControllerTest',
               'com.code2hack.eyeprojector.directhost.anchor.position.AnchorPositionStorageTest',
               'com.code2hack.eyeprojector.directhost.anchor.position.CoordinateAnchorPreferencesHostTest']
result = {'scope': 'Actual shared DTO/state codec, controller, list codec, atomic file store and process adapter; '
                  'only Android Context/filesDir is doubled. No Gradle/SDK/device or physical input proof.',
          'compilerCommand': command, 'testCommand': run_command,
          'sources': [{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources],
          'runnerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
          'dependencies': [{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars]}
def run(command, filename, seconds):
    with (out / filename).open('w') as log:
        try:
            return subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=seconds).returncode
        except subprocess.TimeoutExpired:
            log.write('\nHOST_CHECK_TIMEOUT\n')
            return 124
result['compileExit'] = run(command, 'compile.log', 120)
result['junitExit'] = run(run_command, 'junit.log', 60) if result['compileExit'] == 0 else None
if jar.exists():
    result['artifact'] = {'path': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / 'compile.log').read_text())
if result['junitExit'] is not None:
    print((out / 'junit.log').read_text())
raise SystemExit(result['compileExit'] or (result['junitExit'] if result['junitExit'] is not None else 1))
