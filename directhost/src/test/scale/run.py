#!/usr/bin/env python3
"""Compile actual common Phone/Home launch sources against controlled host boundaries."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo', type=Path, default=Path.cwd(), help='Exact worktree whose actual sources are compiled')
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--p2-only', action='store_true', help='Run only the independently reported P2 remediation regressions; retain all individual results')
args = parser.parse_args()
suite = Path(__file__).resolve().parent
repo = args.repo.resolve()
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=True)
host = repo / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = repo / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
sources = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt', suite / 'PhoneActivityScenarios.kt', suite / 'ScaleActivityScenarios.kt', suite / 'P2Scenarios.kt']
sources += [host / name for name in (
    'PhoneSessionController.kt', 'SessionController.kt', 'SessionAttemptStore.kt',
    'ProjectionContext.kt', 'SessionUsability.kt', 'ProjectionDisplayState.kt', 'ProjectedHomeActivity.kt', 'HostActivity.kt',
    'apps/ChosenAppStore.kt', 'scale/AppScaleCoordinator.kt', 'scale/AppScaleStore.kt',
    'scale/AppScalePreferences.kt', 'scale/AppScaleActivity.kt',
)]
sources += [protocol / name for name in ('SessionState.kt', 'Wire.kt', 'ActivationControl.kt', 'ReceiverUsability.kt', 'PhoneInput.kt', 'Touch.kt', 'LayoutScale.kt')]
missing = [str(path) for path in sources if not path.is_file()]
if missing:
    parser.error('Missing source files: ' + ', '.join(missing))

cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
dependencies = (
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21',
    'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21',
    'org.jetbrains.kotlin/kotlin-reflect',
    'org.jetbrains/annotations/13.0',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm',
)
jars = [jar for dependency in dependencies for jar in sorted((cache / dependency).rglob('*.jar'))]
if not jars:
    parser.error('Pinned Kotlin compiler dependencies are absent; provision using authorized Gradle checks first')
classpath = os.pathsep.join(map(str, jars))
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
if not java:
    parser.error('Configure the required JDK 17 using JAVA_HOME or PATH')

artifact = output / 'common-launch-regressions.jar'
manifest = {
    'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'worktree': str(repo),
    'suiteMode': 'p2-only' if args.p2_only else 'all',
    'runner': {'path': str(Path(__file__).resolve()), 'sha256': sha256(Path(__file__).resolve())},
    'sources': [{'path': str(path), 'sha256': sha256(path)} for path in sources],
    'toolJars': [{'path': str(path), 'sha256': sha256(path)} for path in jars],
    'compileExit': None, 'scenariosExit': None, 'timedOut': None,
    'scope': 'Actual combined HostActivity/AppScaleActivity/PhoneSessionController/SessionController/ProjectedHomeActivity/scale sources; inert Android widgets/ScalePanelView/protocol, widget-attachment boundary and controlled bootstrap/panel facade doubles; no actual widget publication/actions or Android Binder/UI/gesture/device proof',
    'compositionBoundaries': {
        'phoneController': 'Complete actual combined production source; no substitute controller, core or scale coordinator',
        'widgetAttachment': 'PhoneWidgetUpdates.attach records calls only; no widget observer subscription, launcher publication or widget action delivery',
        'panelFacade': 'Context-bound requestPanelOff compiles the actual controller delegation and returns controlled null; no panel admission or completion proof',
        'androidScheduling': 'Existing host Handler boundary executes posts immediately and retains delayed ticks without automatic delivery; no Android main-loop proof',
    },
}


def persist():
    manifest['finishedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    manifest['sourcesUnchanged'] = all(sha256(Path(source['path'])) == source['sha256'] for source in manifest['sources'])
    manifest['logs'] = [{'path': str(output / name), 'sha256': sha256(output / name)}
                        for name in ('compile.log', 'scenarios.log') if (output / name).is_file()]
    if artifact.is_file():
        manifest['artifact'] = {'path': str(artifact), 'sha256': sha256(artifact)}
    (output / 'result.json').write_text(json.dumps(manifest, indent=2) + '\n')


compile_command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-classpath', classpath,
                   '-d', str(artifact)] + list(map(str, sources))
manifest['compileCommand'] = compile_command
exit_code = 1
try:
    with (output / 'compile.log').open('w') as log:
        compiled = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    manifest['compileExit'] = compiled.returncode
    exit_code = compiled.returncode
    if compiled.returncode:
        print((output / 'compile.log').read_text())
    elif not all(sha256(Path(source['path'])) == source['sha256'] for source in manifest['sources']):
        exit_code = 2
        manifest['executionSkipped'] = 'Source changed during compilation; result cannot bind this source snapshot'
        print(manifest['executionSkipped'])
    else:
        run_command = [java, '-cp', str(artifact) + os.pathsep + classpath,
                       'com.code2hack.eyeprojector.directhost.commonharness.P2ScenariosKt' if args.p2_only else
                       'com.code2hack.eyeprojector.directhost.commonharness.ScenariosKt']
        manifest['scenariosCommand'] = run_command
        with (output / 'scenarios.log').open('w') as log:
            run = subprocess.run(run_command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
        manifest['scenariosExit'] = run.returncode
        scenario_text = (output / 'scenarios.log').read_text()
        manifest['p2CaseResults'] = [
            {'outcome': line.split(' ', 1)[0], 'detail': line.split(' ', 1)[1]}
            for line in scenario_text.splitlines()
            if line.startswith(('PASS EP12-', 'FAIL EP12-', 'ERROR EP12-'))
        ]
        manifest['p2Summary'] = next((line for line in scenario_text.splitlines()
                                      if line.startswith('P2 RESULT ')), None)
        exit_code = run.returncode
        print(scenario_text)
except subprocess.TimeoutExpired as error:
    manifest['timedOut'] = 'compile' if manifest['compileExit'] is None else 'scenarios'
    manifest['timeoutSeconds'] = error.timeout
    exit_code = 124
finally:
    persist()
if not manifest['sourcesUnchanged']:
    exit_code = 2
sys.exit(exit_code)
