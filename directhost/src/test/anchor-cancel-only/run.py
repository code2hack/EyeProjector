#!/usr/bin/env python3
"""Cross-feature actual-source integration using Manager-frozen cached dependencies."""
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
p.add_argument('--queued-launch-before', action='store_true')

a = p.parse_args()
suite = Path(__file__).resolve().parent
root = suite.parents[3]
shared = root / 'directhost/src/test/anchor-integration'
rg_suite = root / 'directrg/src/test/native-swipe-navigation'
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
rgmain = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
launch = shared.parent / 'launch'
out = a.output_dir.resolve()
if (out / 'result.json').exists(): p.error('Refusing to overwrite completed evidence: ' + str(out))
out.mkdir(parents=True, exist_ok=True)
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
sources += [rgmain / (n + '.kt') for n in ['ProjectionSession', 'ReceiverSessionOwner', 'AnchorFeedbackUi', 'AnchorHeldIndicator',
            'RetainedVideoOutput', 'SurfaceDecoder']]
sources += [f for f in sorted((root / 'directrg/src/test/kotlin/android').rglob('*.kt'))
            if f.name not in ['SystemClock.kt', 'AtomicFile.kt']]
sources += [f for f in sorted((launch / 'doubles').glob('*.kt')) if f.name not in ['AnchorInterfaces.kt', 'Json.kt', 'Context.kt', 'Runtime.kt']]
binder = out / 'AnchorInterfaces.kt'
binder_origin = shared.parent / 'anchor-hold/doubles/AnchorInterfaces.kt'
# AIDL generation is an explicit host boundary; actual two consumers compile against this new method.
binder.write_text(binder_origin.read_text().replace('    fun heldSetting(): ByteArray?',
    '    fun consumeHeldControl(): Int = 0\n    fun heldSetting(): ByteArray?'))
sources += [binder, shared.parent / 'adapter/doubles/AtomicFile.kt']
sources += [launch / (n + '.kt') for n in ['Scenarios', 'ServiceContextScenarios', 'FusionScenarios', 'StopRaceScenarios', 'FunctionFailureScenarios']]
sources += [root / 'directrg/src/test/kotlin/android/util/Log.kt']
sources += [host / (n + '.kt') for n in ['ShellMain', 'PanelPower', 'LiveScale', 'PhoneTouchDispatcher',
    'HeldInputRelease', 'OwnedRelease', 'ProjectedAppLaunch', 'PlatformProjectionLaunch', 'ProjectionDisplayState',
    'ProjectionContext', 'ShizukuHostService', 'OwnedProjectionRoutes', 'OwnedHostLifecycle', 'OwnedShellSessions',
    'BootstrapSessionFiles', 'SessionUsability']]
for prefix in ['anchor/execution', 'anchor/preferences', 'anchor/target', 'anchor/registration']:
    sources += sorted((host / prefix).glob('*.kt'))
sources += [host / 'uiaccess' / (n + '.kt') for n in ['UiTargetSnapshot', 'UiSourceGeometry', 'UiTargetReader', 'UiTargetSnapshotProvider']]
sources += [host / 'activation/snapshot' / (n + '.kt') for n in ['NativeSnapshotRegion', 'SnapshotWord']]
sources += sorted((shared.parent / 'snapshot/transport/doubles').glob('*.kt'))
sources += [host / 'input' / (n + '.kt') for n in ['PhoneInputMerger', 'FunctionWork', 'FunctionIntentRoute']]
sources += [shared.parent / 'anchor-hold/AnchorHoldSessionScenarios.kt']
import importlib.util
import re
parser_spec = importlib.util.spec_from_file_location('actual_activity_blocks', shared.parent / 'hid/run.py')
parser = importlib.util.module_from_spec(parser_spec)
parser_spec.loader.exec_module(parser)
activity_path = rgmain / 'ReceiverActivity.kt'
activity = activity_path.read_text()
boundary = rg_suite / 'CompositionBoundary.kt'
fixture = boundary.read_text()
names = ['currentRegistrationIntent', 'anchorMessage', 'chooseAnchorRegistration', 'cancelAnchorRegistration',
         'cancelInput', 'cancelInputState', 'updatePointerBounds', 'publishHeadPointer', 'updateAnchorHeldContext',
         'contextualBack', 'contextualHome', 'showControlPanel', 'showHidBindings', 'dispatchNavigation', 'completedNavigation', 'sleepGlasses', 'exitToLauncher', 'finishProjection', 'cancelOriginalOnExit', 'remoteBack', 'remoteTap', 'remoteScroll', 'resize', 'captureFunction', 'dispatchFunction', 'showSnapshot']
