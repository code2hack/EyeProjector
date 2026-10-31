#!/usr/bin/env python3
"""Compile actual reader/Android adapter/geometry with host doubles; no Gradle/SDK/device operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
actual = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
        'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
jars = [str(j) for dependency in deps for j in sorted((cache / dependency).rglob('*.jar'))]
if not jars:
    parser.error('Existing host Kotlin compiler dependencies are required; this runner never downloads them')
classpath = os.pathsep.join(jars)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
sources = sorted((actual / 'uiaccess').glob('*.kt')) + [actual / 'ProjectionContext.kt', actual / 'hid/PhoneHidKeyService.kt']
sources += [protocol / (name + '.kt') for name in ['Wire', 'Touch', 'PhoneInput', 'ReceiverUsability', 'CameraFeed', 'ActivationControl', 'FunctionIntent']]
sources += sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt']
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=True)
jar = out / 'uiaccess-checks.jar'
command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17', '-no-stdlib', '-no-reflect',
           '-classpath', classpath, '-d', str(jar)] + list(map(str, sources))
with (out / 'compile.log').open('w') as log:
    compile_result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
run_result = None
if compile_result.returncode == 0:
    with (out / 'scenarios.log').open('w') as log:
        run_result = subprocess.run([java, '-cp', str(jar) + os.pathsep + classpath,
            'com.code2hack.eyeprojector.directhost.uiaccess.ScenariosKt'], stdout=log, stderr=subprocess.STDOUT, timeout=60)
result = {'compileExit': compile_result.returncode, 'scenariosExit': run_result.returncode if run_result else None,
    'scope': 'Actual production reader, Android adapter and geometry with modeled framework handles; no SDK compilation or physical/UI/gesture proof',
    'compilerCommand': command,
    'sources': [{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources],
    'dependencies': [{'path': p, 'sha256': hashlib.sha256(Path(p).read_bytes()).hexdigest()} for p in jars]}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / 'compile.log').read_text())
if run_result:
    print((out / 'scenarios.log').read_text())
raise SystemExit(compile_result.returncode or (run_result.returncode if run_result else 1))
