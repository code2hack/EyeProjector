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
p.add_argument('--phone-before-result', type=Path)
p.add_argument('--panel-before-result', type=Path, help='Own frozen ShellMain/PanelPower only; final panel case')
p.add_argument('--native-before-result', type=Path, help='Own frozen PinRuntime only; first authoritative observation cases')

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
    '    fun consumeHeldControl(): Int = 0\n    fun heldSetting(): ByteArray?').replace('    fun onIntent(transactionId:',
    '    fun onControl(original: IAnchorUserControl) = original.apply()\n    fun onSession(ownerEpoch: String, projectionToken: String, original: IAnchorControlSession) = Unit\n    fun onIntent(transactionId:') + '''\ninterface IAnchorUserControl {
    fun apply()
    abstract class Stub: android.os.Binder(), IAnchorUserControl
}
interface IAnchorControlSession {
    fun admitUserControl(recognitionOnly: Boolean): Int
    fun captureRawControl(disposition:IntArray): IAnchorRawControl? = null
    abstract class Stub: android.os.Binder(), IAnchorControlSession
}
interface IAnchorRawControl {
    fun admitUserControl():Int
    fun edge(routeId:Long,typedEdge:ByteArray,regionId:String,word:Long)
    abstract class Stub:android.os.Binder(), IAnchorRawControl
}
''')
sources += [binder, shared.parent / 'adapter/doubles/AtomicFile.kt']
sources += [launch / (n + '.kt') for n in ['Scenarios', 'ServiceContextScenarios', 'FusionScenarios', 'StopRaceScenarios', 'FunctionFailureScenarios']]
sources += [root / 'directrg/src/test/kotlin/android/util/Log.kt']
sources += [host / (n + '.kt') for n in ['PhoneInputTraceConfig', 'PhoneInputTrace', 'CameraSourceSettings', 'ShellMain', 'PanelPower', 'LiveScale', 'PhoneTouchDispatcher',
    'HeldInputRelease', 'OwnedRelease', 'ProjectedAppLaunch', 'PlatformProjectionLaunch', 'ProjectionDisplayState',
    'ProjectionContext', 'ShizukuHostService', 'OwnedProjectionRoutes', 'OwnedHostLifecycle', 'OwnedShellSessions',
    'BootstrapSessionFiles', 'SessionUsability', 'UserControls']]
for prefix in ['anchor/execution', 'anchor/preferences', 'anchor/target', 'anchor/registration']:
    sources += sorted((host / prefix).glob('*.kt'))
sources += [host / 'uiaccess' / (n + '.kt') for n in ['UiTargetSnapshot', 'UiSourceGeometry', 'UiTargetReader', 'UiTargetSnapshotProvider']]
sources += [host / 'activation/snapshot' / (n + '.kt') for n in ['NativeSnapshotRegion', 'SnapshotWord']]
sources += sorted((shared.parent / 'snapshot/transport/doubles').glob('*.kt'))
sources += [host / 'input' / (n + '.kt') for n in ['PhoneInputMerger', 'FunctionWork', 'FunctionIntentRoute']]
sources += [host/'hid'/(n+'.kt') for n in ['PhoneHidController','PhoneHidBindings']]
sources += [shared.parent / 'anchor-hold/AnchorHoldSessionScenarios.kt']
import importlib.util
import re
parser_spec = importlib.util.spec_from_file_location('actual_activity_blocks', shared.parent / 'hid/run.py')
parser = importlib.util.module_from_spec(parser_spec)
parser_spec.loader.exec_module(parser)
activity_path = rgmain / 'ReceiverActivity.kt'
activity = activity_path.read_text()
boundary = rg_suite / 'CompositionBoundary.kt'
fixture = boundary.read_text().replace('// ACTUAL_METHODS', '    private fun runOnUiThread(action: () -> Unit) { action() }\n// ACTUAL_METHODS')
fixture = fixture.replace('    val confirmTap = Runnable {}','    private var confirmTap: Runnable = newConfirmTap()').replace('    val edgeTick = Runnable {}','    private var edgeTick: Runnable = newEdgeTick()')
fixture = fixture.replace('}, capture = ::captureFunction)', '}, capture = ::captureFunction, captureOriginal = { expected, id, parent -> captureFunctionOriginal(expected,id,FunctionSource.GLASSES_NATIVE,parent) }, retireOriginal = { id, original -> retireFunctionOriginal(id,original,FunctionSource.GLASSES_NATIVE) }, registrationRelease = { gesture, canceled -> releaseFunctionRegistration(FunctionSource.GLASSES_NATIVE, gesture, canceled) })')
fixture = fixture.replace('    val edgeMotion = HeldNode()', '    val edgeMotion = HeldNode()\n    private val settings = object { val scrollSpeed = 100 }')
fixture = fixture.replace('    private fun nativeInputStage(stage: String) = Unit', '    private fun nativeInputStage(stage: String, due: Long? = null) = Unit')
fixture = fixture.replace('    fun cancel() = Unit', """    fun prepareCancel(preserveRegistration: Boolean = false) = InputRetirement({}, { false })
    fun prepareCancel(time: Long) = InputRetirement({}, { false })
    fun captureCancellation() = InputRetirement({}, { false })
    fun prepareDismiss() = InputRetirement({}, { false })
    fun confirm(time: Long) = Unit
    val deadline: Long? get() = null
    fun postDelayed(task: Runnable, delay: Long) = Unit
    var visibleBounds = PointerBounds(0f,0f,480f,640f)
    fun direction(point: PointerPosition, bounds: PointerBounds, expires: Long, now: Long) = 0
    fun step(point: PointerPosition, bounds: PointerBounds, expires: Long, now: Long, speed: Int) = 0
    fun cancel() = Unit""")
