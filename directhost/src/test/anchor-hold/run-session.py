#!/usr/bin/env python3
"""Actual Phone/Glasses source composition using only Manager-frozen cached dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
p.add_argument('--dependency-manifest', type=Path, required=True)
p.add_argument('--glasses', action='store_true')
p.add_argument('--phone-artifact', type=Path)
a = p.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
rgmain = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
launch = suite.parent / 'launch'
manifest = json.loads(a.dependency_manifest.read_text())
sha = lambda f: hashlib.sha256(Path(f).read_bytes()).hexdigest()
jars = manifest['jars']
for entry in jars:
    if sha(entry['path']) != entry['sha256']:
        p.error('Frozen dependency no longer matches Manager manifest: ' + entry['path'])
cp = os.pathsep.join(entry['path'] for entry in jars)
friends = ','.join(entry['path'] for entry in jars if entry['kind'] in ['host-kotlin', 'glasses-kotlin'])
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
# Every protocol DTO used by this closure is actual current source, preceding old cached Wire10.
sources = sorted(f for f in protocol.glob('*.kt') if f.name not in
                 ['LanPairing.kt', 'Tls.kt', 'PairingPanel.kt', 'PairingExchange.kt', 'PeerStore.kt'])
if a.glasses:
    sources += [rgmain / (n + '.kt') for n in ['ProjectionSession', 'ReceiverSessionOwner', 'AnchorFeedbackUi', 'AnchorHeldIndicator',
                'RetainedVideoOutput', 'SurfaceDecoder']]
    sources += sorted((root / 'directrg/src/test/kotlin/android').rglob('*.kt'))
    sources += [root / 'directrg/src/test/kotlin/org/json/JSONObject.kt']
    sources += [root / 'directrg/src/test/anchor-hold/AnchorHoldTransportTest.kt']
    sources += [root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg/RetainedReceiverSessionTest.kt']
    main = 'org.junit.runner.JUnitCore'
    main_args = ['com.code2hack.eyeprojector.directrg.AnchorHoldTransportTest']
else:
    sources += [f for f in sorted((launch / 'doubles').glob('*.kt')) if f.name != 'AnchorInterfaces.kt']
    sources += [suite / 'doubles/AnchorInterfaces.kt', suite.parent / 'adapter/doubles/AtomicFile.kt']
    sources += [launch / (n + '.kt') for n in ['Scenarios', 'ServiceContextScenarios', 'FusionScenarios', 'StopRaceScenarios', 'FunctionFailureScenarios']]
    sources += [root / 'directrg/src/test/kotlin/android/util/Log.kt']
    sources += [host / (n + '.kt') for n in ['ShellMain', 'PanelPower', 'LiveScale', 'PhoneTouchDispatcher',
        'HeldInputRelease', 'OwnedRelease', 'ProjectedAppLaunch', 'PlatformProjectionLaunch', 'ProjectionDisplayState',
        'ProjectionContext', 'ShizukuHostService', 'OwnedProjectionRoutes', 'OwnedHostLifecycle', 'OwnedShellSessions',
        'BootstrapSessionFiles', 'SessionUsability']]
    for prefix in ['anchor/execution', 'anchor/preferences', 'anchor/target']:
        sources += sorted((host / prefix).glob('*.kt'))
    sources += [host / 'uiaccess' / (n + '.kt') for n in ['UiTargetSnapshot', 'UiSourceGeometry', 'UiTargetReader', 'UiTargetSnapshotProvider']]
    sources += [host / 'activation/snapshot' / (n + '.kt') for n in ['NativeSnapshotRegion', 'SnapshotWord']]
    sources += sorted((suite.parent / 'snapshot/transport/doubles').glob('*.kt'))
    sources += [host / 'input' / (n + '.kt') for n in ['PhoneInputMerger', 'FunctionWork', 'FunctionIntentRoute']]
    sources += [suite / 'AnchorHoldSessionScenarios.kt']
    main = 'com.code2hack.eyeprojector.directhost.AnchorHoldSessionScenariosKt'
    main_args = []
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=True)
snapshots = []
for i, source in enumerate(sources):
    copy = out / 'sources' / str(i) / source.name
    copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_bytes(source.read_bytes())
    snapshots.append(copy)
jar = out / ('glasses-source.jar' if a.glasses else 'phone-source.jar')
compile_cmd = [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-Xfriend-paths=' + friends,
               '-jvm-target', '17', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(jar)] + list(map(str, snapshots))
run_cmd = [java, '-Xlog:class+load=info:file=' + str(out / 'class-load.log'), '-cp', str(jar) + os.pathsep + cp, main] + main_args
record = {'scope': 'Actual current source first; only Manager-frozen accepted122 dependencies; Android framework/Binder/UI boundaries doubled',
          'dependencyManifest': str(a.dependency_manifest.resolve()), 'dependencyManifestSha256': sha(a.dependency_manifest),
          'dependencies': jars, 'originAcceptedHead': manifest['originAcceptedHead'],
          'historicalDependencyDisposition': manifest['historicalDisposition'],
          'sources': [{'path': str(s), 'snapshot': str(c), 'sha256': sha(c)} for s, c in zip(sources, snapshots)],
          'compileCommand': compile_cmd, 'runCommand': run_cmd, 'runnerSha256': sha(__file__)}
env = os.environ.copy()
env['EYEPROJECTOR_HOLD_EVIDENCE'] = str(out)
if a.phone_artifact:
    env['EYEPROJECTOR_HOLD_PHONE_ARTIFACT'] = str(a.phone_artifact.resolve())
with (out / 'compile.log').open('w') as log:
    r = subprocess.run(compile_cmd, stdout=log, stderr=subprocess.STDOUT, timeout=120)
record['compileExit'] = r.returncode
if r.returncode == 0:
    with zipfile.ZipFile(jar) as current:
        produced = set(current.namelist())
    overlaps = []
    for dep in jars:
        if dep['kind'] not in ['protocol', 'host-kotlin', 'host-java', 'glasses-kotlin', 'android-api35']:
            continue
        with zipfile.ZipFile(dep['path']) as cache:
            for name in sorted(produced.intersection(cache.namelist())):
                if name.endswith('.class'):
                    overlaps.append({'class': name, 'oldDependency': dep['path'], 'disposition': 'new actual source JAR first'})
    record['overlaps'] = overlaps
    with (out / 'scenarios.log').open('w') as log:
        r = subprocess.run(run_cmd, stdout=log, stderr=subprocess.STDOUT, timeout=60, env=env)
    record['testExit'] = r.returncode
    record['artifactSha256'] = sha(jar)
    record['loadedClasses'] = str(out / 'class-load.log')
    log_text = (out / 'scenarios.log').read_text()
    print('\n'.join(line for line in log_text.splitlines() if not line.startswith('{"audio"')) if not a.glasses else log_text)
else:
    print((out / 'compile.log').read_text())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(record['compileExit'] or record.get('testExit', 1))
