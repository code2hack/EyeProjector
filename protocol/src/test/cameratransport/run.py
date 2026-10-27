#!/usr/bin/env python3
"""Frozen camera transport checks on real TLS/sockets and explicit host Android boundaries.

Execution requires its own allocation. --prepare-only copies exact inputs and commands without Java.
Each output directory is new, and first compile/run outcomes are retained without repair or retry.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--suite', choices=['protocol', 'phone', 'receiver', 'projection-tls'], required=True)
p.add_argument('--output-dir', type=Path, required=True)
p.add_argument('--prepare-only', action='store_true')
a = p.parse_args()
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
root = Path(__file__).resolve().parents[4]
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
phone = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
receiver = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
pt = root / 'protocol/src/test/kotlin/com/code2hack/eyeprojector/protocol'
ht = root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost'
rt = root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg'
files = [f for f in sorted(protocol.glob('*.kt')) if f.name != 'PairingPanel.kt']
classes = []
annotation = root / 'directhost/src/test/adapter/doubles/Annotation.kt'
files.append(annotation)
if a.suite == 'protocol':
    names = ['CameraFeedTest', 'WireTest', 'TouchTest', 'PhoneInputTest', 'LayoutScaleTest', 'SessionControlTest', 'TlsTest']
    files += [pt / (n + '.kt') for n in names]
    files += [pt / 'DelayedTlsContext.kt', root / 'protocol/src/test/kotlin/android/os/SystemClock.kt']
    classes = ['com.code2hack.eyeprojector.protocol.' + n for n in names]
elif a.suite == 'phone':
    # LanPairing is outside this server closure; no clock substitution or copied product body.
    files = [f for f in files if f.name != 'LanPairing.kt']
    names = ['ShellMain', 'PanelPower', 'PhoneTouchDispatcher', 'HeldInputRelease', 'OwnedRelease', 'LiveScale',
             'ProjectionContext', 'ProjectionDisplayState', 'ProjectedAppLaunch', 'PlatformProjectionLaunch', 'SessionUsability']
    files += [phone / (n + '.kt') for n in names]
    files += [phone / 'camera/CameraMediaRole.kt', phone / 'input/PhoneInputMerger.kt', phone / 'input/FunctionWork.kt']
    files += [phone / ('activation/snapshot/' + n + '.kt') for n in ['NativeSnapshotRegion', 'SnapshotWord']]
    files += [f for f in sorted((root / 'directhost/src/test/panel/doubles').glob('*.kt')) if f.name != 'Tls.kt']
    files += sorted((root / 'directhost/src/test/snapshot/transport/doubles').glob('*.kt'))
    files += [root / 'directhost/src/test/adapter/doubles/AtomicFile.kt', ht / 'camera/CameraMediaRoleTest.kt', ht / 'CameraBridgeDispatchTest.kt']
    classes = ['com.code2hack.eyeprojector.directhost.camera.CameraMediaRoleTest',
               'com.code2hack.eyeprojector.directhost.CameraBridgeDispatchTest']
else:
    names = ['ProjectionSession', 'ReceiverSessionOwner', 'ReceiverActivationStore', 'PairedSessionClient',
             'RetainedVideoOutput', 'SurfaceDecoder', 'SessionAttachment', 'StatePublisher', 'VideoReadiness', 'VideoClock',
             'HeldTouchTransport', 'OrderedControlDispatcher', 'LatestHeadPointer', 'CompletedReceiverInput', 'SessionPresentation',
             'HeadPointerModel', 'HeadOrientation', 'HeldPointerDispatcher', 'PadGestureRecognizer', 'WindowPresentationObservation']
    files += [receiver / (n + '.kt') for n in names]
    files += [receiver / ('localcamera/' + n + '.kt') for n in
              ['LocalPreviewOutput', 'LocalProducerRetirement', 'ViewfinderGeometry']]
    files += [receiver / 'localcamera/forwarding/CameraRoleClient.kt']
    files += sorted((root / 'directrg/src/test/kotlin/android').rglob('*.kt'))
    files += sorted((root / 'directrg/src/test/kotlin/org/json').glob('*.kt'))
    files += [f for f in sorted((root / 'directrg/src/test/activation/doubles').glob('*.kt'))
              if f.name not in ['Transport.kt', 'StartupPeer.kt']]
    if a.suite == 'receiver':
        names = ['CameraProjectionDispatchTest', 'ReceiverUsabilityTest', 'RetainedReceiverSessionTest',
                 'ProjectionInputTest', 'HeadPointerExpiryTest', 'PhoneFusionTest', 'SessionCloseCompletionTest']
        files += [rt / (n + '.kt') for n in names]
        files += [rt / 'localcamera/forwarding/CameraRoleClientTest.kt']
        classes = ['com.code2hack.eyeprojector.directrg.' + n for n in names]
        classes += ['com.code2hack.eyeprojector.directrg.localcamera.forwarding.CameraRoleClientTest']
    else:
        files += [rt / 'VideoSilenceTest.kt']
        classes = ['com.code2hack.eyeprojector.directrg.VideoSilenceTest']

cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
coordinates = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'), ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
               ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'), ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
               ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), ('org.jetbrains', 'annotations', '13.0'),
               ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3'),
               ('org.bouncycastle', 'bcprov-jdk18on', '1.83'), ('org.bouncycastle', 'bcutil-jdk18on', '1.83'),
               ('org.bouncycastle', 'bcpkix-jdk18on', '1.83')]
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()
result = {'suite': a.suite, 'scope': 'Actual camera codecs/owners/TLS and explicit host Android doubles; no physical camera/input/capture proof',
          'runner': {'path': str(Path(__file__).resolve()), 'sha256': sha(Path(__file__))},
          'inputs': [], 'dependencies': [], 'commands': {}, 'compileExit': None, 'runExit': None,
          'preparedOnly': a.prepare_only, 'firstFailure': None}
try:
    compiled = []
    for i, source in enumerate(dict.fromkeys(files)):
        dest = out / 'source' / str(i) / source.name
        dest.parent.mkdir(parents=True)
        dest.write_bytes(source.read_bytes())
        result['inputs'].append({'path': str(source), 'snapshot': str(dest), 'sha256': sha(dest)})
        compiled.append(str(dest))
    jars = []
    for group, name, version in coordinates:
        found = sorted((cache / group / name / version).glob('*/' + name + '-' + version + '.jar'))
        if len(found) != 1:
            raise RuntimeError('Missing or ambiguous cached dependency ' + name)
        source = found[0]
        dest = out / 'dependencies' / source.name
        dest.parent.mkdir(exist_ok=True)
        dest.write_bytes(source.read_bytes())
        jars.append(str(dest))
        result['dependencies'].append({'path': str(source), 'snapshot': str(dest), 'sha256': sha(dest)})
    java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
    result['java'] = {'path': java, 'sha256': sha(Path(java))}
    cp = os.pathsep.join(jars)
    jar = str(out / 'camera-transport.jar')
    result['commands'] = {
        'compile': [java, '-Xmx2g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-jvm-target', '17', '-classpath', cp, '-d', jar] + compiled,
        'run': [java, '-cp', jar + os.pathsep + cp, 'org.junit.runner.JUnitCore'] + classes}
    if not a.prepare_only:
        for phase, limit in [('compile', 90), ('run', 90)]:
            with (out / (phase + '.log')).open('w') as log:
                completed = subprocess.run(result['commands'][phase], stdout=log, stderr=subprocess.STDOUT, timeout=limit, cwd=out)
            result[phase + 'Exit'] = completed.returncode
            print((out / (phase + '.log')).read_text(), end='')
            if completed.returncode:
                result['firstFailure'] = phase
                break
except Exception as error:
    result['firstFailure'] = type(error).__name__
    raise
finally:
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
if not a.prepare_only:
    raise SystemExit(0 if result['compileExit'] == result['runExit'] == 0 else 1)