names = ['currentRegistrationIntent', 'anchorMessage', 'chooseAnchorRegistration', 'retireRegistration', 'cancelAnchorRegistration',
         'cancelInput', 'cancelInputState', 'updatePointerBounds', 'publishHeadPointer', 'updateAnchorHeldContext',
         'contextualBack', 'contextualHome', 'showControlPanel', 'showHidBindings', 'dispatchNavigation', 'localControl', 'admitLocalControl', 'showControlPanelAdmitted', 'showHidBindingsAdmitted', 'contextualBackAdmitted', 'contextualHomeAdmitted', 'sleepGlassesAdmitted', 'exitToLauncherAdmitted', 'completedNavigation', 'sleepGlasses', 'exitToLauncher', 'finishProjection', 'cancelOriginalOnExit', 'remoteBack', 'remoteTap', 'remoteScroll', 'resize', 'captureFunction', 'captureFunctionOriginal', 'retireFunctionOriginal', 'dispatchFunction', 'releaseFunctionRegistration', 'registrationReleased', 'showSnapshot']
names += ['currentNavigation', 'cancelInputRevision', 'prepareInputCancellation', 'cancelInputOperation', 'newEdgeTick', 'newConfirmTap', 'schedulePadConfirmation']
blocks = {name: parser.block(activity, rf'^    (?:private|internal) fun {name}\(') for name in names}
blocks['nativePadMotionInput'] = '    private var nativePadMotionAdmitted = false\n' + parser.block(activity,r'^    private val nativePadMotionInput by lazy ').replace('try { if (::padContact.isInitialized) padContact.accept(event) }','try { Unit }')
blocks['padControlInput'] = parser.block(activity,r'^    private val padControlInput by lazy ').replace('dispatchPadActionAdmitted(PadGestureRecognizer.Action.Contact(event))','Unit')
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
fixture = fixture.replace('    fun waiting() = Unit', '    fun waiting() = Unit\n    fun pointer(x:Float,y:Float,available:Boolean)=Unit')
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
                   'scope': 'Actual Activity destination/cancellation/Function/registration source on named widgets/service boundaries; padControlInput field callback is explicitly Unit here because this closure does not exercise native pad normalization/delivery. Actual full pad callback/body is compiled by run-retirement.py; full Activity SDK compile separately required', 'adaptations': {'padControlInputDelivery': 'Unit: native pad delivery is not included in this Phone/Function closure', 'nativePadMotionDelivery': 'Unit: new original Motion queue metadata is compiled but raw delivery exercised by full retirement runner' }}
sources += [root / 'directrg/src/test/anchor-integration/ToastBoundary.kt', generated, shared.parent / 'anchor-hold/doubles/Json.kt', root / 'directrg/src/test/anchor-integration/ReceiverRig.kt',
            rg_suite / 'CompositionRig.kt', suite / 'CoreScenarios.kt', suite / 'CoreUnitScenarios.kt', suite / 'QueuedHomeBefore.kt', suite / 'CentralGateScenarios.kt', suite / 'PhoneIngressScenarios.kt', suite / 'LocalGateScenarios.kt', suite / 'TraceScenarios.kt', suite / 'RawGlassesScenarios.kt', suite / 'ScopedLaunchScenarios.kt', suite / 'BridgeReceiptScenarios.kt']
