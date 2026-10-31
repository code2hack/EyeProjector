#!/usr/bin/env python3
"""Compile complete actual Activity navigation/cancellation methods on named host API boundaries."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
actual = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
parser_path = root / 'directhost/src/test/hid/run.py'
spec = importlib.util.spec_from_file_location('source_blocks', parser_path)
parser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parser)
source = (actual / 'ReceiverActivity.kt').read_text()
names = ['dispatchNavigation', 'completedNavigation', 'contextualHome', 'contextualBack', 'remoteBack',
         'sleepGlasses', 'showControlPanel', 'cancelInput', 'exitToLauncher', 'cancelOriginalOnExit',
         'finishProjection']
blocks = [parser.block(source, rf'^    (?:private|internal) fun {name}\(') for name in names]
getter = re.search(r'^    internal val projectionContext:.*$', source, re.M).group()
initializer = re.search(r'^        completedInput = CompletedReceiverInput\(.*?::completedNavigation\)', source, re.M | re.S).group()
fixture = (suite / 'ActivityBoundary.kt').read_text().replace('// ACTUAL_METHODS', '\n'.join(blocks))
fixture = fixture.replace('// ACTUAL_CONTEXT_GETTER', getter).replace('// ACTUAL_COMPLETED_INITIALIZER', initializer)
generated = out / 'ActivityNavigationWiring.kt'
generated.write_text(fixture)
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
files = [generated, suite / 'Boundaries.kt', suite / 'Scenarios.kt']
files += [actual / (n + '.kt') for n in ['ContextualNavigation', 'CompletedReceiverInput', 'SessionPresentation']]
files += [protocol / n for n in ['Wire.kt', 'PhoneInput.kt', 'Touch.kt', 'LayoutScale.kt', 'ReceiverUsability.kt', 'ActivationControl.kt', 'CameraFeed.kt', 'FunctionIntent.kt']]
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependencies = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
                'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
                'org.jetbrains.intellij.deps/trove4j/1.0.20200330', 'org.jetbrains/annotations/13.0',
                'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
jars = [str(j) for d in dependencies for j in sorted((cache / d).rglob('*.jar'))]
java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
cp = os.pathsep.join(jars)
jar = out / 'navigation.jar'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
snapshots = []
entries = []
for index, path in enumerate(files):
    snapshot = out / 'source' / str(index) / path.name
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(path.read_bytes())
    entries.append({'path': str(path), 'snapshot': str(snapshot), 'sha256': sha(snapshot)})
    snapshots.append(str(snapshot))
commands = {
    'compile': [java, '-Xmx2g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib',
                '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] + snapshots,
    'run': [java, '-cp', str(jar) + os.pathsep + cp, 'com.code2hack.eyeprojector.directrg.ScenariosKt']}
record = {'source': str(actual / 'ReceiverActivity.kt'), 'sourceSha256': sha(actual / 'ReceiverActivity.kt'),
          'extractedMethods': dict(zip(names, blocks)), 'completedInitializer': initializer, 'contextGetter': getter,
          'inputs': entries, 'commands': commands,
          'scope': 'Actual Activity method bodies and completed-input initializer/consumer; modeled UI, Android clock, sleep submission and session APIs. No device/native/secure-keyguard proof.'}
try:
    for phase, timeout in [('compile', 90), ('run', 20)]:
        with (out / (phase + '.log')).open('w') as log:
            r = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        record[phase + 'Exit'] = r.returncode
        print((out / (phase + '.log')).read_text(), end='')
        if r.returncode:
            break
finally:
    (out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(0 if record.get('compileExit') == record.get('runExit') == 0 else 1)
