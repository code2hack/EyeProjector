#!/usr/bin/env python3
"""Compile the exact production close owner with delayed fake callbacks; cached JVM only, no SDK."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--source-file', type=Path, help='Exact saved production source for a parent control')
parser.add_argument('--scenarios-file', type=Path, help='Exact saved affected scenarios for a parent control')
args = parser.parse_args()
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
suite = Path(__file__).resolve().parent
root = suite.parents[3]
source = (args.source_file or root / 'hostprobe/src/main/kotlin/com/code2hack/eyeprojector/hostprobe/CameraAccessActivity.kt').resolve()
scenarios = (args.scenarios_file or suite / 'Scenarios.kt').resolve()
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
text = source.read_text()
owner = text.split('// BEGIN CAMERA_CLOSE_OWNER', 1)[1].split('\n', 1)[1].split('// END CAMERA_CLOSE_OWNER', 1)[0]
actual = out / 'CameraCloseOwner.kt'
actual.write_text('package com.code2hack.eyeprojector.hostprobe\n' + owner)
tests = out / 'Scenarios.kt'
tests.write_bytes(scenarios.read_bytes())
(out / 'CameraAccessActivity.kt').write_bytes(source.read_bytes())
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
coordinates = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
               ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
               ('org.jetbrains', 'annotations', '13.0')]
jars = []
for group, artifact, version in coordinates:
    matches = list((cache / group / artifact / version).glob('*/*.jar'))
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one existing cached JAR: ' + artifact + '; no download attempted')
    jars.extend(matches)
cp = os.pathsep.join(map(str, jars))
java = '/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
jar = out / 'owner-tests.jar'
commands = {
    'compile': [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-jvm-target', '17', '-classpath', cp, '-d', str(jar), str(actual), str(tests)],
    'tests': [java, '-cp', str(jar) + os.pathsep + cp, 'com.code2hack.eyeprojector.hostprobe.ScenariosKt'],
}
command_environment = {'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8'}
record = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'scope': 'Exact production CameraCloseOwner/mode declaration; fake callbacks only. Activity/Camera2/layout/imagery not executed.',
          'productionSource': {'path': str(source), 'sha256': sha(source)},
          'runner': {'path': str(Path(__file__).resolve()), 'sha256': sha(Path(__file__).resolve())},
          'scenarios': {'path': str(scenarios), 'sha256': sha(tests)},
          'compiledOwnerSha256': sha(actual), 'commands': commands,
          'commandEnvironment': command_environment, 'commandCwd': str(out),
          'dependencies': [{'path': str(p), 'sha256': sha(p)} for p in jars], 'phases': [], 'exit': None}
(out / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')


def process_observation(pid):
    try:
        raw = Path('/proc', str(pid), 'stat').read_text()
        fields = raw.rsplit(')', 1)[1].split()
        return {'pid': pid, 'pgid': int(fields[2]), 'startTimeTicks': int(fields[19]), 'stat': raw}
    except FileNotFoundError:
        return {'pid': pid, 'absent': True}


def group_members(pgid):
    members = []
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            pid = int(path.parent.name)
            observed = process_observation(pid)
            if observed.get('pgid') == pgid:
                members.append(observed)
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    return members


try:
    for phase, timeout in [('compile', 90), ('tests', 20)]:
        observed = {'phase': phase, 'argv': commands[phase], 'environment': command_environment,
                    'cwd': str(out), 'timeoutSeconds': timeout, 'state': 'intent',
                    'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'forcedSignals': []}
        record['phases'].append(observed)
        (out / (phase + '-intent.json')).write_text(json.dumps(observed, indent=2) + '\n')
        started = time.monotonic()
        with (out / (phase + '.log')).open('w') as log:
            process = subprocess.Popen(commands[phase], cwd=out, env=command_environment,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            observed['owner'] = process_observation(process.pid)
            (out / (phase + '-owner.json')).write_text(json.dumps(observed, indent=2) + '\n')
            try:
                result = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                # Preserve the existing bounded host deadline and reconcile only this owned group.
                observed['timedOut'] = True
                for sig, wait in [(signal.SIGTERM, 3), (signal.SIGKILL, 3)]:
                    if process.poll() is not None:
                        break
                    os.killpg(process.pid, sig)
                    observed['forcedSignals'].append(sig.name)
                    try:
                        process.wait(timeout=wait)
                    except subprocess.TimeoutExpired:
                        continue
                result = 124
        observed['state'] = 'terminal'
        observed['exit'] = result
        observed['waitReturnCode'] = process.returncode
        observed['wallSeconds'] = time.monotonic() - started
        observed['afterWait'] = process_observation(process.pid)
        observed['groupMembersAfterWait'] = group_members(process.pid)
        (out / (phase + '-result.json')).write_text(json.dumps(observed, indent=2) + '\n')
        record[phase + 'Exit'] = result
        print((out / (phase + '.log')).read_text(), end='', flush=True)
        record['exit'] = result
        if result:
            break
except Exception as error:
    record['exit'] = 125
    record['error'] = str(error)
finally:
    record['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    record['sourceUnchanged'] = sha(source) == record['productionSource']['sha256']
    record['scenariosUnchanged'] = sha(scenarios) == record['scenarios']['sha256']
    record['logs'] = {p.name: sha(p) for p in out.glob('*.log')}
    if jar.exists():
        record['artifactSha256'] = sha(jar)
    (out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(record['exit'] if record['sourceUnchanged'] and record['scenariosUnchanged'] else 2)
