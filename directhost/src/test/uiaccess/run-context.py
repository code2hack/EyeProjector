#!/usr/bin/env python3
"""Check actual HostBootstrap context API against existing host Binder doubles after SDK compile."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
actual = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
fixture = suite.parent / 'adapter/doubles'
out = a.output_dir.resolve(); out.mkdir(parents=True, exist_ok=True)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect',
        'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars = [str(j) for d in deps for j in sorted((cache / d).rglob('*.jar'))]
host = root / 'directhost/build/tmp/kotlin-classes/debug'
cp = os.pathsep.join(map(str, [host, root / 'directhost/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes',
    root / 'protocol/build/tmp/kotlin-classes/debug', Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platforms/android-35/android.jar', *jars]))
sources = [actual / 'HostBootstrap.kt']
sources += [fixture / n for n in ['Context.kt', 'AtomicFile.kt', 'Json.kt', 'Os.kt', 'Annotation.kt', 'PackageManager.kt', 'Service.kt', 'Shizuku.kt']]
sources += [suite / 'doubles/AndroidClock.kt', suite / 'ContextScenarios.kt']
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
jar = out / 'ui-context-checks.jar'
command = [java, '-cp', os.pathsep.join(jars), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-Xfriend-paths=' + str(host),
    '-jvm-target', '17', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(jar), *map(str, sources)]
with (out / 'compile.log').open('w') as log:
    compiled = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
run = None
if compiled.returncode == 0:
    with (out / 'scenarios.log').open('w') as log:
        run = subprocess.run([java, '-cp', str(jar) + os.pathsep + cp,
            'com.code2hack.eyeprojector.directhost.ContextScenariosKt'], stdout=log, stderr=subprocess.STDOUT, timeout=60)
result = {'compileExit': compiled.returncode, 'scenariosExit': run.returncode if run else None,
    'scope': 'Actual HostBootstrap source with modeled Android/Shizuku/Binder, using current SDK-compiled dependency classes; no device or production permission proof',
    'command': command, 'sources': [{'path': str(s), 'sha256': hashlib.sha256(s.read_bytes()).hexdigest()} for s in sources],
    'compiledDependencies': [{'path': str(s), 'sha256': hashlib.sha256(s.read_bytes()).hexdigest()}
        for base in [host, root / 'protocol/build/tmp/kotlin-classes/debug'] for s in sorted(base.rglob('*.class'))]}
(out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print((out / 'compile.log').read_text())
if run: print((out / 'scenarios.log').read_text())
raise SystemExit(compiled.returncode or (run.returncode if run else 1))
