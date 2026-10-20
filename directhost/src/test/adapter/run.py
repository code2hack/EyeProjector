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
parser.add_argument('--composed', action='store_true', help='Continuous actual bootstrap/service/Bridge/Session scalar plus OFF composition')
parser.add_argument('--legacy-scale-abi', action='store_true', help='Compile the matched pre-correction scalar input ABI for exact-parent RED only')
parser.add_argument('--panel', action='store_true', help='Drive actual Session panel/cleanup composition with modeled Android resources')
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
if args.composed:
    sources = [suite / 'doubles' / name for name in ('AtomicFile.kt', 'Context.kt', 'Json.kt', 'Os.kt', 'Annotation.kt', 'PackageManager.kt')]
    sources += sorted((suite.parent / 'panel/doubles').glob('*.kt'))
    sources = [p for p in sources if p != suite.parent / 'panel/doubles/Json.kt']
    sources += sorted((suite.parent / 'composed/doubles').glob('*.kt'))
    sources += [suite.parent / 'panel/PanelFixtures.kt', suite.parent / 'composed/ComposedScenarios.kt']
    sources += [actual / name for name in ('HostBootstrap.kt', 'HostForgetCompletion.kt', 'BootstrapSessionFiles.kt', 'SessionController.kt', 'SessionAttemptStore.kt', 'ShizukuHostService.kt', 'OwnedHostLifecycle.kt', 'OwnedShellSessions.kt', 'ShellMain.kt', 'PanelPower.kt', 'PhoneTouchDispatcher.kt', 'HeldInputRelease.kt', 'OwnedRelease.kt', 'LiveScale.kt')]
    if args.legacy_scale_abi:
        # Each compiler run sees exactly ONE input ABI, matched to the archived production source.
        generated = output / 'matched-legacy-input'
        generated.mkdir(exist_ok=True)
        for index, source in enumerate(sources):
            if source.name in ('Service.kt', 'ComposedScenarios.kt'):
                content = source.read_text().replace(', expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray\n fun scaleState', '): ByteArray\n fun scaleState', 1)
                if source.name == 'Service.kt':
                    content = content.replace('fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String)', 'fun scaleState()')
                else:
                    content = content.replace('requestId: Long, scale: Double, expectedOwnerEpoch: String, expectedProjectionToken: String', 'requestId: Long, scale: Double')
                    content = content.replace('scaleContexts.add(PanelContext(expectedOwnerEpoch, expectedProjectionToken))', 'scaleContexts.add(PanelContext(\"legacy-owner\", \"legacy-token\"))')
                    content = content.replace('override fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String)', 'override fun scaleState()')
                    content = content.replace('service.requestScale(requestId, scale, expectedOwnerEpoch, expectedProjectionToken)', 'service.requestScale(requestId, scale)')
                    content = content.replace('service.scaleState(expectedOwnerEpoch, expectedProjectionToken)', 'service.scaleState()')
                target = generated / source.name
                target.write_text(content)
                sources[index] = target
elif args.panel:
    sources = sorted((suite.parent / 'panel/doubles').glob('*.kt')) + [suite.parent / 'panel/PanelScenarios.kt', suite.parent / 'panel/PanelFixtures.kt', suite / 'doubles/AtomicFile.kt']
    sources += [actual / name for name in ('ShellMain.kt', 'PanelPower.kt', 'PhoneTouchDispatcher.kt', 'HeldInputRelease.kt', 'OwnedRelease.kt', 'LiveScale.kt')]
else:
    sources = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt']
    sources += [actual / name for name in ('HostBootstrap.kt', 'HostForgetCompletion.kt', 'BootstrapSessionFiles.kt', 'SessionController.kt', 'SessionAttemptStore.kt', 'PanelPower.kt')]
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
sources += [protocol / name for name in ('Wire.kt', 'LayoutScale.kt', 'Touch.kt')]
# Retain exact compiler inputs even for preparation failures and later test corrections.
archive = output / 'compiled-sources'
archive.mkdir(exist_ok=True)
for index, source in enumerate(sources):
    (archive / f'{index:02d}-{source.name}').write_bytes(source.read_bytes())
(output / 'compiler-inputs.json').write_text(json.dumps([
    {'path': str(source), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
    for source in sources], indent=2) + '\n')
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
                          'com.code2hack.eyeprojector.directhost.' + ('ComposedScenariosKt' if args.composed else 'PanelScenariosKt' if args.panel else 'ScenariosKt')],
                         stdout=log, stderr=subprocess.STDOUT, timeout=60)
(output / 'result.json').write_text(json.dumps({
    'compileExit': compiled.returncode, 'scenariosExit': run.returncode,
    'sources': [{'path': str(source), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()} for source in sources],
    'logSha256': hashlib.sha256((output / 'scenarios.log').read_bytes()).hexdigest(),
    'inputAbi': 'legacy3/4' if args.legacy_scale_abi else 'context3/4' if args.composed else 'suite-matched',
    'scope': 'Continuous actual bootstrap/service/Bridge/Session/LiveScale; modeled Binder/Android resources, no physical proof' if args.composed else 'Actual Session command/cleanup composition; modeled Android resources, no physical proof' if args.panel else 'Actual adapter source against modeled host API events; no Android Binder/device proof',
}, indent=2) + '\n')
print((output / 'scenarios.log').read_text())
sys.exit(run.returncode)
