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
sources += [actual / 'anchor/preferences' / (name + '.kt') for name in ['AppAnchor', 'AppAnchorStore']]
sources += [actual / 'uiaccess' / (name + '.kt') for name in
            ['UiTargetSnapshot', 'UiSourceGeometry', 'UiTargetReader', 'UiTargetSnapshotProvider']]
sources += [actual / 'ProjectionContext.kt']
sources += [protocol / (name + '.kt') for name in
            ['Wire', 'Touch', 'PhoneInput', 'ReceiverUsability', 'CameraFeed', 'ActivationControl', 'FunctionIntent', 'AnchorMessage']]
# Only unused CapturedProjection return types are doubled; all snapshot/geometry/identity models are real.
sources += [suite.parent / 'uiaccess/doubles/ProjectionDependencies.kt', suite.parent / 'uiaccess/doubles/SnapshotDescriptor.kt']
sources += [suite.parent / 'kotlin/com/code2hack/eyeprojector/directhost/anchor/registration/AnchorRegistrationTest.kt']
sources += [root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/AnchorRegistrationController.kt',
            root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/AnchorRegistrationControllerTest.kt']
if args.provider_api_checks:
    sources += [actual / 'anchor/registration/AnchorRegistrationProvider.kt', suite / 'AnchorRuntimeApi.kt',
                suite / 'AnchorRegistrationProviderTest.kt']
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=True)
jar = out / 'anchor-registration-checks.jar'
command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17', '-no-stdlib', '-no-reflect',
           '-classpath', classpath, '-d', str(jar)] + list(map(str, sources))
with (out / 'compile.log').open('w') as log:
    compiled = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
ran = None
run_command = [java, '-cp', str(jar) + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
               'com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationTest',
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
          'sources': [{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources],
          'dependencies': [{'path': p, 'sha256': hashlib.sha256(Path(p).read_bytes()).hexdigest()} for p in jars]}
if jar.exists():
    result['artifact'] = {'path': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / 'compile.log').read_text())
if ran:
    print((out / 'junit.log').read_text())
raise SystemExit(compiled.returncode or (ran.returncode if ran else 1))
