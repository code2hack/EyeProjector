#!/usr/bin/env python3
"""Bounded cached checks of actual selection/policy and both launch queues; no SDK/device proof."""
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


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def utc():
    return datetime.now(timezone.utc).isoformat()


def source_files(root, current):
    suite = current.parent / 'activation'
    host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
    protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
    glass = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    files = sorted((host/'activation').glob('*.kt'))
    files += [p for p in sorted((host/'notifications').glob('*.kt')) if p.name != 'NotificationAccessPanel.kt']
    files += [host/'apps'/n for n in ['ChosenApps.kt','ChosenAppStore.kt']]
    files += [current/'Scenarios.kt']
    files += [root/'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost'/n for n in
              ['notifications/NotificationEventsTest.kt','notifications/NotificationIngressRuntimeTest.kt',
               'apps/ChosenAppStoreTest.kt','scale/AppScaleCoordinatorTest.kt']]
    files += [host/n for n in ['SessionController.kt','SessionAttemptStore.kt','ProjectionContext.kt','ProjectionDisplayState.kt','SessionUsability.kt']]
    files += [protocol/n for n in ['SessionControl.kt','SessionState.kt','ActivationControl.kt','ReceiverUsability.kt','Wire.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt']]
    files += [glass/n for n in ['ReceiverActivationStore.kt','ReceiverTaskRegistry.kt','PairedSessionClient.kt']]
    files += [host/n for n in ['PhoneSessionController.kt','HostBootstrap.kt','HostForgetCompletion.kt','BootstrapSessionFiles.kt','PanelPower.kt','input/PhoneHidRoute.kt','LaunchOperationDiagnostics.kt',
                              'scale/AppScaleCoordinator.kt','scale/AppScalePreferences.kt','scale/AppScaleStore.kt']]
    files += [suite.parent/'adapter/doubles'/n for n in ['AtomicFile.kt','Context.kt','Json.kt','Os.kt','Annotation.kt','PackageManager.kt','Service.kt','Shizuku.kt','Log.kt']]
    files += [suite/'composed/Transport.kt',suite/'composed/Widgets.kt']+sorted((suite/'producer').glob('*.kt'))
    files += [host/'hid'/(n+'.kt') for n in ['PhoneHidBindings','PhoneHidController','PhoneHidRuntime','PhoneHidKeyService']]
    files += [suite.parent/'hid/doubles'/n for n in ['Accessibility.kt','AccessibilityEvent.kt','InputManager.kt','KeyEvent.kt','Settings.kt','PackageManager.kt']]
    files += [host/'activation/snapshot'/n for n in ['NativeSnapshotRegion.kt','SnapshotWord.kt','PhoneHidSnapshotLease.kt']]
    files += sorted((suite.parent/'snapshot/transport/doubles').glob('*.kt'))
    return files


