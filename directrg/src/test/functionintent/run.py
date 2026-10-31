#!/usr/bin/env python3
"""Compile actual Function sources with cached Kotlin/JUnit; no Gradle or SDK operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[4]
out = a.output_dir.resolve()
if out.exists() and any(out.iterdir()):
    p.error('Choose a new empty evidence directory')
out.mkdir(parents=True, exist_ok=True)
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
sources = [protocol / n for n in ('Wire.kt', 'Touch.kt', 'PhoneInput.kt', 'ReceiverUsability.kt', 'ActivationControl.kt', 'CameraFeed.kt', 'FunctionIntent.kt')]
sources += [root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/FunctionGestureRecognizer.kt',
            root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/FunctionGestureRecognizerTest.kt']
sources += [root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg' / n
            for n in ('NativeFunctionInput.kt', 'HidKeyInput.kt', 'HidBindings.kt', 'HidDecisionTrace.kt', 'PadGestureRecognizer.kt', 'HeadPointerModel.kt', 'HeadOrientation.kt', 'HeldInputOwner.kt', 'HeldPointerDispatcher.kt')]
sources += [root / 'directrg/src/test/functionadapter/doubles' / n for n in ('Android.kt', 'Activity.kt', 'Annotation.kt', 'Clock.kt')]
sources += [root / 'directrg/src/test/hidframework/doubles' / n for n in ('Point.kt', 'Rect.kt', 'Log.kt')]
sources += [root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/HidKeyInputTest.kt',
            root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/HidAdmissionReentrancyTest.kt',
            root / 'directrg/src/test/functionintent/NativeFunctionIntentTest.kt',
            root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/input/PhoneInputMerger.kt',
            root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/input/FunctionWork.kt',
            root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/PhoneTouchDispatcher.kt',
            root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/PhoneInputMergerTest.kt',
            root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/input/FunctionWorkTest.kt']
sources += [root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost' / n for n in
            ('hid/PhoneHidController.kt', 'hid/PhoneHidBindings.kt', 'input/PhoneHidRoute.kt',
             'activation/PhoneHidActivation.kt', 'activation/PhoneHidDisposition.kt',
             'activation/PhoneActivationOwner.kt', 'activation/HidWakeRequest.kt',
             'activation/LaunchCommitPermit.kt')]
sources += [root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidFunctionCompositionTest.kt',
            root / 'directrg/src/test/functionintent/ProjectionContextBoundary.kt']
tests = ['com.code2hack.eyeprojector.directrg.FunctionGestureRecognizerTest',
         'com.code2hack.eyeprojector.directrg.HidKeyInputTest',
         'com.code2hack.eyeprojector.directrg.HidAdmissionReentrancyTest',
         'com.code2hack.eyeprojector.directrg.NativeFunctionIntentTest',
         'com.code2hack.eyeprojector.directhost.PhoneInputMergerTest',
         'com.code2hack.eyeprojector.directhost.input.FunctionWorkTest',
         'com.code2hack.eyeprojector.directhost.hid.PhoneHidFunctionCompositionTest']
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect',
        'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm',
        'junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3']
jars = [str(j) for d in deps for j in sorted((cache / d).rglob('*.jar'))]
classpath = os.pathsep.join(jars)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
manifest = []
inputs = []
for source in sources:
    data = source.read_bytes()
    snapshot = out / 'sources' / source.relative_to(root)
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(data)
    inputs.append(str(snapshot))
    manifest.append({'path':str(source.relative_to(root)), 'sha256':hashlib.sha256(data).hexdigest()})
(out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
jar = out / 'function-tests.jar'
compile_command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                   '-jvm-target', '17', '-classpath', classpath, '-d', str(jar)] + inputs
with (out / 'compile.log').open('w') as log:
    compiled = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
run = None
if compiled.returncode == 0:
    with (out / 'tests.log').open('w') as log:
        run = subprocess.run([java, '-cp', str(jar) + os.pathsep + classpath, 'org.junit.runner.JUnitCore'] + tests,
                             stdout=log, stderr=subprocess.STDOUT, timeout=60)
result = {'compileExit':compiled.returncode, 'testExit':run.returncode if run else None,
          'scope':'Actual Function sources on host; modeled scheduler/reports, no firmware/device proof',
          'compilerCommand':compile_command, 'tests':tests}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / ('tests.log' if run else 'compile.log')).read_text())
sys.exit(compiled.returncode or (run.returncode if run else 1))
