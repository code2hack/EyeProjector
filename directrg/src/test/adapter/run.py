#!/usr/bin/env python3
"""Compile actual client/controller sources and drive cancellation through a modeled Android UI queue."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--source-dir', type=Path, help='Optional exact repository snapshot for before/after source checks')
parser.add_argument('--expect-fail', action='store_true', help='Require an actual compiled baseline regression failure')
parser.add_argument('--expected-failure', action='append', help='Required FAIL scenario prefix (repeatable); defaults to the original review01 cases')
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
source = args.source_dir.resolve() if args.source_dir else root
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=True)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependencies = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
                'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect',
                'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars = [str(j) for dependency in dependencies for j in sorted((cache / dependency).rglob('*.jar'))]
protocol = root / 'protocol/build/tmp/kotlin-classes/debug'
if not jars or not protocol.is_dir():
    parser.error('Run affected Gradle checks first to provision the compiler and unchanged protocol classes')
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
classpath = os.pathsep.join([str(protocol)] + jars)
sources = [source / path for path in [
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/PairedSessionClient.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/SessionAttachment.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/SessionController.kt',
]] + [suite / 'PairedSessionCancellationScenarios.kt']
inputs = output / 'inputs'
inputs.mkdir(exist_ok=True)
input_manifest = []
for path in sources + [Path(__file__).resolve()]:
    snapshot = inputs / path.name
    shutil.copy2(path, snapshot)
    input_manifest.append({'path': str(path), 'snapshot': str(snapshot), 'sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest()})
jar = output / 'session-cancellation-regressions.jar'
command = [java, '-cp', os.pathsep.join(jars), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
           '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', str(jar)] + list(map(str, sources))
with (output / 'compile.log').open('w') as log:
    compiled = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
if compiled.returncode:
    print((output / 'compile.log').read_text())
    sys.exit(compiled.returncode)
with (output / 'scenarios.log').open('w') as log:
    run = subprocess.run([java, '-cp', str(jar) + os.pathsep + classpath,
                          'com.code2hack.eyeprojector.directrg.PairedSessionCancellationScenariosKt'],
                         stdout=log, stderr=subprocess.STDOUT, timeout=45)
text = (output / 'scenarios.log').read_text()
manifest = {
    'compileExit': compiled.returncode, 'scenariosExit': run.returncode, 'expectedBaselineFailure': args.expect_fail,
    'sources': input_manifest,
    'inputsUnchanged': all(hashlib.sha256(Path(p['path']).read_bytes()).hexdigest() == p['sha256'] for p in input_manifest),
    'protocolClasses': [{'path': str(p.relative_to(protocol)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                        for p in sorted(protocol.rglob('*')) if p.is_file()],
    'logSha256': hashlib.sha256(text.encode()).hexdigest(),
    'scope': 'Actual client/controller/attachment source; modeled bounded network and serialized Android UI scheduling; no device proof',
}
(output / 'result.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(text)
if not manifest['inputsUnchanged']:
    sys.exit('Inputs changed during the check; retain this run and repeat at a fixed source')
if args.expect_fail:
    expected = args.expected_failure or ['FAIL R8-01 queued AVAILABLE', 'FAIL R8-02 first final Stop']
    sys.exit(0 if run.returncode != 0 and all(name in text for name in expected) else 1)
sys.exit(run.returncode)
