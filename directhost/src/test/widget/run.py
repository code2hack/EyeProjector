#!/usr/bin/env python3
"""Exercise actual controller/widget Android entry sources against explicitly modeled framework/host transport."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
output = a.output_dir.resolve()
output.mkdir(parents=True, exist_ok=True)
suite = Path(__file__).resolve().parent
root = suite.parents[3]
actual = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependencies = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
                'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect',
                'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars = [str(j) for d in dependencies for j in sorted((cache / d).rglob('*.jar'))]
classpath = os.pathsep.join([str(root / 'protocol/build/tmp/kotlin-classes/debug')] + jars)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
resources = root / 'directhost/src/main/res'
strings = {e.attrib['name']: e.text for e in ET.parse(resources / 'values/phone_widget.xml').getroot()}
ids = ['phone_widget_status', 'phone_widget_primary', 'phone_widget_panel', 'phone_widget_panel_status']
# These constants/texts come from the production XML; record both as compiler inputs.
generated = output / 'R.kt'
lines = ['package com.code2hack.eyeprojector.directhost', 'object R {', 'object string {']
for i, name in enumerate(strings, 1): lines.append(f'const val {name} = {i}')
lines += ['}', 'object id {'] + [f'const val {name} = {i}' for i, name in enumerate(ids, 100)]
lines += ['}', 'object layout { const val phone_widget = 200 }', 'val strings = mapOf(']
lines += [f'{i} to {json.dumps(value)},' for i, value in enumerate(strings.values(), 1)]
lines += [')', '}']
generated.write_text('\n'.join(lines) + '\n')
sources = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt', generated]
sources += sorted((actual / 'widget').glob('*.kt'))
sources += [actual / n for n in ['PhoneSessionController.kt', 'SessionController.kt', 'SessionAttemptStore.kt', 'PanelPower.kt', 'ProjectionContext.kt']]
sources += [root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/SessionState.kt']
inputs = sources + [resources / n for n in ['values/phone_widget.xml', 'layout/phone_widget.xml', 'xml/phone_widget_info.xml']]
archive = output / 'compiled-sources'
archive.mkdir(exist_ok=True)
for i, source in enumerate(inputs): (archive / f'{i:02d}-{source.name}').write_bytes(source.read_bytes())
identity = [{'path': str(s), 'sha256': hashlib.sha256(s.read_bytes()).hexdigest()} for s in inputs]
(output / 'compiler-inputs.json').write_text(json.dumps(identity, indent=2) + '\n')
jar = output / 'widget-regressions.jar'
command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
           '-classpath', classpath, '-d', str(jar)] + list(map(str, sources))
with (output / 'compile.log').open('w') as log:
    compiled = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
if compiled.returncode:
    print((output / 'compile.log').read_text())
    raise SystemExit(compiled.returncode)
with (output / 'scenarios.log').open('w') as log:
    run = subprocess.run([java, '-cp', str(jar) + os.pathsep + classpath,
                          'com.code2hack.eyeprojector.directhost.widget.ScenariosKt'],
                         stdout=log, stderr=subprocess.STDOUT, timeout=60)
(output / 'result.json').write_text(json.dumps({'compileExit': compiled.returncode, 'scenariosExit': run.returncode,
    'inputs': identity, 'scope': 'Actual PhoneSessionController/SessionController/ledger and widget provider/Activity/RemoteViews routing; modeled framework, HostBootstrap and peer transport; no physical proof'}, indent=2) + '\n')
print((output / 'scenarios.log').read_text())
raise SystemExit(run.returncode)
