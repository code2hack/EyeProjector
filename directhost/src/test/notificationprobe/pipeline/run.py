#!/usr/bin/env python3
"""One bounded cached actual-source pipeline-observer check; no SDK/Gradle/device."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def bounded(argv, path, seconds):
    seconds = min(seconds, remaining_budget())
    with path.open('w') as log:
        process = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=seconds)
            return {'pid': process.pid, 'exit': code, 'timeout': False, 'closed': True}
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return {'pid': process.pid, 'exit': process.returncode, 'timeout': True, 'closed': True}


def remaining_budget():
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('Focused overall execution budget exhausted')
    return remaining


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[4]
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
glass = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
activation = root / 'directhost/src/test/activation'
files = sorted((host / 'activation').glob('*.kt'))
files += [path for path in sorted((host / 'notifications').glob('*.kt')) if path.name != 'NotificationAccessPanel.kt']
files += [host / 'apps' / name for name in ('ChosenApps.kt', 'ChosenAppStore.kt')]
files += [host / name for name in ('SessionController.kt', 'SessionAttemptStore.kt', 'ProjectionContext.kt', 'ProjectionDisplayState.kt', 'SessionUsability.kt')]
files += [protocol / name for name in ('SessionControl.kt', 'SessionState.kt', 'ActivationControl.kt', 'ReceiverUsability.kt', 'Wire.kt', 'PhoneInput.kt', 'Touch.kt', 'LayoutScale.kt')]
files += [glass / name for name in ('ReceiverActivationStore.kt', 'ReceiverTaskRegistry.kt', 'PairedSessionClient.kt')]
files += [host / name for name in ('PhoneSessionController.kt', 'HostBootstrap.kt', 'HostForgetCompletion.kt', 'BootstrapSessionFiles.kt', 'PanelPower.kt', 'input/PhoneHidRoute.kt', 'LaunchOperationDiagnostics.kt', 'scale/AppScaleCoordinator.kt', 'scale/AppScalePreferences.kt', 'scale/AppScaleStore.kt')]
files += [activation.parent / 'adapter/doubles' / name for name in ('AtomicFile.kt', 'Context.kt', 'Json.kt', 'Os.kt', 'Annotation.kt', 'PackageManager.kt', 'Service.kt', 'Shizuku.kt', 'Log.kt')]
files += [activation / 'composed/Transport.kt', activation / 'composed/Widgets.kt'] + sorted((activation / 'producer').glob('*.kt'))
files += [host / 'hid' / (name + '.kt') for name in ('PhoneHidBindings', 'PhoneHidController', 'PhoneHidRuntime', 'PhoneHidKeyService')]
files += [activation.parent / 'hid/doubles' / name for name in ('Accessibility.kt', 'AccessibilityEvent.kt', 'InputManager.kt', 'KeyEvent.kt', 'Settings.kt', 'PackageManager.kt')]
files += [host / 'activation/snapshot' / name for name in ('NativeSnapshotRegion.kt', 'SnapshotWord.kt', 'PhoneHidSnapshotLease.kt')]
files += sorted((activation.parent / 'snapshot/transport/doubles').glob('*.kt'))
files += [suite / 'Scenarios.kt']
missing = [str(path) for path in files if not path.is_file()]
if missing:
    parser.error('Missing actual source/fixture: ' + ', '.join(missing))
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=False)
runner_snapshot = output / 'runner.py'
runner_snapshot.write_bytes(Path(__file__).read_bytes())
runner_snapshot.chmod(stat.S_IMODE(Path(__file__).stat().st_mode))
record = {'startedUtc': now(), 'scope': 'Actual owner/policy/carrier/types/facade/Bootstrap/coordinator/canonical adapters; explicit Android/Shizuku/Binder/JSON/TLS/native and adversarial delivery/settlement boundaries; no existing-suite counts or device proof',
          'runner': {'path': str(Path(__file__).resolve()), 'snapshot': str(runner_snapshot), 'sha256': digest(Path(__file__).resolve()), 'mode': stat.S_IMODE(Path(__file__).stat().st_mode)},
          'sources': [], 'dependencies': [], 'compile': None, 'tests': None}
exit_code = 1
deadline = time.monotonic() + 115
record['overallBudgetSeconds'] = 115
try:
    java = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin/java')
    record['java'] = {'path': str(java.resolve()), 'sha256': digest(java.resolve()), 'mode': stat.S_IMODE(java.resolve().stat().st_mode)}
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    coordinates = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'), ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'), ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'), ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'), ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), ('org.jetbrains', 'annotations', '13.0'), ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3')]
    jars = []
    for group, name, version in coordinates:
        matches = sorted((cache / group / name / version).glob(f'*/{name}-{version}.jar'))
        if len(matches) != 1:
            raise RuntimeError('Expected one cached dependency: ' + name)
        jars += matches
    record['dependencies'] = [{'path': str(path), 'sha256': digest(path), 'mode': stat.S_IMODE(path.stat().st_mode)} for path in jars]
    compiled = []
    for index, source in enumerate(files):
        snapshot = output / 'sources' / str(index) / source.name
        snapshot.parent.mkdir(parents=True)
        snapshot.write_bytes(source.read_bytes())
        mode = stat.S_IMODE(source.stat().st_mode)
        snapshot.chmod(mode)
        record['sources'].append({'path': str(source), 'snapshot': str(snapshot), 'sha256': digest(snapshot), 'mode': mode})
        compiled.append(str(snapshot))
    classpath = os.pathsep.join(map(str, jars))
    artifact = output / 'pipeline-observer-tests.jar'
    compiler = [str(java), '-Xmx2g', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', str(artifact)] + compiled
    tests = [str(java), '-cp', str(artifact) + os.pathsep + classpath, 'com.code2hack.eyeprojector.directhost.notifications.pipelinetest.ScenariosKt']
    record.update({'compileArgv': compiler, 'testsArgv': tests, 'argvSha256': hashlib.sha256(json.dumps([compiler, tests], separators=(',', ':')).encode()).hexdigest()})
    (output / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
    record['javaVersion'] = bounded([str(java), '-version'], output / 'java-version.log', 5)
    if record['javaVersion']['exit']:
        raise RuntimeError('JDK invocation failed')
    record['compile'] = bounded(compiler, output / 'compile.log', 55)
    exit_code = 124 if record['compile']['timeout'] else record['compile']['exit']
    if not exit_code:
        record['tests'] = bounded(tests, output / 'tests.log', 20)
        exit_code = 124 if record['tests']['timeout'] else record['tests']['exit']
        lines = (output / 'tests.log').read_text()
        record['namedResults'] = [line for line in lines.splitlines() if line.startswith(('PASS ', 'FAIL ', 'RESULT '))]
        print(lines, end='')
    else:
        print((output / 'compile.log').read_text(), end='')
    if artifact.is_file():
        record['artifact'] = {'path': str(artifact), 'sha256': digest(artifact)}
except Exception as error:
    record['error'] = str(error)
    print(str(error), file=sys.stderr)
finally:
    record['finishedUtc'] = now()
    record['sourcesUnchanged'] = all(Path(item['path']).is_file() and digest(Path(item['path'])) == item['sha256'] and stat.S_IMODE(Path(item['path']).stat().st_mode) == item['mode'] for item in record['sources'])
    record['runnerUnchanged'] = digest(Path(__file__).resolve()) == record['runner']['sha256'] and stat.S_IMODE(Path(__file__).stat().st_mode) == record['runner']['mode']
    record['logs'] = [{'path': str(path), 'sha256': digest(path)} for path in sorted(output.glob('*.log'))]
    record['exit'] = exit_code if record['sourcesUnchanged'] and record['runnerUnchanged'] else 2
    (output / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
sys.exit(record['exit'])
