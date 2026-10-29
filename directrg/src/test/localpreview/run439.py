#!/usr/bin/env python3
"""Finite cached actual-renderer retirement439 controls, retaining the unchanged old13 bodies. No SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def proc(pid):
    try:
        raw = Path(f'/proc/{pid}/stat').read_text()
        fields = raw[raw.rfind(')') + 2:].split()
        return {'pid': pid, 'state': fields[0], 'ppid': int(fields[1]),
                'pgid': int(fields[2]), 'birthTicks': int(fields[19])}
    except (FileNotFoundError, ProcessLookupError):
        return None


def group(pgid):
    found = []
    for path in Path('/proc').iterdir():
        if path.name.isdigit():
            item = proc(int(path.name))
            if item and item['pgid'] == pgid:
                found.append(item)
    return found


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
started = time.monotonic()
deadline = started + 110
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
runner = Path(__file__).resolve()
root = next(p for p in runner.parents if (p / 'SPEC.md').is_file())
feature = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
files = [root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/RetainedVideoOutput.kt'] + sorted(feature.glob('*.kt')) + sorted((runner.parent / 'doubles').glob('*.kt')) + [runner.parent / 'RendererScenarios.kt', runner.parent / 'RendererRetirementScenarios.kt'] + [
    protocol / name for name in ['Wire.kt', 'CameraFeed.kt', 'Touch.kt', 'PhoneInput.kt',
                                'ReceiverUsability.kt', 'ActivationControl.kt']]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
coords = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
          ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
          ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
          ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
          ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
          ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
          ('org.jetbrains', 'annotations', '13.0')]
jars = []
for g, name, version in coords:
    matches = list((cache / g / name / version).glob(f'*/{name}-{version}.jar'))
    assert len(matches) == 1, (g, name, version, matches)
    jars.extend(matches)
java = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin/java').resolve()
result = {'scope': 'Whole current RetainedVideoOutput + optional producer-retirement capability and actual Wire/Fit; '
          'unchanged old13 scenario bodies plus separate retirement439 cases, explicit GL/Android doubles; '
          'no EGL/Camera2/acquisition/forwarding/device/capture or native callback-quiescence proof',
          'runner': {'path': str(runner), 'sha256': sha(runner), 'process': proc(os.getpid())},
          'inputs': [], 'dependencies': [{'path': str(p), 'sha256': sha(p)} for p in jars],
          'java': {'path': str(java), 'sha256': sha(java)}, 'phases': []}
compiled = []
for file in files:
    dest = out / 'source' / file.relative_to(root)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(file.read_bytes())
    result['inputs'].append({'path': str(file), 'snapshot': str(dest), 'sha256': sha(dest)})
    compiled.append(str(dest))
(out / 'runner.py').write_bytes(runner.read_bytes())
cp = os.pathsep.join(str(p) for p in jars)
artifact = out / 'retained-retirement439.jar'
commands = [('compile', [str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(artifact), *compiled], 85),
            ('run', [str(java), '-cp', str(artifact) + os.pathsep + cp,
                     'com.code2hack.eyeprojector.directrg.RendererRetirement439Runner'], 15)]


def save():
    result['elapsedSeconds'] = time.monotonic() - started
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


save()  # Preserve exact input/tool pins before either invocation.
try:
    for name, argv, limit in commands:
        phase = {'name': name, 'argv': argv, 'timeoutSeconds': min(limit, deadline - time.monotonic()),
                 'exit': None, 'waited': False, 'reaped': False, 'observedProcesses': []}
        result['phases'].append(phase)
        with (out / (name + '.log')).open('w') as log:
            child = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            phase['originalProcess'] = proc(child.pid)
            seen = {}
            until = time.monotonic() + phase['timeoutSeconds']
            try:
                while True:
                    for item in group(child.pid):
                        seen[(item['pid'], item['birthTicks'])] = item
                    remaining = until - time.monotonic()
                    if remaining <= 0:
                        raise subprocess.TimeoutExpired(argv, phase['timeoutSeconds'])
                    try:
                        phase['exit'] = child.wait(timeout=min(0.1, remaining))
                        phase['waited'] = True
                        break
                    except subprocess.TimeoutExpired:
                        pass
            except BaseException as error:
                phase['exception'] = repr(error)
                # Only the child process group created by this exact invocation is targeted.
                os.killpg(child.pid, signal.SIGKILL)
                phase['exit'] = child.wait()
                phase['waited'] = True
                raise
            finally:
                phase['observedProcesses'] = list(seen.values())
                phase['processAfterWait'] = proc(child.pid)
                phase['reaped'] = child.returncode is not None and phase['processAfterWait'] is None
                phase['remainingGroup'] = group(child.pid)
                phase['remainingObservedChildren'] = [item for old in seen.values()
                    if (item := proc(old['pid'])) and item['birthTicks'] == old['birthTicks']]
                save()
        print((out / (name + '.log')).read_text(), end='', flush=True)
        if phase['exit'] != 0 or not phase['reaped'] or phase['remainingGroup'] or phase['remainingObservedChildren']:
            break
    result['passed'] = len(result['phases']) == 2 and all(
        p['exit'] == 0 and p['reaped'] and not p['remainingGroup'] and not p['remainingObservedChildren']
        for p in result['phases'])
    if artifact.exists():
        result['artifact'] = {'path': str(artifact), 'sha256': sha(artifact)}
finally:
    save()
raise SystemExit(0 if result.get('passed') else 1)