def group_exists(group):
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, help='Exact production source snapshot; fixtures remain current and separately retained')
    select = parser.add_mutually_exclusive_group()
    select.add_argument('--correction65-only', action='store_true')
    select.add_argument('--observation219-only', action='store_true')
    args = parser.parse_args()
    started = time.monotonic()
    deadline = started + 115
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    current = Path(__file__).resolve().parent
    root = args.source_root.resolve() if args.source_root else current.parents[3]
    runner = Path(__file__).resolve()
    record = {'startedUtc': utc(), 'finishedUtc': None, 'overallBudgetSeconds': 115,
              'inputs': [], 'dependencies': [], 'compileExit': None, 'runExit': None,
              'ownedProcesses': [], 'runner': {'path': str(runner), 'sha256': sha(runner), 'mode': stat.S_IMODE(runner.stat().st_mode)},
              'scope': 'Actual facade/Bootstrap/coordinator/notification policy-owner-runtime/canonical adapters; explicit Android/Shizuku/Binder/JSON/TLS/JNI boundaries. No actual198 cause or Android/device/native/physical proof.',
              'selection': 'observation219-only' if args.observation219_only else 'correction65-only' if args.correction65_only else 'full-current'}
    exit_code = 1

    def bounded(argv, phase, maximum):
        remaining = deadline-time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Overall 115 second cached-check budget exhausted')
        with (out/(phase+'.log')).open('w') as log:
            process = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            item = {'phase': phase, 'pid': process.pid, 'pgid': process.pid, 'startedUtc': utc(),
                    'timeout': False, 'closed': False, 'cleanupActions': []}
            record['ownedProcesses'].append(item)
            try:
                code = process.wait(timeout=min(maximum, remaining))
            except subprocess.TimeoutExpired:
                item['timeout'] = True
                os.killpg(process.pid, signal.SIGKILL)
                item['cleanupActions'].append('SIGKILL_EXACT_OWNED_PROCESS_GROUP_ON_TIMEOUT')
                process.wait()
                code = 124
            except BaseException:
                if group_exists(process.pid):
                    os.killpg(process.pid, signal.SIGKILL)
                    item['cleanupActions'].append('SIGKILL_EXACT_OWNED_PROCESS_GROUP_ON_EXCEPTION')
                process.wait()
                raise
            finally:
                # Reaping the JVM closes its threads. Only this invocation's group is touched.
                if group_exists(process.pid):
                    os.killpg(process.pid, signal.SIGKILL)
                    item['cleanupActions'].append('SIGKILL_SURVIVING_EXACT_OWNED_GROUP')
                until = min(deadline, time.monotonic()+1)
                while group_exists(process.pid) and time.monotonic() < until:
                    time.sleep(.01)
                item['closed'] = not group_exists(process.pid)
                item['processExit'] = process.returncode
                item['finishedUtc'] = utc()
            if not item['closed']:
                raise RuntimeError('Owned process group remains unresolved')
            return code

    try:
        java = Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64'))/'bin/java'
        if not java.is_file():
            raise FileNotFoundError('Cached JDK17 java executable unavailable')
        record['java'] = {'path': str(java.resolve()), 'sha256': sha(java.resolve()), 'mode': stat.S_IMODE(java.resolve().stat().st_mode)}
        cache = Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
        coordinates = [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),
                       ('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
                       ('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),
                       ('org.jetbrains','annotations','13.0'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]
        jars = []
        for group, name, version in coordinates:
            paths = sorted((cache/group/name/version).glob(f'*/{name}-{version}.jar'))
            if len(paths) != 1:
                raise FileNotFoundError('Expected one existing cached dependency: '+name)
            jars += paths
        record['dependencies'] = [{'path': str(f), 'sha256': sha(f), 'mode': stat.S_IMODE(f.stat().st_mode)} for f in jars]
        snapshots = []
        for index, source in enumerate(source_files(root,current)):
            dest = out/'source'/str(index)/source.name
            dest.parent.mkdir(parents=True)
            dest.write_bytes(source.read_bytes())
            dest.chmod(stat.S_IMODE(source.stat().st_mode))
            snapshots.append(str(dest))
            record['inputs'].append({'path': str(source), 'snapshot': str(dest), 'sha256': sha(dest), 'mode': stat.S_IMODE(source.stat().st_mode)})
        runner_copy = out/'runner'/runner.name
        runner_copy.parent.mkdir()
        runner_copy.write_bytes(runner.read_bytes())
        runner_copy.chmod(record['runner']['mode'])
        record['runner']['snapshot'] = str(runner_copy)
        cp = os.pathsep.join(map(str,jars))
        artifact = out/'activation.jar'
        commands = {'javaVersion': [str(java), '-version'],
                    'compile': [str(java), '-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(artifact)]+snapshots,
                    'run': [str(java), '-cp',str(artifact)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.notifications.selectiontest.ScenariosKt']}
        if args.observation219_only:
            commands['run'].append('--observation219-only')
        elif args.correction65_only:
            commands['run'].append('--correction65-only')
        record['commands'] = commands
        record['argvSha256'] = hashlib.sha256(json.dumps(commands,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        (out/'inputs.json').write_text(json.dumps(record,indent=2)+'\n')
        version = bounded(commands['javaVersion'],'java-version',5)
        if version:
            raise RuntimeError('Cached JDK version query failed')
        record['compileExit'] = bounded(commands['compile'],'compile',85)
        exit_code = record['compileExit']
        if not exit_code:
            record['runExit'] = bounded(commands['run'],'run',25)
            exit_code = record['runExit']
            lines = (out/'run.log').read_text()
            record['namedResults'] = [line for line in lines.splitlines() if line.startswith(('PASS ','FAIL ','RESULT '))]
            print(lines,end='')
        else:
            print((out/'compile.log').read_text(),end='')
        if artifact.is_file():
            record['artifact'] = {'path': str(artifact), 'sha256': sha(artifact)}
    except Exception as error:
        exit_code = 1
        record['error'] = str(error)
        print(str(error),file=sys.stderr)
    finally:
        record['finishedUtc'] = utc()
        record['elapsedSeconds'] = time.monotonic()-started
        record['sourcesUnchanged'] = all(Path(x['path']).is_file() and sha(Path(x['path'])) == x['sha256'] and stat.S_IMODE(Path(x['path']).stat().st_mode) == x['mode'] for x in record['inputs'])
        record['runnerUnchanged'] = sha(runner) == record['runner']['sha256'] and stat.S_IMODE(runner.stat().st_mode) == record['runner']['mode']
        record['allOwnedProcessesClosed'] = all(x['closed'] for x in record['ownedProcesses'])
        record['logs'] = [{'path': str(x),'sha256': sha(x)} for x in sorted(out.glob('*.log'))]
        record['exit'] = exit_code if record['sourcesUnchanged'] and record['runnerUnchanged'] and record['allOwnedProcessesClosed'] else 2
        (out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
    return record['exit']


if __name__ == '__main__':
    sys.exit(main())
