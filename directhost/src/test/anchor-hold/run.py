#!/usr/bin/env python3
"""Compile exact Anchor/indicator sources with cached JVM dependencies, without Gradle or SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
p.add_argument('--indicator-only', action='store_true')
p.add_argument('--dependency-manifest', type=Path, help='Use only the Manager-frozen compiler/JUnit JARs')
a = p.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
        'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
        'junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3']
jars = []
if a.dependency_manifest:
    frozen = json.loads(a.dependency_manifest.read_text())
    for entry in frozen['jars']:
        if entry['kind'] == 'compiler-dependency':
            jar = Path(entry['path'])
            if hashlib.sha256(jar.read_bytes()).hexdigest() != entry['sha256']:
                p.error('Frozen compiler dependency hash mismatch: ' + str(jar))
            jars.append(jar)
else:
    for dep in deps:
        found = sorted((cache / dep).rglob('*.jar'))
        if not found:
            p.error(f'Missing cached host dependency: {dep}')
        jars += found
cp = os.pathsep.join(map(str, jars))
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
sources = [protocol / (n + '.kt') for n in ['Wire', 'Touch', 'PhoneInput', 'ReceiverUsability', 'CameraFeed',
           'ActivationControl', 'FunctionIntent', 'AnchorMessage', 'BackHomeGestureRecognizer']]
sources += [root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/AnchorHeldIndicator.kt',
            root / 'directrg/src/test/anchor-hold/AnchorHeldIndicatorTest.kt']
tests = ['com.code2hack.eyeprojector.directrg.AnchorHeldIndicatorTest']
if not a.indicator_only:
    sources += sorted((host / 'anchor/target').glob('*.kt'))
    sources += [host / 'anchor/execution' / (n + '.kt') for n in
                ['AnchorContactOwner', 'AnchorAppCoordinator', 'AnchorSessionInput', 'AnchorExecutionCodec']]
    sources += [host / 'anchor/preferences' / (n + '.kt') for n in ['AppAnchor', 'AppAnchorStore']]
    sources += [host / 'uiaccess' / (n + '.kt') for n in
                ['UiTargetSnapshot', 'UiSourceGeometry', 'UiTargetReader', 'UiTargetSnapshotProvider']]
    sources += [host / (n + '.kt') for n in ['ProjectionContext', 'PhoneTouchDispatcher']]
    sources += [host / 'input/PhoneInputMerger.kt', host / 'input/FunctionWork.kt', suite.parent / 'uiaccess/doubles/ProjectionDependencies.kt',
                suite.parent / 'uiaccess/doubles/SnapshotDescriptor.kt', suite / 'AnchorHoldTest.kt', suite / 'AnchorHoldWireTest.kt',
                suite.parent / 'kotlin/com/code2hack/eyeprojector/directhost/anchor/execution/AnchorExecutionTest.kt']
    tests += ['com.code2hack.eyeprojector.directhost.anchor.execution.AnchorHoldTest',
              'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorHoldWireTest',
              'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorExecutionTest']
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=True)
snapshots = []
for i, source in enumerate(sources):
    copy = out / 'sources' / str(i) / source.name
    copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_bytes(source.read_bytes())
    snapshots.append(copy)
jar = out / 'anchor-hold-checks.jar'
compile_cmd = [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17',
               '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(jar)] + list(map(str, snapshots))
test_cmd = [java, '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore'] + tests
record = {'scope': 'Exact production source with cached compiler; no Gradle/SDK/device or app-effect proof',
          'compileCommand': compile_cmd, 'testCommand': test_cmd,
          'sources': [{'path': str(s), 'snapshot': str(c), 'sha256': hashlib.sha256(c.read_bytes()).hexdigest()}
                      for s, c in zip(sources, snapshots)],
          'dependencies': [{'path': str(j), 'sha256': hashlib.sha256(j.read_bytes()).hexdigest()} for j in jars],
          'runnerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
with (out / 'compile.log').open('w') as log:
    compiled = subprocess.run(compile_cmd, stdout=log, stderr=subprocess.STDOUT, timeout=120)
record['compileExit'] = compiled.returncode
if compiled.returncode == 0:
    with (out / 'junit.log').open('w') as log:
        ran = subprocess.run(test_cmd, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    record['testExit'] = ran.returncode
    record['artifactSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    print((out / 'junit.log').read_text())
else:
    print((out / 'compile.log').read_text())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(record['compileExit'] or record.get('testExit', 1))
