#!/usr/bin/env python3
"""Run archived actual widget sources with modeled boundaries and fully bound compiler inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
p.add_argument('--source-root', type=Path, help='Exact production tree; tests always come from this runner checkout')
p.add_argument('--suite', choices=['standard', 'remediation', 'bootstrap-init', 'cross-feature', 'activation'], default='standard')
a = p.parse_args()
output = a.output_dir.resolve()
output.mkdir(parents=True, exist_ok=False)
suite = Path(__file__).resolve().parent
root = suite.parents[3]
production = a.source_root.resolve() if a.source_root else root
actual = production / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependency_names = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
                    'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect',
                    'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
archive_deps = output.parent / 'dependencies'
archive_deps.mkdir(exist_ok=True)
deps = []
for name in dependency_names:
    for original in sorted((cache / name).rglob('*.jar')):
        digest = hashlib.sha256(original.read_bytes()).hexdigest()
        destination = archive_deps / (digest + '.jar')
        if not destination.exists(): shutil.copy2(original, destination)
        assert hashlib.sha256(destination.read_bytes()).hexdigest() == digest
        deps.append({'originalPath': str(original), 'archive': str(destination), 'sha256': digest, 'bytes': original.stat().st_size})
if not deps: p.error('Kotlin compiler dependencies must already be provisioned')
classpath = os.pathsep.join(d['archive'] for d in deps)
java = Path(os.environ['JAVA_HOME']) / 'bin/java'
resources = production / 'directhost/src/main/res'
generated = output / 'R.kt'
strings = {e.attrib['name']: e.text for e in ET.parse(resources / 'values/phone_widget.xml').getroot()}
ids = ['phone_widget_status', 'phone_widget_primary', 'phone_widget_panel', 'phone_widget_panel_status']
lines = ['package com.code2hack.eyeprojector.directhost', 'object R {', 'object string {']
lines += [f'const val {name} = {i}' for i, name in enumerate(strings, 1)]
lines += ['}', 'object id {'] + [f'const val {name} = {i}' for i, name in enumerate(ids, 100)]
lines += ['}', 'object layout { const val phone_widget = 200 }', 'val strings = mapOf(']
lines += [f'{i} to {json.dumps(value)},' for i, value in enumerate(strings.values(), 1)]
period = ET.parse(resources / 'xml/phone_widget_info.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}updatePeriodMillis']
lines += [')', '}', f'object WidgetDescriptor {{ const val updatePeriodMillis = {period}L }}']
generated.write_text('\n'.join(lines) + '\n')
protocol = production / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
if a.suite == 'bootstrap-init':
    adapter = suite.parent / 'adapter/doubles'
    sources = [adapter / n for n in ['Context.kt', 'AtomicFile.kt', 'Json.kt', 'Annotation.kt', 'Service.kt', 'Shizuku.kt']]
    sources += [suite / 'bootstrap/Os.kt', suite / 'bootstrap/PackageManager.kt', suite / 'BootstrapInitializationScenarios.kt']
    sources += [actual / n for n in ['HostBootstrap.kt', 'HostForgetCompletion.kt', 'BootstrapSessionFiles.kt', 'PanelPower.kt', 'ProjectionContext.kt', 'SessionUsability.kt', 'ProjectionDisplayState.kt']]
    entry = 'com.code2hack.eyeprojector.directhost.BootstrapInitializationScenariosKt'
else:
    scenario = {'standard': 'Scenarios.kt', 'remediation': 'RemediationScenarios.kt', 'cross-feature': 'CrossFeatureScenarios.kt', 'activation':'ActivationScenarios.kt'}[a.suite]
    sources = sorted((suite / 'doubles').glob('*.kt')) + [suite / scenario, generated]
    sources += sorted((actual / 'widget').glob('*.kt'))
    sources += [actual / n for n in ['PhoneSessionController.kt', 'SessionController.kt', 'SessionAttemptStore.kt', 'PanelPower.kt', 'ProjectionContext.kt', 'SessionUsability.kt', 'ProjectionDisplayState.kt',
                                    'scale/AppScaleCoordinator.kt', 'scale/AppScalePreferences.kt', 'scale/AppScaleStore.kt']]
    sources += sorted((actual / 'activation').glob('*.kt'))
    sources += [protocol / 'SessionState.kt', protocol / 'SessionControl.kt']
    entry = 'com.code2hack.eyeprojector.directhost.widget.' + {'standard': 'ScenariosKt', 'remediation': 'RemediationScenariosKt', 'cross-feature': 'CrossFeatureScenariosKt', 'activation':'ActivationScenariosKt'}[a.suite]
sources += [actual / 'input/PhoneHidRoute.kt']
sources += [protocol / n for n in ['Wire.kt', 'ActivationControl.kt', 'ReceiverUsability.kt', 'PhoneInput.kt', 'Touch.kt', 'LayoutScale.kt']]
inputs = sources + [resources / n for n in ['values/phone_widget.xml', 'layout/phone_widget.xml', 'xml/phone_widget_info.xml']]
compiled = []
identity = []
archive = output / 'compiled-sources'
for index, source in enumerate(inputs):
    destination = archive / f'{index:02d}' / source.name
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())
    identity.append({'path': str(source), 'archive': str(destination), 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
                     'usage': 'compiled Kotlin input' if index < len(sources) else 'resource provenance; see resourceUsage'})
    if index < len(sources): compiled.append(destination)
(output / 'compiler-inputs.json').write_text(json.dumps(identity, indent=2) + '\n')
jar = output / 'widget-regressions.jar'
compile_command = [str(java), '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                   '-classpath', classpath, '-d', str(jar)] + list(map(str, compiled))
run_command = [str(java), '-cp', str(jar) + os.pathsep + classpath, entry]
java_home = java.parent.parent
runtime = [{'path': str(f), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()} for f in [java, java_home / 'release', java_home / 'lib/modules']]
command = {'compile': compile_command, 'run': run_command, 'dependencies': deps, 'runtime': runtime,
           'javaVersion': subprocess.run([str(java), '-version'], text=True, capture_output=True, check=True).stderr,
           'runtimeQualification': 'Compiler/runtime jars archived; JDK executable/release/modules hashed, native JDK/OS runtime files are not archived.'}
(output / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
result = {'suite': a.suite, 'productionRoot': str(production), 'compileExit': None, 'scenariosExit': None,
          'inputs': identity,
          'resourceUsage': {
              'values/phone_widget.xml': 'Parsed strings generate compiled fixture R.kt' if a.suite != 'bootstrap-init' else 'Parsed to uncompiled fixture R.kt; provenance only for bootstrap-init',
              'layout/phone_widget.xml': 'Hashed/copied provenance only; no XML inflation/layout execution in these host suites',
              'xml/phone_widget_info.xml': 'Parsed updatePeriodMillis into fixture WidgetDescriptor; asserted by remediation descriptor case only' if a.suite != 'bootstrap-init' else 'Parsed to uncompiled fixture descriptor; provenance only for bootstrap-init',
          },
          'scope': 'Actual combined PhoneSessionController/widget/scale coordinator/preferences/store and value types for standard/remediation/cross-feature; actual HostBootstrap for bootstrap-init. Android preferences/bootstrap/render/peer boundaries modeled; no physical proof'}
with (output / 'compile.log').open('w') as log:
    c = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
result['compileExit'] = c.returncode
if c.returncode == 0:
    with (output / 'scenarios.log').open('w') as log:
        r = subprocess.run(run_command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    result['scenariosExit'] = r.returncode
    content = (output / 'scenarios.log').read_text()
    result['passCases'] = sum(line.startswith('PASS ') for line in content.splitlines())
    result['failedCases'] = sum(line.startswith('FAIL ') for line in content.splitlines())
    print(content)
else: print((output / 'compile.log').read_text())
(output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
raise SystemExit(c.returncode if c.returncode else result['scenariosExit'])
