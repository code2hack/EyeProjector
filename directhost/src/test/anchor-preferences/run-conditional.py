#!/usr/bin/env python3
"""Run actual Anchor preference/storage/change JUnit source with a cached host compiler, without Gradle/SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--dependency-manifest',type=Path,required=True)
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
actual = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
frozen = json.loads(args.dependency_manifest.read_text())
jars=[]
for entry in frozen['jars']:
    assert hashlib.sha256(Path(entry['path']).read_bytes()).hexdigest()==entry['sha256']
    if entry['kind']=='compiler-dependency': jars.append(entry['path'])
classpath = os.pathsep.join(jars)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
sources = sorted((actual / 'anchor/preferences').glob('*.kt'))
sources += [actual / 'anchor/target/AnchorTargetDescriptor.kt', actual / 'apps/ChosenAppStore.kt',
            actual / 'scale/AppScaleStore.kt', actual / 'notifications/AppNotificationStore.kt',
            protocol / 'LayoutScale.kt']
sources += [protocol / (name + '.kt') for name in
            ['Wire', 'Touch', 'PhoneInput', 'ReceiverUsability', 'CameraFeed', 'ActivationControl', 'FunctionIntent', 'AnchorMessage']]
sources += sorted((suite / 'doubles').glob('*.kt'))
sources += [suite / 'AppAnchorPreferencesHostTest.kt', suite / 'ConditionalRestoreTest.kt',
            suite.parent / 'kotlin/com/code2hack/eyeprojector/directhost/anchor/preferences/AppAnchorStoreTest.kt']
out = args.output_dir.resolve()
if (out/'result.json').exists(): parser.error('Refusing to rewrite retained result')
out.mkdir(parents=True, exist_ok=True)
records=[]
for i,source in enumerate(sources):
    snapshot=out/'sources'/str(i)/source.name;snapshot.parent.mkdir(parents=True,exist_ok=True);snapshot.write_bytes(source.read_bytes())
    records.append({'path':str(source),'snapshot':str(snapshot),'sha256':hashlib.sha256(snapshot.read_bytes()).hexdigest()})
sources=[Path(e['snapshot']) for e in records]
(out/'runner-source.py').write_bytes(Path(__file__).read_bytes())
jar = out / 'anchor-preferences-checks.jar'
command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17', '-no-stdlib', '-no-reflect',
           '-classpath', classpath, '-d', str(jar)] + list(map(str, sources))
with (out / 'compile.log').open('w') as log:
    compiled = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
ran = None
run_command = [java, '-cp', str(jar) + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
               'com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStoreTest',
               'com.code2hack.eyeprojector.directhost.anchor.preferences.ConditionalRestoreTest',
               'com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorPreferencesHostTest']
if compiled.returncode == 0:
    with (out / 'junit.log').open('w') as log:
        ran = subprocess.run(run_command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
result = {'compileExit': compiled.returncode, 'junitExit': ran.returncode if ran else None,
          'scope': 'Actual production preference codec/store/file persistence/Android adapter plus existing chosen/scale/notification models. Android Context/Log are host doubles only; no device/input/UI proof',
          'compilerCommand': command, 'testCommand': run_command,
          'sources': records,
          'runner': {'snapshot':str(out/'runner-source.py'),'sha256':hashlib.sha256((out/'runner-source.py').read_bytes()).hexdigest()},
          'dependencyManifest': {'path':str(args.dependency_manifest),'sha256':hashlib.sha256(args.dependency_manifest.read_bytes()).hexdigest()},
          'dependencies': [{'path': p, 'sha256': hashlib.sha256(Path(p).read_bytes()).hexdigest()} for p in jars]}
if jar.exists():
    result['artifact'] = {'path': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / 'compile.log').read_text())
if ran:
    print((out / 'junit.log').read_text())
raise SystemExit(compiled.returncode or (ran.returncode if ran else 1))
