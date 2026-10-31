#!/usr/bin/env python3
"""Focused actual-source Back/Home grammar and both binding-store regressions; no SDK/Gradle."""
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
protocol = 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/'
files = [root / (protocol + n + '.kt') for n in [
    'BackHomeGestureRecognizer', 'Wire', 'PhoneInput', 'Touch', 'ReceiverUsability', 'CameraFeed', 'ActivationControl']]
files += [root / n for n in [
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/HidBindings.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidBindings.kt',
    'protocol/src/test/kotlin/com/code2hack/eyeprojector/protocol/BackHomeGestureRecognizerTest.kt',
    'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/HidBindingsTest.kt',
    'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidBindingsTest.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidPanel.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidController.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/input/PhoneHidRoute.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/activation/PhoneHidActivation.kt',
    'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/activation/PhoneHidDisposition.kt',
    'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidBackHomeOwnershipTest.kt',
]]
files += sorted((root / 'directhost/src/test/hidnavigation').glob('*.kt'))
ui_doubles = root / 'directrg/src/test/hidframework/doubles'
files += [ui_doubles / (n + '.kt') for n in [
    'Context', 'Resources', 'InputManager', 'KeyEvent', 'View', 'Rect', 'Point', 'Color', 'ColorStateList', 'SystemClock', 'Handler']]
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
coordinates = [
    ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
    ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    ('org.jetbrains', 'annotations', '13.0'),
    ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3'),
]
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
jar = out / 'hid-navigation.jar'
commands = {
    'compile': [java, '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] +
               [str(out / f['snapshot']) for f in inputs],
    'run': [java, '-Xmx256m', '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
            'com.code2hack.eyeprojector.protocol.BackHomeGestureRecognizerTest',
            'com.code2hack.eyeprojector.directrg.HidBindingsTest',
            'com.code2hack.eyeprojector.directhost.hid.PhoneHidBindingsTest',
            'com.code2hack.eyeprojector.directhost.hid.PhoneHidPanelTest',
            'com.code2hack.eyeprojector.directhost.hid.PhoneHidBackHomeOwnershipTest'],
}
result = {'scope': 'Actual pure grammar, shared roles/codecs, both production stores, Phone controller/route/activation port and complete Phone panel. Controller composition uses the actual Back/Home grammar as its modeled Session destination and opaque wake-authority types. Panel uses Android widget and runtime/interception doubles. No actual PhoneInputMerger, transport, pin service, device or physical claim.',
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