names += ['currentNavigation', 'cancelInputRevision']
blocks = {name: parser.block(activity, rf'^    (?:private|internal) fun {name}\(') for name in names}
blocks['NavigationContext'] = parser.block(activity, r'^    private data class NavigationContext\(')
blocks['navigationContext'] = re.search(r'^    private fun navigationContext\(\).*?anchorRegistration.offer\)', activity, re.M | re.S).group()
for name in ['onResume', 'onWindowFocusChanged', 'onPause', 'onDestroy']:
    blocks[name] = parser.block(activity, rf'^    override fun {name}\(')
predicates = '\n'.join(re.search(rf'^    private fun {n}\(.*$', activity, re.M).group() for n in
                       ['usable', 'registrationActive', 'localInputVisible', 'projectedInput', 'functionContext'])
held_fields = re.search(r'^    private lateinit var anchorHeldLabel: TextView$', activity, re.M).group() + '\n' + \
    parser.block(activity, r'^    private val anchorHeldIndicator = AnchorHeldIndicator ')
session_view = parser.block(activity, r'^    private val sessionView = object : ReceiverSessionOwner.View ')
anchor = parser.block(activity, r'^        anchorRegistration = AnchorRegistrationController ')
completed = re.search(r'^        completedInput = CompletedReceiverInput\(.*?::completedNavigation\)', activity, re.M | re.S).group()
held = parser.block(activity, r'^        anchorHeldLabel = TextView\(this\).apply ') + '\n' + \
    re.search(r'^        root.addView\(anchorHeldLabel,.*$', activity, re.M).group()
capture = re.search(r'^    private data class FunctionCapture.*$', activity, re.M).group()
native = parser.block(activity, r'^        nativeSwipeInput = NativeSwipeNavigationInput\(this\) ')
generated = out / 'HeldActivityWiring.kt'
generated.write_text(fixture.replace('// ACTUAL_HELD_FIELDS', held_fields).replace('// ACTUAL_SESSION_VIEW', session_view)
                     .replace('// ACTUAL_ANCHOR_INITIALIZER', anchor).replace('// ACTUAL_COMPLETED_INITIALIZER', completed)
                     .replace('// ACTUAL_HELD_INITIALIZER', held).replace('// ACTUAL_PREDICATES', predicates)
                     .replace('// ACTUAL_NATIVE_INITIALIZER', native).replace('// ACTUAL_METHODS', capture + '\n' + '\n'.join(blocks.values())))
snapshot = out / 'ReceiverActivity-source.kt'
snapshot.write_text(activity)
activity_record = {'path': str(activity_path), 'snapshot': str(snapshot), 'sha256': sha(snapshot),
                   'boundary': str(boundary), 'boundarySha256': sha(boundary), 'blocks': blocks,
                   'sessionView': session_view, 'heldFields': held_fields, 'heldInitializer': held,
                   'predicates': predicates, 'functionCapture': capture, 'nativeInitializer': native,
                   'scope': 'Complete actual Activity methods/initializer/callback source; Android widgets/service framework boundary; full Activity compile separately required'}
sources += [root / 'directrg/src/test/anchor-integration/ToastBoundary.kt', generated, shared.parent / 'anchor-hold/doubles/Json.kt', root / 'directrg/src/test/anchor-integration/ReceiverRig.kt',
            rg_suite / 'CompositionRig.kt', suite / 'CoreScenarios.kt', suite / 'CoreUnitScenarios.kt', suite / 'QueuedHomeBefore.kt']
sources += [rgmain / (n + '.kt') for n in ['AnchorRegistrationController', 'FunctionGestureRecognizer',
                                         'CompletedReceiverInput', 'SessionPresentation', 'ContextualNavigation', 'NativeSwipeNavigationInput', 'PadGestureRecognizer', 'HeldInputOwner', 'HeldPointerDispatcher', 'OrderedControlDispatcher']]
mapping = out / 'NativePadMapping.kt'
mapping.write_text('package com.code2hack.eyeprojector.directrg\n' + parser.block(
    (rgmain / 'NativePadInput.kt').read_text(), r'^internal fun nativePadKey\(') + '\n')
