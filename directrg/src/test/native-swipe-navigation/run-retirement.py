#!/usr/bin/env python3
"""Actual original cleanup, Activity callbacks, local dispatch and queued transport; cache only."""
import argparse,hashlib,importlib.util,json,os,re,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--dependency-manifest',type=Path,required=True)
p.add_argument('--source-ref',help='Immutable own Git source for behavior failing-before; component receipt checks omitted')
a=p.parse_args();suite=Path(__file__).resolve().parent;root=suite.parents[3]
actual=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg';protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
sha=lambda data:hashlib.sha256(data).hexdigest()
manifest=json.loads(a.dependency_manifest.read_text())
for dep in manifest['jars']:assert sha(Path(dep['path']).read_bytes())==dep['sha256'],dep['path']
deps=[x for x in manifest['jars'] if x['kind']=='compiler-dependency'];cp=os.pathsep.join(x['path'] for x in deps)
java='/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
helper=root/'directhost/src/test/hid/run.py';spec=importlib.util.spec_from_file_location('retirement_blocks',helper);parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def source(path):
    if a.source_ref:return subprocess.check_output(['git','show',a.source_ref+':'+str(path.relative_to(root))],cwd=root).decode()
    return path.read_text()
# Read only previously frozen own115 before evidence, never mutable peer source.
frozen=Path('/home/code2hack/.local/state/eyeprojector/v0.3/run-001/issue-115/early-cancel81/queue-draft/run03/result.json')
old=json.loads(frozen.read_text())
def frozen_text(name):
    r=next(x for x in old['inputs'] if Path(x['path']).name==name);data=Path(r['snapshot']).read_bytes();assert sha(data)==r['sha256'];return data.decode()
activity=source(actual/'ReceiverActivity.kt');fixture=frozen_text('ActivityNavigationWiring.kt').replace('import android.view.ViewConfiguration','import android.view.ViewConfiguration\nimport android.view.MotionEvent');boundary=frozen_text('Boundaries.kt')
methods=['cancelInput','cancelInputRevision','cancelInputState','contextualBack','contextualHome','remoteBack','showControlPanel','showHidBindings','dispatchNavigation', 'localControl', 'admitLocalControl', 'showControlPanelAdmitted', 'showHidBindingsAdmitted', 'contextualBackAdmitted', 'contextualHomeAdmitted', 'sleepGlasses', 'exitToLauncher', 'sleepGlassesAdmitted', 'exitToLauncherAdmitted', 'finishProjection', 'cancelOriginalOnExit','completedNavigation','currentNavigation','updatePointerBounds','anchorMessage','currentRegistrationIntent','chooseAnchorRegistration', 'retireRegistration','cancelAnchorRegistration','captureFunctionOriginal','retireFunctionOriginal','newEdgeTick','newConfirmTap','prepareInputCancellation', 'cancelInputOperation','schedulePadConfirmation','dispatchPadAction','dispatchPadActionAdmitted','updateEdgeScroll','acceptNativePadMotion']
extracted={}
for name in methods:
    pattern=rf'^    (?:private|internal|override) fun {name}\('
    if not re.search(pattern,activity,re.M):continue
    block=parser.block(activity,pattern);extracted[name]=block
    old_pattern=rf'^\s*(?:private|internal|override) fun {name}\('
    if re.search(old_pattern,fixture,re.M):
        old_line=re.search(old_pattern+r'.*$',fixture,re.M).group()
        old_block=old_line if re.search(r'\)\s*(?::[^=]+)?=\s*',old_line) else parser.block(fixture,old_pattern)
        fixture=fixture.replace(old_block,block,1)
    else:
        marker='\n}\n\ninternal open class SwipeLifecycleBoundary'
        fixture=fixture.replace(marker,'\n'+block+marker,1)
