#!/usr/bin/env python3
"""Cached, bounded JVM checks of actual notification-probe instrumentation helpers.

Android, listener, policy and launch observations are explicit doubles. This is
helper/diagnostic verification, never Android notification-delivery/device proof.
"""

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
import xml.etree.ElementTree as ET


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def bounded(argv, log_path, seconds):
    seconds = min(seconds, remaining_budget())
    with log_path.open('w') as log:
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
parser.add_argument('--output-dir', type=Path, required=True, help='New private directory for this closed run')
parser.add_argument('--correction136-only', action='store_true', help='Run only the three correction136 regression scenarios')
parser.add_argument('--helper-source-root', type=Path, help='Read saved Checks/Report snapshots from this directory; never import them into the repository')
args = parser.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
helper = (args.helper_source_root.resolve() if args.helper_source_root else
          root / 'directhost/src/androidTest/kotlin/com/code2hack/eyeprojector/directhost/notifications')
sources = [helper / 'NotificationProbeChecks.kt', helper / 'NotificationProbeReport.kt', suite / 'Scenarios.kt']
sources += sorted((suite / 'doubles').glob('*.kt'))
missing = [str(source) for source in sources if not source.is_file()]
if missing:
    parser.error('Actual helper/test sources must exist before execution: ' + ', '.join(missing))
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=False)
record = {
    'startedUtc': now(), 'finishedUtc': None,
    'scope': 'Actual androidTest NotificationProbeChecks/NotificationProbeReport against explicit Android and production observation doubles; no Android/system notification/activation/device proof',
    'runner': {'path': str(Path(__file__).resolve()), 'sha256': digest(Path(__file__).resolve()), 'mode': stat.S_IMODE(Path(__file__).stat().st_mode)},
    'sources': [], 'auxiliaryInputs': [], 'dependencies': [], 'compile': None, 'tests': None,
    'scenarioSelection': 'correction136-only' if args.correction136_only else 'all-current-helper',
    'helperSourceOverride': str(helper) if args.helper_source_root else None,
}
exit_code = 1
deadline = time.monotonic() + 115
record['overallBudgetSeconds'] = 115
try:
    base = 'b2ed84ee52b7faf65f8273bc11dc458a0e5b22ae'
    auxiliary = [root / 'directhost/src/main/AndroidManifest.xml', root / 'directhost/src/debug/AndroidManifest.xml',
                 root / 'directhost/src/androidTest/kotlin/com/code2hack/eyeprojector/directhost/ChosenAppsInstrumentation.kt']
    base_bytes = {}
    for index, path in enumerate(auxiliary):
        relative = path.relative_to(root).as_posix()
        original = subprocess.check_output(['git', 'show', base + ':' + relative], cwd=root, timeout=min(5, remaining_budget()))
        base_bytes[relative] = original
        snapshot = output / 'auxiliary' / str(index) / path.name
        snapshot.parent.mkdir(parents=True)
        snapshot.write_bytes(path.read_bytes())
        snapshot.chmod(stat.S_IMODE(path.stat().st_mode))
        original_snapshot = snapshot.with_suffix(snapshot.suffix + '.base')
        original_snapshot.write_bytes(original)
        record['auxiliaryInputs'].append({'path': str(path), 'snapshot': str(snapshot), 'sha256': digest(snapshot),
            'mode': stat.S_IMODE(path.stat().st_mode), 'base': base, 'baseSnapshot': str(original_snapshot), 'baseSha256': digest(original_snapshot)})
    main_manifest, debug_manifest, instrumentation = auxiliary
    if main_manifest.read_bytes() != base_bytes[main_manifest.relative_to(root).as_posix()]:
        raise RuntimeError('Production main manifest changed from the assigned base')
    android = '{http://schemas.android.com/apk/res/android}'
    current = ET.parse(debug_manifest).getroot()
    original = ET.fromstring(base_bytes[debug_manifest.relative_to(root).as_posix()])
    permissions = current.findall('uses-permission')
    if len(permissions) != 1 or permissions[0].attrib != {android + 'name': 'android.permission.POST_NOTIFICATIONS'}:
        raise RuntimeError('Debug manifest must declare exactly the allocated POST_NOTIFICATIONS permission')
    def shape(element):
        return element.tag, sorted(element.attrib.items()), (element.text or '').strip(), [shape(child) for child in element]
    current.remove(permissions[0])
    if shape(current) != shape(original):
        raise RuntimeError('Debug snapshot service or other original manifest content changed')
    branch = '''                "notification-probe" -> {
                    // This diagnostic reports its stages; readiness/event receipt is not feature PASS.
                    com.code2hack.eyeprojector.directhost.notifications.NotificationProbeChecks.run(this, targetContext, arguments, result)
                    finish(if (result.getString("result") == "DIAGNOSTIC_ERROR") Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
                    return
                }
'''
    instrumented = instrumentation.read_text()
    if instrumented.count(branch) != 1 or instrumented.replace(branch, '').encode() != base_bytes[instrumentation.relative_to(root).as_posix()]:
        raise RuntimeError('Notification operation must finish/return early while preserving every other operation byte')
    record['staticChecks'] = {'mainManifestUnchangedFromBase': True, 'debugPermissionOnlyAndOriginalServicePreserved': True,
                            'newOperationEarlyReturnAndOtherInstrumentationBytesUnchanged': True}
    java = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin/java')
    if not java.is_file():
        raise RuntimeError('Required cached JDK17 java executable is unavailable')
    record['java'] = {'path': str(java.resolve()), 'sha256': digest(java.resolve()), 'mode': stat.S_IMODE(java.resolve().stat().st_mode)}
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    dependencies = (
        'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21',
        'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21',
        'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
        'org.jetbrains/annotations/13.0',
        'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
    )
    jars = []
    for dependency in dependencies:
        matches = sorted((cache / dependency).rglob('*.jar'))
        if len(matches) != 1:
            raise RuntimeError('Expected one cached jar for ' + dependency + '; no download attempted')
        jars.extend(matches)
    record['dependencies'] = [{'path': str(path), 'sha256': digest(path), 'mode': stat.S_IMODE(path.stat().st_mode)} for path in jars]
    snapshots = []
    for index, source in enumerate(sources):
        snapshot = output / 'sources' / str(index) / source.name
        snapshot.parent.mkdir(parents=True)
        snapshot.write_bytes(source.read_bytes())
        snapshots.append(snapshot)
        snapshot.chmod(stat.S_IMODE(source.stat().st_mode))
        record['sources'].append({'path': str(source), 'snapshot': str(snapshot), 'sha256': digest(snapshot), 'mode': stat.S_IMODE(source.stat().st_mode)})
    classpath = os.pathsep.join(map(str, jars))
    artifact = output / 'notification-probe-tests.jar'
    compiler = [str(java), '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                '-d', str(artifact)] + list(map(str, snapshots))
    tests = [str(java), '-cp', str(artifact) + os.pathsep + classpath,
             'com.code2hack.eyeprojector.directhost.notifications.probetest.ScenariosKt']
    if args.correction136_only:
        tests.append('--correction136-only')
    record['compileArgv'] = compiler
    record['testsArgv'] = tests
    record['argvSha256'] = hashlib.sha256(json.dumps([compiler, tests], separators=(',', ':')).encode()).hexdigest()
    (output / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
    record['javaVersion'] = bounded([str(java), '-version'], output / 'java-version.log', 5)
    if record['javaVersion']['exit']:
        raise RuntimeError('JDK17 version invocation failed')
    record['compile'] = bounded(compiler, output / 'compile.log', 85)
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
    record['sourcesUnchanged'] = all(Path(item['path']).is_file() and digest(Path(item['path'])) == item['sha256'] and stat.S_IMODE(Path(item['path']).stat().st_mode) == item['mode'] for item in record['sources'] + record['auxiliaryInputs'])
    record['runnerUnchanged'] = digest(Path(__file__).resolve()) == record['runner']['sha256'] and stat.S_IMODE(Path(__file__).stat().st_mode) == record['runner']['mode']
    record['logs'] = [{'path': str(path), 'sha256': digest(path)} for path in sorted(output.glob('*.log'))]
    record['exit'] = exit_code if record['sourcesUnchanged'] and record['runnerUnchanged'] else 2
    (output / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
sys.exit(record['exit'])