sources += [rgmain / (n + '.kt') for n in ['NativePadMotionInput', 'ReceiverContactInput', 'InputRetirement', 'AnchorRegistrationController', 'FunctionGestureRecognizer',
                                         'CompletedReceiverInput', 'SessionPresentation', 'ContextualNavigation', 'NativeSwipeNavigationInput', 'PadGestureRecognizer', 'HeldInputOwner', 'HeldPointerDispatcher', 'OrderedControlDispatcher']]
mapping = out / 'NativePadMapping.kt'
mapping.write_text('package com.code2hack.eyeprojector.directrg\n' + parser.block(
    (rgmain / 'NativePadInput.kt').read_text(), r'^internal fun nativePadKey\(') + '\n')
sources.append(mapping)
sources += sorted((rg_suite / 'doubles').glob('*.kt')) + sorted((rg_suite / 'compositiondoubles').glob('*.kt'))
control_type=host/'anchor/execution/AnchorControlAdmission.kt'
if control_type.exists(): sources.append(control_type)
main = 'com.code2hack.eyeprojector.directhost.CoreScenariosKt'
for index, file in enumerate(sources):
    if file.is_file() and 'interface IHostBootstrap' in file.read_text():
        adapted = out / 'HostBootstrapInterfaces.kt'
        adapted.write_text(file.read_text().replace('    fun destroy()',
            '    fun userControl(kind: Int, expectedOwnerEpoch: String, expectedProjectionToken: String): Int = 0\n    fun destroy()'))
        sources[index] = adapted
# Actual HostBootstrap START/config bodies; Android binding/executor/status-only boundaries are explicit.
bootstrap = host / 'HostBootstrap.kt'
bootstrap_text = bootstrap.read_text()
bootstrap_blocks = [parser.block(bootstrap_text, r'^    fun start\('), parser.block(bootstrap_text, r'^    private fun performPending\(')]
bootstrap_blocks += [re.search(r'^    (?:private val inputTraceConfiguration|fun configureInputTrace).*$', bootstrap_text, re.M).group()]
bootstrap_blocks += [re.search(r'^    fun configureInputTrace.*$', bootstrap_text, re.M).group(), re.search(r'^    private fun inputTraceEndpoint.*?PhoneInputTraceConfig.endpoint.*$', bootstrap_text, re.M|re.S).group().split('\n\n')[0]]
bootstrap_boundary = suite / 'BootstrapTraceBoundary.kt'
bootstrap_generated = out / 'BootstrapTraceWiring.kt'
bootstrap_generated.write_text(bootstrap_boundary.read_text().replace('// ACTUAL_FIELDS_AND_METHODS', '\n'.join(bootstrap_blocks)).replace('JSONObject(result)', 'BootstrapStatusBoundary(result)'))
sources += [bootstrap_generated, suite / 'BootstrapTraceScenarios.kt', suite / 'BootstrapAudioBoundary.kt']