fixture=fixture.replace('    val hidBindings', parser.block(activity,r'^    private val padControlInput by lazy ')+'\n    val hidBindings')
fixture=fixture.replace('    val hidBindings', '    private var nativePadMotionAdmitted = false\n'+parser.block(activity,r'^    private val nativePadMotionInput by lazy ')+'\n    val hidBindings')
fixture=fixture.replace('    val hidBindings', '    private fun runOnUiThread(action: () -> Unit) { action() }\n    val hidBindings')
fixture=fixture.replace('private fun publishHeadPointer() { publishedSessions.add(session); Calls.events.add("publish") }',
    'private fun publishHeadPointer() { session?.let { it.fact=it.fact.copy(inputRevision=inputGeneration,mode=if(localInputVisible()) ReceiverUsabilityMode.LOCAL_PANEL else ReceiverUsabilityMode.PROJECTED) }; publishedSessions.add(session); Calls.events.add("publish") }')
# Complete actual scheduled callback declarations/factories, with visibility alone adapted for observation.
for name in ['edgeTick','confirmTap']:
    pattern=rf'^    private (?:val|var) {name}'
    declaration=next(x for x in activity.splitlines() if re.match(pattern,x))
    if declaration.rstrip().endswith('{'):declaration=parser.block(activity,pattern)
    declaration=declaration.replace('private var','var',1).replace('private val','val',1)
    fixture=re.sub(rf'^    val {name} = Runnable \{{ \}}$',lambda _:declaration,fixture,flags=re.M)
fixture=fixture.replace('lateinit var functionInput: Node','lateinit var functionInput: NativeFunctionInput').replace('lateinit var shellPadContact: Node','lateinit var shellPadContact: NativePadShellContact').replace('lateinit var gestures: Node','lateinit var gestures: PadGestureRecognizer').replace('lateinit var screen: Node','lateinit var screen: ProjectorSurface').replace('lateinit var padContact: Node','lateinit var padContact: NativePadContact').replace('    val edgeMotion = Node()','    val edgeMotion = EdgeScrollMotion()\n    val settings = RetirementSettings()')
fixture=fixture.replace('hidPanel = Node(); functionInput = Node();','hidPanel = Node();')
# The producer callbacks are part of the actual source boundary too; do not retain frozen old wiring.
hid_start=re.search(r'^        hidInput = HidKeyInput\(',activity,re.M).start()
hid_end=re.search(r'^        hidDevices = ',activity[hid_start:],re.M).start()+hid_start
hid_initializer=activity[hid_start:hid_end].rstrip();extracted['hidInitializer']=hid_initializer
old_start=re.search(r'^\s*hidInput = HidKeyInput\(',fixture,re.M).start()
old_end=re.search(r'^        nativeSwipeInput.start\(\)',fixture[old_start:],re.M).start()+old_start
fixture=fixture[:old_start]+hid_initializer+'\n'+fixture[old_end:]

gesture_start=activity.index('        gestures = PadGestureRecognizer(')
gesture_end=activity.index('        // Admission and position',gesture_start)
gesture_initializer=activity[gesture_start:gesture_end].strip();extracted['gesturesInitializer']=gesture_initializer
fixture=fixture.replace('shellPadContact = Node(); gestures = Node();',gesture_initializer+'\n        ')

for name in ['functionInput','shellPadContact']:
    start=re.search(rf'^        {name} = ',activity,re.M).start()
    following='nativeSwipeInput' if name=='functionInput' else 'padContact'
    end=re.search(rf'^        {following} = ',activity[start:],re.M).start()+start
    init=activity[start:end].rstrip();extracted[name+'Initializer']=init
    fixture=fixture.replace('        nativeSwipeInput.start()',init+'\n        nativeSwipeInput.start()',1)
