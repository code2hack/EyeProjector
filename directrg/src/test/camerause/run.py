#!/usr/bin/env python3
"""REQUEST ONLY: finite actual media14 or owner17 source501 host controls; no device/network."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def data_record(path):
    observed = path.stat()
    return {'path': str(path), 'sha256': sha(path), 'bytes': observed.st_size,
            'mode': oct(observed.st_mode & 0o7777), 'mtimeNsDecimal': str(observed.st_mtime_ns)}


def supporting_records(directories):
    return [data_record(path) for directory in directories
            for path in sorted(directory.rglob('*')) if path.is_file()]


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
parser.add_argument('--suite', choices=('media', 'owner'), default='media')
args = parser.parse_args()
started = time.monotonic()
deadline = started + 120
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
runner = Path(__file__).resolve()
root = next(p for p in runner.parents if (p / 'SPEC.md').is_file())
# Deliberately explicit: compile actual production bodies and protocol dependencies, no model replacement/glob.
source_names = [
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/LocalPreviewOutput.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/LocalProducerRetirement.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/ViewfinderGeometry.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/LocalCompositionState.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/acquisition/CameraAcquisitionContract.kt',
    'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Wire.kt',
    'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Touch.kt',
    'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/PhoneInput.kt',
    'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/ReceiverUsability.kt',
    'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/ActivationControl.kt',
    'directrg/src/test/cameraencoder/doubles/SurfaceDouble.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/forwarding/ForwardEncoderContract.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/forwarding/EncoderRunOwnership.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/forwarding/CameraSurfaceEncoder.kt',
    'directrg/src/test/cameraencoder/doubles/MediaCodecDoubles.kt',
    'directrg/src/test/cameraencoder/doubles/MediaFormatDoubles.kt',
    'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/CameraFeed.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/forwarding/CameraRoleClient.kt',
    'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/forwarding/EncoderCameraMediaSource.kt',
    'directrg/src/test/camerause/doubles/EncoderHandoffDoubles.kt',
    'directrg/src/test/camerause/EncoderMediaSourceScenarios.kt',
]
supporting_dirs = []
compile_only_jars = []
main_class = 'com.code2hack.eyeprojector.directrg.localcamera.forwarding.EncoderMediaSource501Scenarios'
scope = ('Actual EncoderCameraMediaSource + CameraRoleClient + CameraSurfaceEncoder + EncoderRunOwnership + contracts + localcamera '
         'capability/geometry/state + actual CameraFeed/Wire dependencies; unchanged explicit MediaCodec/MediaFormat/Surface doubles and '
         'a TLS environment boundary that is never used. No native AVC, Camera2, TLS/network, Phone capture or whole issue proof.')
if args.suite == 'owner':
    # Actual ProjectionSession and SessionControl compile as bodies/types, never as a fixture model.
    # Their unchanged helper classes come from the pinned SDK478 class directories. Source wins
    # over duplicate cached/Android symbols; the resulting source JAR is FIRST at runtime.
    source_names.remove('directrg/src/test/camerause/doubles/EncoderHandoffDoubles.kt')
    source_names.extend([
        'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Tls.kt',
        'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/SessionControl.kt',
        'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ProjectionSession.kt',
        'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/SessionStatusWriter.kt',
        'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverCameraOwner.kt',
        'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/forwarding/ReceiverCameraUse.kt',
        'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/acquisition/CameraRunOwnership.kt',
        'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/localcamera/acquisition/GlassesCameraProducer.kt',
        'directrg/src/test/cameraacquisition/doubles/OsDoubles.kt',
        'directrg/src/test/camerause/doubles/AndroidManifest.kt',
        'directrg/src/test/camerause/doubles/AndroidContext.kt',
        'directrg/src/test/camerause/doubles/AndroidPackageManager.kt',
        'directrg/src/test/camerause/doubles/AndroidImageFormat.kt',
        'directrg/src/test/camerause/doubles/AndroidSurfaceTexture.kt',
        'directrg/src/test/camerause/doubles/AndroidSizeRange.kt',
        'directrg/src/test/camerause/doubles/AndroidStreamConfigurationMap.kt',
        'directrg/src/test/camerause/doubles/CameraCatalogDoubles.kt',
        'directrg/src/test/camerause/doubles/PermissionServiceDoubles.kt',
        'directrg/src/test/camerause/ReceiverCameraUseScenarios.kt',
    ])
    supporting_dirs = [root / 'directrg/build/tmp/kotlin-classes/debug',
                       root / 'protocol/build/tmp/kotlin-classes/debug']
    compile_only_jars = [Path('/home/code2hack/Android/Sdk/platforms/android-35/android.jar')]
    main_class = 'com.code2hack.eyeprojector.directrg.ReceiverCameraUse501Scenarios'
    scope = ('Actual ReceiverCameraOwner/ReceiverCameraUse/selector + S3 client/ledger/hub + S1 Camera2/encoder adapters, '
             'with actual ProjectionSession/SessionControl/Tls compiled as body/type dependencies. Tests invoke '
             'attachCapturedProjection with actual authenticated-use ledger events and an injected in-memory role socket. '
             'No ProjectionSession constructor, normal projection connection, Tls method, native Android/Camera2/AVC, '
             'network, Phone capture or whole issue acceptance is exercised. Android35 jar is compile-only; pinned '
             'SDK478 class directories provide otherwise unchanged supporting helpers, never a replacement owner/source.')
files = [root / name for name in source_names]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
coords = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
          ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
          ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
          ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
          ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
          ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
          ('org.jetbrains', 'annotations', '13.0')]
jars = []
for group_name, name, version in coords:
    matches = list((cache / group_name / name / version).glob(f'*/{name}-{version}.jar'))
    assert len(matches) == 1, (group_name, name, version, matches)
    jars.extend(matches)
java = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin/java').resolve()
python = Path(sys.executable).resolve()
result = {
    'scope': scope, 'suite': args.suite, 'preparedIndividualGroups': 17 if args.suite == 'owner' else 14,
    'logCounting': 'Individual START/PASS labels only; aggregate footer is not another group.',
    'runner': {'path': str(runner), 'sha256': sha(runner), 'process': proc(os.getpid())},
    'totalDeadlineSeconds': 120,
    'inputs': [], 'dependencies': [{'path': str(p), 'sha256': sha(p)} for p in jars],
    'supportingClassDirectories': [str(p) for p in supporting_dirs],
    'supportingClassesBefore': supporting_records(supporting_dirs),
    'compileOnlyBefore': [data_record(p) for p in compile_only_jars],
    'java': {'path': str(java), 'sha256': sha(java)},
    'python': {'path': str(python), 'sha256': sha(python)}, 'phases': [],
}
compiled = []
for file in files:
    dest = out / 'source' / file.relative_to(root)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(file.read_bytes())
    record = data_record(file)
    record.update({'snapshot': str(dest), 'snapshotSHA256': sha(dest)})
    result['inputs'].append(record)
    compiled.append(str(dest))
(out / 'runner.py').write_bytes(runner.read_bytes())
cp = os.pathsep.join(str(p) for p in jars)
compile_cp = os.pathsep.join(str(p) for p in [*jars, *supporting_dirs, *compile_only_jars])
friend_options = ['-Xfriend-paths=' + ','.join(str(p) for p in supporting_dirs)] if supporting_dirs else []
artifact = out / ('receiver-owner501.jar' if args.suite == 'owner' else 'encoder-media501.jar')
runtime_cp = os.pathsep.join(str(p) for p in [artifact, *jars, *supporting_dirs])
commands = [('compile', [str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', compile_cp,
                        *friend_options, '-d', str(artifact), *compiled], 85),
            ('run', [str(java), '-cp', runtime_cp, main_class], 30)]


def save():
    result['elapsedSeconds'] = time.monotonic() - started
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


save()  # Exact source snapshots, runner and cached tool pins precede both invocations.
try:
    for name, argv, limit in commands:
        remaining_total = deadline - time.monotonic()
        if remaining_total <= 0:
            raise TimeoutError('Finite host-run deadline reached before next invocation')
        phase = {'name': name, 'argv': argv, 'timeoutSeconds': min(limit, remaining_total),
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
                # Target only this exact invocation's newly created process group.
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
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
    try:
        result['inputsAfter'] = [data_record(p) for p in files]
        result['sourceUnchanged'] = all(
            all(before[key] == after[key] for key in ('path', 'sha256', 'bytes', 'mode', 'mtimeNsDecimal')) and
            before['sha256'] == before['snapshotSHA256']
            for before, after in zip(result['inputs'], result['inputsAfter']))
        result['supportingClassesAfter'] = supporting_records(supporting_dirs)
        result['compileOnlyAfter'] = [data_record(p) for p in compile_only_jars]
        result['supportingClassesUnchanged'] = result['supportingClassesBefore'] == result['supportingClassesAfter']
        result['compileOnlyUnchanged'] = result['compileOnlyBefore'] == result['compileOnlyAfter']
        if not all(result[name] for name in ('sourceUnchanged', 'supportingClassesUnchanged', 'compileOnlyUnchanged')):
            result['passed'] = False
    except BaseException as error:
        result['afterSnapshotError'] = repr(error)
        result['passed'] = False
    save()
raise SystemExit(0 if result.get('passed') else 1)