# Full native producer with explicit Android/Shizuku/JSON registry boundaries.
sources += [host/'hid/PhoneHidPinRuntime.kt', host/'input/PhoneHidRoute.kt', host/'input/PhoneHidOriginal.kt', host/'activation/PhoneHidActivation.kt', suite/'PinProducerScenarios.kt']
sources += sorted((suite/'pinboundary').glob('*.kt'))
for index,file in enumerate(sources):
    if file == rg_suite/'doubles/Content.kt':
        text=file.read_text().replace('    private val booleans=', '    private val strings=mutableMapOf<String,String>()\n    fun getString(key:String,fallback:String?)=strings[key]?:fallback\n    private val booleans=')
        text=text.replace('        fun apply()=Unit','        fun putString(key:String,value:String):Editor {strings[key]=value;return this}\n        fun remove(key:String):Editor {strings.remove(key);return this}\n        fun commit()=true\n        fun apply()=Unit')
        text=text.replace('    val assets = Assets()', '    val packageManager=android.content.pm.PackageManager()\n    fun startForegroundService(intent:Intent)=Unit\n    val assets = Assets()').replace('class Intent(val action: String?) {','class Intent(val action: String?) {\n    constructor(context:Context,type:Class<*>):this(type.name)')
        copy=out/'PinContent.kt';copy.write_text(text);sources[index]=copy
    if file == shared.parent/'anchor-hold/doubles/Json.kt':
        text=file.read_text().replace('class JSONObject {','class JSONObject() {\n    constructor(text:String):this(){values.putAll(checkNotNull(registry[text]))}\n    fun getString(key:String)=values[key] as String\n    fun getLong(key:String)=(values[key] as Number).toLong()\n    fun getInt(key:String)=(values[key] as Number).toInt()\n    fun getBoolean(key:String)=values[key] as Boolean\n    fun opt(key:String)=values[key]\n    fun optString(key:String)=(values[key] as? String)?:""\n    fun optInt(key:String,fallback:Int)= (values[key] as? Number)?.toInt()?:fallback\n    fun optBoolean(key:String)=(values[key] as? Boolean)?:false\n    fun optJSONArray(key:String)=values[key] as? JSONArray\n    fun retain(key:String):String {registry[key]=values.toMap();return key}')
        text=text.replace('companion object { @JvmField val NULL = Any() }','companion object { private val registry=java.util.concurrent.ConcurrentHashMap<String,Map<String,Any?>>() ; @JvmField val NULL = Any() }')
        text=text.replace('class JSONArray {','class JSONArray() {\n    constructor(text:String):this(){error("array parser not used by controlled Pin ingress")}\n    fun length()=values.size\n    fun getJSONObject(i:Int)=values[i] as JSONObject\n    fun getString(i:Int)=values[i] as String')
        copy=out/'PinJson.kt';copy.write_text(text);sources[index]=copy
# Exact public raw fixture, with the actual Android adapter and explicit framework handle doubles.
import json as fixture_json
raw_fixture=root/'directhost/src/test/uiaccess/fixtures/doubao-voice306.json'
raw=fixture_json.loads(raw_fixture.read_text())['nodes']
def kotlin_value(v):
    if v is None:return 'null'
    if isinstance(v,bool):return str(v).lower()
    if isinstance(v,str):return fixture_json.dumps(v)
    if isinstance(v,list):return 'listOf('+','.join(kotlin_value(x) for x in v)+')'
    return str(v)
raw_generated=out/'RawVoiceFixture.kt'
raw_generated.write_text('package com.code2hack.eyeprojector.directhost.uiaccess\ninternal fun capturedVoiceNodes()=listOf(\n'+',\n'.join('RawVoiceNode('+','.join(kotlin_value(n.get(k)) for k in ['ordinal','parentOrdinal','childIndex','childCount','resourceId','className','bounds','actionIds','clickable','longClickable','visible','enabled'])+')' for n in raw)+'\n)\n')
raw_generated.write_text(raw_generated.read_text().replace('RawVoiceNode(0,null,null,','RawVoiceNode(0,null,0,'))
sources += [raw_generated,host/'uiaccess/AndroidUiAccess.kt',root/'directhost/src/test/uiaccess/RegistrationScenarios.kt',suite/'RawRegistrationScenarios.kt']
sources += [root/'directhost/src/test/uiaccess/doubles'/(n+'.kt') for n in ['AndroidAccessibility','AndroidService','AndroidSparseArray','AndroidRect']]
(out/'raw-fixture.json').write_bytes(raw_fixture.read_bytes())
sources = list(dict.fromkeys(sources))
before_sources = {}
if a.phone_before_result:
    before_record = json.loads(a.phone_before_result.read_text())
    before_sources = {entry['path']: entry for entry in before_record['sources']}

snapshots = []
for i, source in enumerate(sources):
    copy = out / 'sources' / str(i) / source.name
    copy.parent.mkdir(parents=True, exist_ok=True)
    old = before_sources.get(str(source)) if source.is_relative_to(host) else None
    override = a.panel_before_result if source in [host/'ShellMain.kt',host/'PanelPower.kt'] else a.native_before_result if source == host/'hid/PhoneHidPinRuntime.kt' else None
    if override:
        old=next(e for e in json.loads(override.read_text())['sources'] if e['path']==str(source))
    if old:
        original = Path(old['snapshot'])
        if sha(original) != old['sha256']: p.error('Changed frozen before source: ' + str(original))
        copy.write_bytes(original.read_bytes())
    else:
        copy.write_bytes(source.read_bytes())
    snapshots.append(copy)
jar = out / 'integration-source.jar'
compile_cmd = [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-Xfriend-paths=' + friends,
               '-jvm-target', '17', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(jar)] + list(map(str, snapshots))
