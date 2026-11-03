#!/usr/bin/env python3
"""Actual profile codec/controller/pin/panel checks; no SDK, Gradle or device operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
p.add_argument('--json-jar', type=Path, required=True, help='org.json:json:20240303 host JSON implementation')
p.add_argument('--callers', action='store_true', help='Compile actual Runtime and KeyService caller composition')
a = p.parse_args()
root = Path(__file__).resolve().parents[4]
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
suite = Path(__file__).resolve().parent
files = [protocol / (n + '.kt') for n in [
    'PhoneHidGestureRecognizer', 'Wire', 'PhoneInput', 'Touch', 'ReceiverUsability',
    'CameraFeed', 'ActivationControl', 'ReceiverControlSource', 'CoordinateAnchors', 'CoordinateMode']]
files += [host / 'hid' / (n + '.kt') for n in [
    'PhoneHidBindings', 'PhoneHidProfiles', 'PhoneHidController', 'PhoneHidPinRuntime', 'PhoneHidPinService', 'PhoneHidPanel']]
files += [host / 'activation' / (n + '.kt') for n in ['PhoneHidActivation', 'PhoneHidDisposition']]
files += [root / 'directhost/src/test/hidnavigation/ActivationBoundary.kt']
files += [root / 'directhost/src/test/hid/doubles' / (n + '.kt') for n in ['Os', 'Color', 'Resources', 'InputManager', 'KeyEvent', 'Accessibility', 'AccessibilityEvent', 'Settings', 'Annotation']]
files += [root / 'directhost/src/test/hidnavigation' / (n + '.kt') for n in ['Widgets']]
files += sorted((suite / 'doubles').glob('*.kt'))
files += [suite / 'ProfilesTest.kt', root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidBindingsTest.kt']
if a.callers:
    files.remove(suite / 'ProfilesTest.kt')
    files.remove(suite / 'doubles/PanelRuntime.kt')
    files.remove(root / 'directhost/src/test/hidnavigation/ActivationBoundary.kt')
    files += [host / 'hid' / (n + '.kt') for n in ['PhoneHidRuntime', 'PhoneHidKeyService']]
    files += [host / 'activation/HidWakeRequest.kt', suite / 'CallerTest.kt']
    files += sorted((suite / 'caller-doubles').glob('*.kt'))
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
# Record the supplied host JSON implementation alongside the compiler dependencies.
json_jar = a.json_jar.resolve()
jars.append(json_jar)
inputs = []
for f in files:
    snapshot = out / 'source' / f.relative_to(root)
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(f.read_bytes())
    inputs.append({'path': str(f.relative_to(root)), 'snapshot': str(snapshot.relative_to(out)), 'sha256': sha(snapshot)})
java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
cp = os.pathsep.join(map(str, jars))
jar = out / 'hid-profiles.jar'
commands = {
    'compile': [java, '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] +
               [str(out / f['snapshot']) for f in inputs],
    'run': [java, '-Xmx256m', '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
            'com.code2hack.eyeprojector.directhost.hid.PhoneHidBindingsTest',
            'com.code2hack.eyeprojector.directhost.hid.CallerTest' if a.callers else
            'com.code2hack.eyeprojector.directhost.hid.ProfilesTest'],
}
scope = ('Actual full PhoneHidRuntime and KeyService + profiles/bindings/controller/pin/notification/panel/learning callers. Session/snapshot availability and target routes are explicit doubles; no extraction of Runtime/KeyService source.' if a.callers else
         'Actual profiles/bindings/controller/pin/notification/panel/learning components. Panel Runtime composition is an explicit test boundary.')
result = {'actualRuntimeKeyServiceCallers': a.callers, 'scope': scope + ' In-memory Android preferences, queued main Handler, Shizuku/Binder/service boundaries. No Android/OS/native-grab/physical HID acceptance.',
          'inputs': inputs, 'runnerSha256': sha(Path(__file__)),
          'dependencies': [{'path': str(f), 'sha256': sha(f)} for f in jars],
          'commands': commands, 'compileExit': None, 'runExit': None}
try:
    for phase, limit in [('compile', 60), ('run', 30)]:
        with (out / (phase + '.log')).open('w') as log:
            r = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=limit)
        result[phase + 'Exit'] = r.returncode
        print((out / (phase + '.log')).read_text(), end='')
        if r.returncode:
            break
finally:
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
raise SystemExit(0 if result['compileExit'] == result['runExit'] == 0 else 1)