sources.append(mapping)
sources += sorted((rg_suite / 'doubles').glob('*.kt')) + sorted((rg_suite / 'compositiondoubles').glob('*.kt'))
control_type=host/'anchor/execution/AnchorControlAdmission.kt'
if control_type.exists(): sources.append(control_type)
main = 'com.code2hack.eyeprojector.directhost.CoreScenariosKt'
sources = list(dict.fromkeys(sources))
snapshots = []
for i, source in enumerate(sources):
    copy = out / 'sources' / str(i) / source.name
    copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_bytes(source.read_bytes())
    snapshots.append(copy)
jar = out / 'integration-source.jar'
compile_cmd = [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-Xfriend-paths=' + friends,
               '-jvm-target', '17', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(jar)] + list(map(str, snapshots))
run_cmd = [java, '-Xlog:class+load=info:file=' + str(out / 'class-load.log'), '-cp', str(jar) + os.pathsep + cp, main]
record = {'scope': 'Owner88 Phone core actual source; original Session/merger/Anchor/Binder/FIFO implementations, cached accepted122 dependencies and named Android/transport/UI boundaries; no Glasses/wire join or SDK/device claim',
          'dependencyManifest': str(a.dependency_manifest.resolve()), 'dependencyManifestSha256': sha(a.dependency_manifest),
          'dependencies': jars, 'originAcceptedHead': manifest['originAcceptedHead'],
          'historicalDependencyDisposition': manifest['historicalDisposition'],
          'sources': [{'path': str(s), 'snapshot': str(c), 'sha256': sha(c)} for s, c in zip(sources, snapshots)],
          'compileCommand': compile_cmd, 'runCommand': run_cmd, 'runnerSha256': sha(__file__), 'base': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()}
record['activityExtraction'] = activity_record
actual_aidl = root / 'directhost/src/main/aidl/com/code2hack/eyeprojector/directhost/anchor/execution/IAnchorSession.aidl'
aidl_snapshot = out / 'IAnchorSession-source.aidl'
aidl_snapshot.write_bytes(actual_aidl.read_bytes())
record['aidlSource'] = {'path': str(actual_aidl), 'snapshot': str(aidl_snapshot), 'sha256': sha(aidl_snapshot)}
record['binderBoundary'] = {'source': str(binder_origin), 'sourceSha256': sha(binder_origin),
    'generated': str(binder), 'generatedSha256': sha(binder),
    'aidl': str(root / 'directhost/src/main/aidl/com/code2hack/eyeprojector/directhost/anchor/execution/IAnchorSession.aidl'),
    'qualification': 'Generated Binder boundary adapted with new consumeHeldControl method; default NORMAL only for existing stateless host fixture Stubs. Actual production Operation/Runtime methods override and load from new source.'}
env = os.environ.copy()
env['EYEPROJECTOR_HOLD_EVIDENCE'] = str(out)
if a.queued_launch_before: env['EYEPROJECTOR_PENDING_LAUNCH'] = '1'
record['pendingQueuedLaunchBeforeOnly'] = a.queued_launch_before
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
    # Verify the critical closure actually loaded from the newly compiled source artifact.
    required = [
        'com.code2hack.eyeprojector.protocol.BackHomeGestureRecognizer',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlAdmission',
        'com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher',
        'com.code2hack.eyeprojector.directhost.Session',
        'com.code2hack.eyeprojector.directhost.input.PhoneInputMerger',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorContactOwner',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorSessionInput',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorShellSession',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorAppCoordinator',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorAppRuntime',
        'com.code2hack.eyeprojector.directrg.ProjectionSession',
        'com.code2hack.eyeprojector.directrg.ReceiverSessionOwner',
    ]
    loaded = (out / 'class-load.log').read_text().splitlines()
    record['sourceClassLoads'] = {name: [line for line in loaded if '] ' + name + ' source:' in line] for name in required}
    record['sourceClassesVerified'] = all(
        len(lines) == 1 and (' source: file:' + str(jar)) in lines[0] for lines in record['sourceClassLoads'].values())
    javap = shutil.which('javap')
    if javap:
        with (out / 'abi.txt').open('w') as log:
            abi = subprocess.run([javap, '-classpath', str(jar), '-p'] + required,
                                 stdout=log, stderr=subprocess.STDOUT, timeout=30)
        record['abiExit'] = abi.returncode
        record['abiSha256'] = sha(out / 'abi.txt')
    if not record['sourceClassesVerified']:
        record['testExit'] = record['testExit'] or 1
        print('FAIL source-class loading provenance; see result.json')
    log_text = (out / 'scenarios.log').read_text()
    print('\n'.join(line for line in log_text.splitlines() if not line.startswith('{"audio"')))
else:
    print((out / 'compile.log').read_text())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(record['compileExit'] or record.get('testExit', 1))