run_cmd = [java, '-Xlog:class+load=info:file=' + str(out / 'class-load.log'), '-cp', str(jar) + os.pathsep + cp, main]
record = {'scope': 'Bounded actual Phone central gate, matched Glasses local intents/Function frames and original lifetimes; actual Session/merger/Anchor/Binder/FIFO/marker producer sources and cached accepted122 dependencies. Named Android/TLS/UI/native pad/Motion delivery boundaries; separate full-source Android/runtime acceptance required',
          'dependencyManifest': str(a.dependency_manifest.resolve()), 'dependencyManifestSha256': sha(a.dependency_manifest),
          'dependencies': jars, 'originAcceptedHead': manifest['originAcceptedHead'],
          'historicalDependencyDisposition': manifest['historicalDisposition'],
          'sources': [{'path': str(s), 'snapshot': str(c), 'sha256': sha(c)} for s, c in zip(sources, snapshots)],
          'compileCommand': compile_cmd, 'runCommand': run_cmd, 'runnerSha256': sha(__file__), 'base': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()}
record['activityExtraction'] = activity_record
bootstrap_frozen=out/'HostBootstrap-source.kt';bootstrap_frozen.write_bytes(bootstrap.read_bytes())
record['bootstrapExtraction']={'source':str(bootstrap),'snapshot':str(bootstrap_frozen),'sha256':sha(bootstrap_frozen),'blocks':bootstrap_blocks,'boundary':str(bootstrap_boundary),'boundarySha256':sha(bootstrap_boundary),'scope':'Actual START/config/performPending bodies, already-connected remote forwarding to real Shizuku service; setup/executor/binding/status publication boundary. Unused STOP JSONObject parser explicitly substituted.'}

record['nativeProducerBoundaryAdapters'] = []
for name, original in [('PinContent.kt',rg_suite/'doubles/Content.kt'),('PinJson.kt',shared.parent/'anchor-hold/doubles/Json.kt')]:
    frozen=out/('original-'+original.name);frozen.write_bytes(original.read_bytes())
    record['nativeProducerBoundaryAdapters'].append({'originalPath':str(original),'originalSnapshot':str(frozen),'originalSha256':sha(frozen),'compiledSnapshot':str(out/name),'compiledSha256':sha(out/name),'scope':'Android preferences/package/service APIs and controlled JSON-object registry only; full actual PinRuntime callback/connect/lane/main/controller/root-gate source remains unchanged'})
record['panelBeforeResult'] = str(a.panel_before_result) if a.panel_before_result else None
record['nativeBeforeResult'] = str(a.native_before_result) if a.native_before_result else None
record['phoneBeforeResult'] = str(a.phone_before_result) if a.phone_before_result else None
for label, path in [('runner', Path(__file__)), ('parser', shared.parent / 'hid/run.py')]:
    frozen = out / (label + '-source.py')
    frozen.write_bytes(path.read_bytes())
    record[label + 'Snapshot'] = {'path': str(path), 'snapshot': str(frozen), 'sha256': sha(frozen)}
record['aidlSources'] = []
for path in sorted((root / 'directhost/src/main/aidl').rglob('*.aidl')):
    if path.name not in ['IAnchorControlSession.aidl', 'IAnchorRawControl.aidl', 'IAnchorAppBridge.aidl', 'IAnchorUserControl.aidl', 'IHostBootstrap.aidl']: continue
    frozen = out / path.name
    frozen.write_bytes(path.read_bytes())
    record['aidlSources'].append({'path': str(path), 'snapshot': str(frozen), 'sha256': sha(frozen)})
actual_aidl = root / 'directhost/src/main/aidl/com/code2hack/eyeprojector/directhost/anchor/execution/IAnchorSession.aidl'
aidl_snapshot = out / 'IAnchorSession-source.aidl'
aidl_snapshot.write_bytes(actual_aidl.read_bytes())
record['aidlSource'] = {'path': str(actual_aidl), 'snapshot': str(aidl_snapshot), 'sha256': sha(aidl_snapshot)}
record['binderBoundary'] = {'source': str(binder_origin), 'sourceSha256': sha(binder_origin),
    'generated': str(binder), 'generatedSha256': sha(binder),
    'aidl': str(root / 'directhost/src/main/aidl/com/code2hack/eyeprojector/directhost/anchor/execution/IAnchorSession.aidl'),
    'qualification': 'Generated Binder boundary adapted with new consumeHeldControl method; default NORMAL only for existing stateless host fixture Stubs. Actual production Operation/Runtime methods override and load from new source.'}