pad_start=re.search(r'^        padContact = NativePadContact\(',activity,re.M).start()
pad_end=re.search(r'^        longTouch = ',activity[pad_start:],re.M).start()+pad_start
pad_initializer=activity[pad_start:pad_end].rstrip();extracted['padContactInitializer']=pad_initializer
fixture=fixture.replace('padContact = Node(); screen = Node(); localInput = LocalPointerDispatcher(root)',pad_initializer+'\n        screen = ProjectorSurface(this,{ x,y -> remoteTap(x,y) },{x,y,pixels -> remoteScroll(x,y,pixels)}); localInput = LocalPointerDispatcher(root)')
fixture=fixture.replace('private fun nativeInputStage(stage: String) = Unit','private fun nativeInputStage(stage: String, action: PadGestureRecognizer.Action? = null, outcome: String? = null, local: Boolean? = null, due: Long? = null) = Unit')
fixture=fixture.replace('    val settings = RetirementSettings()', '    val settings = RetirementSettings()\n    var longClassifications=0L;var nativeActionsRejected=0L;var nativeActionsSubmitted=0L\n    var tapDelaySamples=0L;var tapDelayTotalMs=0L;var tapDelayMaxMs=0L')
fixture=fixture.replace('lateinit var anchorOverlay: Node','lateinit var anchorOverlay: AnchorRegistrationOverlay').replace('anchorOverlay = Node()','anchorOverlay = AnchorRegistrationOverlay(this,::chooseAnchorRegistration)').replace('    val anchorHeldIndicator = Node()', '    val indicatorRenders = mutableListOf<Boolean>()\n    var onIndicatorRender: ((Boolean) -> Unit)? = null\n    val anchorHeldIndicator = AnchorHeldIndicator { value -> indicatorRenders.add(value); onIndicatorRender?.invoke(value) }')
# Production surface complete source; visibility adapter permits direct named-framework testing.
surface=parser.block(activity,r'^private class ProjectorSurface\(').replace('private class ProjectorSurface','internal class ProjectorSurface',1)
surface='''package com.code2hack.eyeprojector.directrg
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
'''+surface+'\n'
# Surface/local/handler/transport are actual source. Widget root and remaining Activity/Session APIs are boundaries.
boundary=boundary.replace('internal data class PointerPosition(val x: Float, val y: Float, val available: Boolean)\n','')
boundary=boundary.replace('    fun inputPosition() = position','''    var onPosition: (() -> Unit)? = null
    var visibleBounds = PointerBounds(0f,0f,1000f,800f)
    var expiresAtNs = Long.MAX_VALUE
    val holding = false
    val downs = 0L
    fun inputSnapshot(): PointerPosition = position
    fun inputPosition(): PointerPosition { onPosition?.invoke();return position }''')
boundary=boundary.replace('    fun cancelTouch() {','    fun prepareCancel() = InputRetirement({}, {false})\n    fun prepareCancel(time:Long) = InputRetirement({}, {false})\n    fun captureCancellation() = InputRetirement({}, {false})\n    fun prepareClear() = InputRetirement({}, {false})\n    fun prepareDismiss() = InputRetirement({}, {false})\n    fun cancelTouch() {')
boundary=boundary.replace('fun uptimeMillis() = time }','fun uptimeMillis() = time; fun elapsedRealtimeNanos() = time*1000000 }')
boundary=boundary.replace('    fun functionIntent(intent: CapturedFunctionIntent<ProjectionSession>): Boolean = true','''    val functions = mutableListOf<FunctionIntent>()
    fun functionIntent(intent: CapturedFunctionIntent<ProjectionSession>): Boolean {functions.add(intent.intent);return true}''')
boundary=boundary.replace('    var pendingCancellations = 0','    var onPendingCancel: (() -> Unit)? = null\n    var pendingCancellations = 0').replace('actualCancelPendingInput(); Calls.events', 'actualCancelPendingInput(); onPendingCancel?.invoke(); Calls.events')
boundary=boundary.replace('    val decoder = Any()', '    fun beginFunction(original:FunctionOriginal)=true\n    fun retireFunction(original:FunctionOriginal)=true\n    val decoder = Any()')
boundary=boundary.replace('    val decoder = Any()', '    var onLocalControl: (ReceiverControlSource,(ReceiverControlDisposition)->Unit)->Unit = {_,complete->complete(ReceiverControlDisposition.NORMAL)}\n    fun localControl(source:ReceiverControlSource, complete:(ReceiverControlDisposition)->Unit):Boolean {onLocalControl(source,complete);return true}\n    val decoder = Any()')
boundary+='\ninternal class RetirementSettings {val scrollSpeed=100}\n'
# Keep exact Session touch/cancellation bodies, with recording only at the TLS send boundary.
session=source(actual/'ProjectionSession.kt');session_touch=parser.block(session,r'^    fun touch\(');session_cancel=parser.block(session,r'^    fun cancelPendingInput\(')
boundary=boundary.replace('    fun touch(event: Touch, time: Long): Boolean = true', session_touch)
boundary=boundary.replace('    private val heldTouch = HeldTouchTransport(controls, { true }, {})', '''    var inputRejected=0L
    val writtenTouches=java.util.concurrent.CopyOnWriteArrayList<Touch>()
    var onTouchWrite: (Touch)->Unit = {}
    private val heldTouch = HeldTouchTransport(controls, { !closed }, { onTouchWrite(it);writtenTouches.add(it) })''')

