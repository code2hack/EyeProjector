#!/usr/bin/env python3
"""Run actual bootstrap adapter against host API doubles after Gradle has provisioned Kotlin."""
import argparse
import os
import hashlib
import json
import shutil
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--source-dir', type=Path, help='Optional exact adapter source snapshot for a before/after check')
args = parser.parse_args()
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=True)
suite = Path(__file__).resolve().parent
root = suite.parents[3]
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = [
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21',
    'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21',
    'org.jetbrains.kotlin/kotlin-reflect',
    'org.jetbrains/annotations/13.0',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm',
]
jars = [str(j) for dependency in deps for j in sorted((cache / dependency).rglob('*.jar'))]
if not jars:
    parser.error('Run the affected Gradle checks first to provision Kotlin compiler dependencies')
classpath = os.pathsep.join([str(root / "protocol/build/tmp/kotlin-classes/debug")] + jars)
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
if not java:
    parser.error('Configure JDK 17 using JAVA_HOME or PATH')
actual = args.source_dir.resolve() if args.source_dir else root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
sources = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt']
sources += [actual / name for name in ('HostBootstrap.kt', 'HostForgetCompletion.kt', 'BootstrapSessionFiles.kt', 'SessionController.kt', 'SessionAttemptStore.kt', 'ProjectionDisplayState.kt')]
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
sources += [protocol / name for name in ('Wire.kt', 'LayoutScale.kt', 'Touch.kt')]
jar = output / 'adapter-regressions.jar'
compile_command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(jar)] + list(map(str, sources))
with (output / 'compile.log').open('w') as log:
    compiled = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
if compiled.returncode:
    print((output / 'compile.log').read_text())
    sys.exit(compiled.returncode)
with (output / 'scenarios.log').open('w') as log:
    run = subprocess.run([java, '-cp', str(jar) + os.pathsep + classpath,
                          'com.code2hack.eyeprojector.directhost.ScenariosKt'],
                         stdout=log, stderr=subprocess.STDOUT, timeout=60)
(output / 'result.json').write_text(json.dumps({
    'compileExit': compiled.returncode, 'scenariosExit': run.returncode,
    'sources': [{'path': str(source), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()} for source in sources],
    'logSha256': hashlib.sha256((output / 'scenarios.log').read_bytes()).hexdigest(),
    'scope': 'Actual adapter source against modeled host API events; no Android Binder/device proof',
}, indent=2) + '\n')
print((output / 'scenarios.log').read_text())
sys.exit(run.returncode)