env = os.environ.copy()
if a.panel_before_result: env['EYEPROJECTOR_R187_PANEL_ONLY']='1'
if a.native_before_result: env['EYEPROJECTOR_C206_NATIVE_ONLY']='1'
env['EYEPROJECTOR_HOLD_EVIDENCE'] = str(out)
fixture_dir=out/'trace-fixtures';fixture_dir.mkdir()
keytool=str(Path(java).parent/'keytool')
fixture_commands=[[keytool,'-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048','-dname','CN=Host-only trace test','-validity','2','-storetype','PKCS12','-keystore',str(fixture_dir/'identity.p12'),'-storepass','host-fixture-only'],[keytool,'-exportcert','-alias','fixture','-keystore',str(fixture_dir/'identity.p12'),'-storepass','host-fixture-only','-file',str(fixture_dir/'peer.crt')]]
with (fixture_dir/'keytool.log').open('w') as log:
    for command in fixture_commands: subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=30)
(fixture_dir/'password').write_text('host-fixture-only')
server=Path('/home/code2hack/.local/state/eyeprojector/v0.3/run-001/issue-115/current-production-sdk159/run01/source/third_party/scrcpy-server-v5.0.1')
assert sha(server)=='764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536'
env['EYEPROJECTOR_TRACE_FIXTURES']=str(fixture_dir);env['EYEPROJECTOR_TRACE_SERVER']=str(server)
record['tracePreparationFixtures']={'commands':fixture_commands,'files':[{'path':str(f),'sha256':sha(f)} for f in sorted(fixture_dir.iterdir())],'server':{'path':str(server),'sha256':sha(server)},'qualification':'Host-generated self-signed test-only identity, fake external listener/peer TLS boundary; real public owned-preparation/Tls.context/config and Bridge Session factory. No SDK/device/test private data.'}

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
        'com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStore',
        'com.code2hack.eyeprojector.directrg.PadGestureRecognizer',
        'com.code2hack.eyeprojector.directhost.PhoneInputTrace',
        'com.code2hack.eyeprojector.directhost.input.FunctionWork',
        'com.code2hack.eyeprojector.protocol.HidNavigationGestureRecognizer',
        'com.code2hack.eyeprojector.protocol.ReceiverControlIntent',
        'com.code2hack.eyeprojector.protocol.ReceiverControlResult',
        'com.code2hack.eyeprojector.protocol.FunctionOriginal',
        'com.code2hack.eyeprojector.directhost.hid.PhoneHidController',
        'com.code2hack.eyeprojector.directhost.PlatformProjectionLaunch',
        'com.code2hack.eyeprojector.directhost.OwnedProjectionCommands',
        'com.code2hack.eyeprojector.directrg.FunctionGestureRecognizer',
        'com.code2hack.eyeprojector.directrg.InputRetirement',
        'com.code2hack.eyeprojector.directrg.ReceiverContactInput',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlAdmission',
        'com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher',
        'com.code2hack.eyeprojector.directhost.Session',
        'com.code2hack.eyeprojector.directhost.Bridge',
        'com.code2hack.eyeprojector.directhost.hid.PhoneHidPinRuntime',
        'com.code2hack.eyeprojector.directhost.input.PhoneHidOriginal',
        'com.code2hack.eyeprojector.directhost.input.PhoneInputMerger',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorContactOwner',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorSessionInput',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorShellSession',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorAppCoordinator',
        'com.code2hack.eyeprojector.directhost.anchor.execution.AnchorAppRuntime',
        'com.code2hack.eyeprojector.directrg.ProjectionSession',
        'com.code2hack.eyeprojector.directrg.ReceiverSessionOwner',
    ]
    if a.panel_before_result:
        required=['com.code2hack.eyeprojector.directhost.'+n for n in ['Session','SessionPanelPower','anchor.execution.AnchorContactOwner','anchor.execution.AnchorSessionInput','input.PhoneInputMerger']]
    elif a.native_before_result:
        required=['com.code2hack.eyeprojector.directhost.'+n for n in ['Session','hid.PhoneHidPinRuntime','hid.PhoneHidController','input.PhoneHidOriginal','input.PhoneInputMerger','anchor.execution.AnchorAppRuntime','anchor.execution.AnchorShellSession','anchor.execution.AnchorContactOwner']]
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