start=boundary.index('    private fun actualCancelPendingInput()');end=boundary.index('\n    fun navigate(',start)
boundary=boundary[:start]+session_cancel.replace('fun cancelPendingInput()','private fun actualCancelPendingInput()',1)+'\n    fun cancelHeadScroll() = controls.cancelPending("head-scroll")\n'+boundary[end:]
boundary=boundary.replace('internal object Log {', 'internal object Log { fun d(tag:String,message:String) = Unit;')
# Same before four controls/24 later-admission matrix with actual producers (Function start remains per test).
early=frozen_text('EarlyCancelScenarios.kt').replace('fun main() {','fun earlyRetirementScenarios() {',1).replace('a.receivers.single()','a.receivers.first()')
early=early.replace('                a.root.postDelayed(a.confirmTap, 10)\n                a.root.postDelayed(a.edgeTick, 10)\n','').replace('else a.receive(ReceiverInput(ReceiverInputKind.DOWN, a.inputGeneration, 77, 200f, 100f))','else a.receive(ReceiverInput(ReceiverInputKind.DOWN, a.inputGeneration, 77, 200f, 100f))\n                a.root.postDelayed(a.confirmTap, 10)\n                a.root.postDelayed(a.edgeTick, 10)')
files=[]
def add(path,data):files.append((str(path),data))
add('generated/ActivityNavigationWiring.kt',fixture);add('generated/Boundaries.kt',boundary);add('generated/ProjectorSurface.kt',surface);add('generated/EarlyCancelScenarios.kt',early)
for path in sorted((suite/'doubles').glob('*.kt')):
    data=path.read_text()
    if path.name=='Content.kt':data=data.replace('    companion object { const val RECEIVER_EXPORTED', '''    private val inputManager=android.hardware.input.InputManager()
    @Suppress("UNCHECKED_CAST") fun <T> getSystemService(type:Class<T>):T=inputManager as T
    companion object { const val RECEIVER_EXPORTED''')
    add(path,data)
for path in sorted((suite/'retirementdoubles').glob('*.kt')):add(path,path.read_text())
names=['NativePadMotionInput','ReceiverContactInput','NativePadContact','NativeSwipeNavigationInput','NativeFunctionInput','NativePadShellContact','ContextualNavigation','CompletedReceiverInput','SessionPresentation','AnchorRegistrationController','HeldInputOwner','HeldPointerDispatcher','PadGestureRecognizer','HidKeyInput','HidBindings','HidDecisionTrace','FunctionGestureRecognizer','LocalPointerDispatcher','ClassifiedPadLongPress','OrderedControlDispatcher','HeldTouchTransport','EdgeScrollMotion','HeadPointerModel','HeadOrientation']
names += ['AnchorHeldIndicator','AnchorRegistrationOverlay']
add(actual/'InputRetirement.kt',(actual/'InputRetirement.kt').read_text())
for name in names:add(actual/(name+'.kt'),source(actual/(name+'.kt')))
for name in ['Point.kt','Rect.kt']:
    path=root/'directrg/src/test/hidframework/doubles'/name;add(path,path.read_text())
for name in ['Wire','PhoneInput','Touch','LayoutScale','ReceiverUsability','ActivationControl','CameraFeed','FunctionIntent','AnchorMessage','BackHomeGestureRecognizer','ReceiverControlIntent']:add(protocol/(name+'.kt'),source(protocol/(name+'.kt')))
for name in ['RetirementBehaviorScenarios.kt']+([] if a.source_ref else ['RetirementReceiptScenarios.kt']):
    path=suite/name;add(path,path.read_text())
