#!/usr/bin/env python3
"""Compile actual v0.4 HID store/controller/gesture/merger sources without SDK or Gradle writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[4]
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
files = [protocol / (n + '.kt') for n in [
    'PhoneHidGestureRecognizer', 'Wire', 'PhoneInput', 'Touch', 'ReceiverUsability',
    'CameraFeed', 'ActivationControl', 'ReceiverControlIntent', 'CoordinateAnchors', 'CoordinateMode']]
files += [host / n for n in [
    'hid/PhoneHidBindings.kt', 'hid/PhoneHidController.kt', 'input/PhoneHidRoute.kt',
    'input/PhoneInputMerger.kt', 'PhoneTouchDispatcher.kt', 'activation/PhoneHidActivation.kt', 'activation/PhoneHidDisposition.kt']]
files += [root / 'directhost/src/test/hidnavigation/ActivationBoundary.kt',
          root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/PhoneInputMergerTest.kt',
          root / 'protocol/src/test/kotlin/com/code2hack/eyeprojector/protocol/PhoneInputTest.kt']
files += [host / n for n in [
    'input/PhoneHidOriginal.kt','input/PhoneModeOwner.kt','input/CoordinateInputRouter.kt',
    'anchor/position/CoordinateAnchorController.kt','anchor/position/AnchorPositionStorage.kt',
    'anchor/position/FileAnchorPositionStorage.kt','anchor/position/CoordinateAnchorPreferences.kt']]
files += [root / 'directhost/src/test/anchor-position/doubles/Context.kt']
files += sorted((root / 'directhost/src/test/mode-routing/doubles').glob('*.kt'))
files += [root / 'directhost/src/test/mode-routing/ModeRoutingTest.kt']
files += [root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/hid' / n for n in [
    'PhoneHidBindingsTest.kt', 'PhoneHidV04Test.kt']]
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
coordinates = [
    ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
    ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    ('org.jetbrains', 'annotations', '13.0'),
    ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3')]
jars = []
for group, name, version in coordinates:
    found = sorted((cache / group / name / version).glob(f'*/{name}-{version}.jar'))
    if len(found) != 1:
        p.error('Missing/ambiguous cached compiler input ' + name)
    jars += found
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()
inputs = []
for f in files:
    snapshot = out / 'source' / f.relative_to(root)
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(f.read_bytes())
    inputs.append({'path': str(f.relative_to(root)), 'snapshot': str(snapshot.relative_to(out)), 'sha256': sha(snapshot)})
java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
cp = os.pathsep.join(map(str, jars))
jar = out / 'hid-v04.jar'
commands = {
    'compile': [java, '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] +
               [str(out / f['snapshot']) for f in inputs],
    'run': [java, '-Xmx256m', '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
            'com.code2hack.eyeprojector.directhost.hid.PhoneHidBindingsTest',
            'com.code2hack.eyeprojector.directhost.hid.PhoneHidV04Test',
            'com.code2hack.eyeprojector.directhost.PhoneInputMergerTest',
            'com.code2hack.eyeprojector.protocol.PhoneInputTest',
            'com.code2hack.eyeprojector.directhost.input.ModeRoutingTest'],
}
result = {'scope': 'Actual v0.4 store/controller/route/merger/gesture/PhoneInput codec. Actual app-process mode owner/router/merger and real concurrent blocked Mode regression. Only Context/Binder transport is doubled; no Android or physical HID/GL/Shell claim.',
          'inputs': inputs, 'runnerSha256': sha(Path(__file__)),
          'dependencies': [{'path': str(f), 'sha256': sha(f)} for f in jars],
          'commands': commands, 'compileExit': None, 'runExit': None}
try:
    for phase, limit in [('compile', 60), ('run', 20)]:
        with (out / (phase + '.log')).open('w') as log:
            r = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=limit)
        result[phase + 'Exit'] = r.returncode
        print((out / (phase + '.log')).read_text(), end='')
        if r.returncode:
            break
finally:
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
raise SystemExit(0 if result['compileExit'] == result['runExit'] == 0 else 1)
