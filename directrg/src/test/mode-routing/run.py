#!/usr/bin/env python3
"""Compile actual v0.4 HID store/controller/gesture/merger sources without SDK or Gradle writes."""
import importlib.util
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
gl = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
files = [protocol / (n + '.kt') for n in [
    'Wire','PhoneInput','Touch','ReceiverUsability','CameraFeed','ActivationControl',
    'CoordinateAnchors','CoordinateMode']]
files += [gl / (n + '.kt') for n in [
    'NativeFunctionInput','FunctionGestureRecognizer','InputRetirement','PointerOverlay','HeadPointerModel','HeadOrientation']]
files += sorted((root / 'directrg/src/test/mode-routing/doubles').glob('*.kt'))
files += [root / 'directrg/src/test/mode-routing/CoordinateModeRenderingTest.kt']
# Preserve the existing extraction workflow: compile exact caller bodies, not copied startup logic.
parser = root / 'directhost/src/test/hid/run.py'
spec = importlib.util.spec_from_file_location('source_blocks',parser)
extract = importlib.util.module_from_spec(spec);spec.loader.exec_module(extract)
owner_path = gl / 'ReceiverSessionOwner.kt'
activity_path = gl / 'ReceiverActivity.kt'
owner_text=owner_path.read_text();activity_text=activity_path.read_text()
cuts=[]
def cut(text,pattern,origin):
    block=extract.block(text,pattern)
    cuts.append({'origin':str(origin.relative_to(root)),'text':block,
                 'sha256':hashlib.sha256(block.encode()).hexdigest()})
    return block
owner_blocks=[cut(owner_text,r'^    (?:internal )?fun '+name+r'\(',owner_path)
              for name in ['nativeInputs','attach','attachSurface','detach']]
owner_blocks.append(cut(owner_text,r'^    override fun close\(',owner_path))
activity_blocks=[cut(activity_text,r'^        override fun onServiceConnected\(',activity_path)]
activity_blocks += [cut(activity_text,r'^    override fun '+name+r'\(',activity_path)
                    for name in ['onResume','onWindowFocusChanged','onPause','onDestroy']]
usable=next(line for line in activity_text.splitlines() if line.startswith('    private fun usable('))
cuts.append({'origin':str(activity_path.relative_to(root)),'text':usable,'sha256':hashlib.sha256(usable.encode()).hexdigest()})
activity_blocks.append(usable)
boundary=root/'directrg/src/test/mode-routing/NativeStartupBoundary.kt'
generated=out/'NativeStartupCallers.kt'
generated.write_text(boundary.read_text().replace('// ACTUAL_OWNER_METHODS','\n'.join(owner_blocks))
    .replace('// ACTUAL_ACTIVITY_METHODS','\n'.join(activity_blocks)))
files += [generated,root/'directrg/src/test/mode-routing/NativeStartupTest.kt']
files += [gl/(name+'.kt') for name in ['NativePadShellContact','ReceiverContactInput','HeldInputOwner','HeldPointerDispatcher','PadGestureRecognizer']]
provenance=[]
for path in [owner_path,activity_path,boundary,parser]:
    destination=out/'callers'/path.relative_to(root)
    destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(path.read_bytes())
    provenance.append({'path':str(path.relative_to(root)),'snapshot':str(destination.relative_to(out)),
                       'sha256':hashlib.sha256(destination.read_bytes()).hexdigest()})
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
    relative = f.relative_to(root) if f.is_relative_to(root) else Path('generated') / f.name
    snapshot = out / 'source' / relative
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(f.read_bytes())
    inputs.append({'path': str(relative), 'generated': not f.is_relative_to(root), 'snapshot': str(snapshot.relative_to(out)), 'sha256': sha(snapshot)})
java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
cp = os.pathsep.join(map(str, jars))
jar = out / 'hid-v04.jar'
commands = {
    'compile': [java, '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] +
               [str(out / f['snapshot']) for f in inputs],
    'run': [java, '-Xmx256m', '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
            'com.code2hack.eyeprojector.directrg.CoordinateModeRenderingTest',
            'com.code2hack.eyeprojector.directrg.NativeStartupTest'],
}
result = {'scope': 'Actual native Function, raw adapter, held input and overlay/model plus verbatim Owner/Activity startup/lifecycle methods. OS socket/acquisition, presentation and Canvas are host boundaries; no whole Activity/Session or Android/device/optical claim.',
          'inputs': inputs, 'callerProvenance': provenance, 'cuts': cuts, 'runnerSha256': sha(Path(__file__)),
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