main='''package com.code2hack.eyeprojector.directrg
fun main() {
 val failures=mutableListOf<String>()
 fun scenario(name:String,test:()->Unit){try{test();println("PASS $name")}catch(e:Throwable){failures.add(name);println("FAIL $name: $e");e.printStackTrace(System.out)}}
 scenario("retained-original4-and-later24"){earlyRetirementScenarios()}
 retirementBehaviorScenarios(::scenario)
'''+('' if a.source_ref else ' retirementReceiptScenarios(::scenario)\n')+'''
 println("RESULT retirement failures=${failures.size}")
 check(failures.isEmpty()){failures.joinToString()}
}
'''
add('generated/RetirementMain.kt',main)
inputs=[]
for i,(path,data) in enumerate(files):
    dest=out/'sources'/str(i)/Path(path).name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(data);inputs.append({'path':path,'snapshot':str(dest),'sha256':sha(dest.read_bytes())})
jar=out/'retirement-source.jar'
commands={'compile':[java,'-Xmx768m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+[x['snapshot'] for x in inputs], 'run':[java,'-Xlog:class+load=info:file='+str(out/'class-load.log'),'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.RetirementMainKt']}
record={'scope':__doc__,'sourceRef':a.source_ref,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'inputs':inputs,'activitySha256':sha(activity.encode()),'activityMethods':extracted,'actualSessionTouchBody':session_touch,'actualSessionCancelBody':session_cancel,'sourceBoundary':'Full actual Activity cancellation/factories and ProjectorSurface, actual HID/native Function/raw shell/held/local/completed/classified/pad/ordered queue/transport. Android widgets/detector/Handler/net, actual dispatchPadAction/updateEdgeScroll/schedule bodies; remaining Session fields and TLS touch write are named boundaries. Not full Android Activity/Session/SDK/device.','frozenBeforeRecord':{'path':str(frozen),'sha256':sha(frozen.read_bytes())},'dependencyManifest':{'path':str(a.dependency_manifest),'sha256':sha(a.dependency_manifest.read_bytes())},'dependencies':manifest['jars'],'commands':commands,'runnerSha256':sha(Path(__file__).read_bytes())}
for label,path in [('runner',Path(__file__)),('parser',helper)]:
    snapshot=out/(label+'-source.py');snapshot.write_bytes(path.read_bytes())
    record[label+'Snapshot']={'path':str(path),'snapshot':str(snapshot),'sha256':sha(snapshot.read_bytes())}
(out/'ReceiverActivity-source.kt').write_text(activity)
pointer_source=actual/'PointerOverlay.kt'
pointer_snapshot=out/'PointerOverlay-source.kt';pointer_snapshot.write_bytes(pointer_source.read_bytes())
record['pointerSnapshotBoundary']={'path':str(pointer_source),'snapshot':str(pointer_snapshot),'sha256':sha(pointer_snapshot.read_bytes()),'scope':'Actual inputSnapshot=model.advance age/geometry without callbacks; host pointer model remains named passive position boundary, full SDK separately required'}
try:
    for phase,limit in [('compile',120),('run',60)]:
        with (out/(phase+'.log')).open('w') as log:result=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=limit)
        record[phase+'Exit']=result.returncode
        if phase=='compile' and result.returncode:print((out/'compile.log').read_text());break
        if phase=='run':print((out/'run.log').read_text())
    if record.get('compileExit')==0:
        lines=(out/'class-load.log').read_text().splitlines()
        required=['InputRetirement','ReceiverContactInput','NativePadContact','AnchorHeldIndicator','AnchorRegistrationOverlay','EdgeScrollMotion','ActivityNavigationWiring','ProjectorSurface','HidKeyInput','NativeFunctionInput','NativePadShellContact','HeldInputOwner','HeldPointerDispatcher','LocalPointerDispatcher','CompletedReceiverInput','ClassifiedPadLongPress','PadGestureRecognizer','FunctionGestureRecognizer','OrderedControlDispatcher','HeldTouchTransport']
        record['classLoads']={name:[x for x in lines if '] com.code2hack.eyeprojector.directrg.'+name+' source:' in x] for name in required}
        record['sourceClassesVerified']=all(len(x)==1 and ' source: file:'+str(jar) in x[0] for x in record['classLoads'].values());record['jarSha256']=sha(jar.read_bytes())
finally:(out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
sys.exit(record.get('compileExit') or record.get('runExit',1))
