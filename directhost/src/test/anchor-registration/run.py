#!/usr/bin/env python3
"""Run actual registration/target/store JUnit source with a cached host compiler, without Gradle/SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--provider-api-checks', action='store_true', help='Actual provider/shared DTO with explicitly modeled runtime API')
parser.add_argument('--uiaccess-ref', help='Exact committed reader/DTO dependency for isolated frontmost composition')
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
actual = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
        'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
        'junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3']
jars = []
for dependency in deps:
    found = sorted((cache / dependency).rglob('*.jar'))
    if not found:
        parser.error(f'Existing host dependency missing: {dependency}; this runner never downloads dependencies')
    jars.extend(map(str, found))
classpath = os.pathsep.join(jars)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
sources = sorted((actual / 'anchor/target').glob('*.kt'))
sources += [p for p in sorted((actual / 'anchor/registration').glob('*.kt')) if p.name != 'AnchorRegistrationProvider.kt']
sources += [actual / 'anchor/preferences' / (name + '.kt') for name in ['AppAnchor', 'AppAnchorStore', 'FileAppAnchorStorage']]
sources += [actual / 'uiaccess' / (name + '.kt') for name in
            ['UiTargetSnapshot', 'UiSourceGeometry', 'UiTargetReader', 'UiTargetSnapshotProvider']]
sources += [actual / 'ProjectionContext.kt']
sources += [protocol / (name + '.kt') for name in
            ['Wire', 'Touch', 'PhoneInput', 'ReceiverUsability', 'CameraFeed', 'ActivationControl', 'AnchorIntent', 'AnchorMessage',
             'ReceiverControlIntent', 'LayoutScale', 'SessionState']]
# Only unused CapturedProjection return types are doubled; all snapshot/geometry/identity models are real.
sources += [suite.parent / 'uiaccess/doubles/ProjectionDependencies.kt', suite.parent / 'uiaccess/doubles/SnapshotDescriptor.kt']
sources += [suite.parent / 'kotlin/com/code2hack/eyeprojector/directhost/anchor/registration/AnchorRegistrationTest.kt',
            suite / 'FrontmostRegistrationTest.kt', suite / 'IdlessIdentityTest.kt',
            suite.parent / 'kotlin/com/code2hack/eyeprojector/directhost/anchor/target/AnchorTargetResolverTest.kt']
sources += [root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/AnchorRegistrationController.kt',
            root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/AnchorRegistrationControllerTest.kt']
if args.provider_api_checks:
    sources += [actual / 'anchor/registration/AnchorRegistrationProvider.kt', suite / 'AnchorRuntimeApi.kt',
                suite / 'AnchorRegistrationProviderTest.kt']
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=True)
inputs = []
records = []
for source in sources:
    dependency = args.uiaccess_ref if source in [actual / 'uiaccess/UiTargetSnapshot.kt', actual / 'uiaccess/UiTargetReader.kt'] else None
    data = subprocess.check_output(['git', 'show', dependency + ':' + str(source.relative_to(root))], cwd=root) if dependency else source.read_bytes()
    frozen = out / 'inputs' / source.relative_to(root)
    frozen.parent.mkdir(parents=True, exist_ok=True)
    frozen.write_bytes(data)
    inputs.append(str(frozen))
    records.append({'path': str(source), 'snapshot': str(frozen), 'sha256': hashlib.sha256(data).hexdigest(), 'gitRef': dependency})
# Historical raw306 has no drawing-order field. Model explicit sibling order for the test,
# using its captured child positions as test input, never as a production fallback.
fixture = suite.parent / 'uiaccess/fixtures/doubao-voice306.json'
nodes = json.loads(fixture.read_text())['nodes']
by_ordinal = {node['ordinal']: node for node in nodes}
def kotlin(value):
    if value is None: return 'null'
    if isinstance(value, bool): return str(value).lower()
    if isinstance(value, str): return json.dumps(value)
    return str(value)
def drawing_path(node):
    parents = drawing_path(by_ordinal[node['parentOrdinal']]) if node['parentOrdinal'] is not None else []
    index = node['childIndex'] if node['childIndex'] is not None else -1
    return parents + [(node['ordinal'], index + 1, index)]
rows = []
for node in nodes:
    path = drawing_path(node)
    fields = [kotlin(node[k]) for k in ['ordinal', 'windowId', 'packageName', 'resourceId', 'className']]
    fields += ['UiBounds(' + ','.join(map(str, node['bounds'])) + ')', 'listOf(' + ','.join(map(str, node['actionIds'])) + ')']
    fields += [kotlin(node[k]) for k in ['clickable', 'longClickable', 'visible', 'enabled']]
    fields += ['listOf(' + ','.join(str(step[0]) for step in path[:-1]) + ')', str(path[-1][1]),
               'listOf(' + ','.join('UiDrawingOrderStep(' + ','.join(map(str, step)) + ')' for step in path) + ')']
    rows.append('UiTargetObservation(' + ','.join(fields) + ')')
generated = out / 'HistoricalDoubaoNodes.kt'
generated.write_text('package com.code2hack.eyeprojector.directhost.anchor.registration\nimport com.code2hack.eyeprojector.directhost.uiaccess.*\ninternal fun historicalDoubaoNodes() = listOf(\n' + ',\n'.join(rows) + '\n)\n')
inputs.append(str(generated))
jar = out / 'anchor-registration-checks.jar'
command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17', '-no-stdlib', '-no-reflect',
           '-classpath', classpath, '-d', str(jar)] + inputs
with (out / 'compile.log').open('w') as log:
    compiled = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
ran = None
run_command = [java, '-cp', str(jar) + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
               'com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationTest',
               'com.code2hack.eyeprojector.directhost.anchor.registration.FrontmostRegistrationTest',
               'com.code2hack.eyeprojector.directhost.anchor.registration.IdlessIdentityTest',
               'com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolverTest',
               'com.code2hack.eyeprojector.directrg.AnchorRegistrationControllerTest']
if args.provider_api_checks:
    run_command += ['com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationProviderTest']
if compiled.returncode == 0:
    with (out / 'junit.log').open('w') as log:
        ran = subprocess.run(run_command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
result = {'compileExit': compiled.returncode, 'junitExit': ran.returncode if ran else None,
          'modeledProviderApi': args.provider_api_checks,
          'scope': 'Actual production registration capture/coordinator, target resolver, atomic store and snapshot/geometry models; host values only, no device proof',
          'compilerCommand': command, 'testCommand': run_command,
          'uiaccessRef': args.uiaccess_ref, 'sources': records,
          'historicalFixture': {'path': str(fixture), 'sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
                                'drawingOrder': 'modeled from captured child positions; not observed current device order'},
          'generatedFixture': {'path': str(generated), 'sha256': hashlib.sha256(generated.read_bytes()).hexdigest()},
          'dependencies': [{'path': p, 'sha256': hashlib.sha256(Path(p).read_bytes()).hexdigest()} for p in jars]}
if jar.exists():
    result['artifact'] = {'path': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / 'compile.log').read_text())
if ran:
    print((out / 'junit.log').read_text())
raise SystemExit(compiled.returncode or (ran.returncode if ran else 1))
