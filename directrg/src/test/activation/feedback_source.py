"""Preserve complete actual Activity callback/resize/cancellation bodies on named UI boundaries."""
import hashlib,importlib.util,re

def generate(root,out):
    parser=root/'directhost/src/test/hid/run.py'
    spec=importlib.util.spec_from_file_location('feedback_extract',parser);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    activity=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt';source=activity.read_text()
    names=['showSnapshot','cancelInput','publishHeadPointer','resize','attachPresentation','applyReceiverDemand','validWake']
    blocks=[m.block(source,rf'^    private fun {name}\(') for name in names]
    blocks += [re.search(r'^    private fun '+name+r'\(\).*$',source,re.M).group() for name in ['usable','projectedInput']]
    state=m.block(source,r'^        override fun state\(')
    blocks += [m.block(source,r'^    override fun onNewIntent\(')]
    wake=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverWake.kt'
    wake_source=wake.read_text()
    parse=m.block(wake_source,r'^        fun readIntent\(')
    constants='\n'.join(re.findall(r'^        const val .*$',wake_source,re.M))
    header='''package com.code2hack.eyeprojector.directrg
import android.content.Intent
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.os.SystemClock
import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.util.UUID
import org.json.JSONObject
internal class UiPart {val holder=objectHolder();class objectHolder {val surface=android.view.Surface()};fun cancel(vararg at:Long)=Unit;fun clear()=Unit;fun cancelTouch()=Unit;var layoutParams=FrameLayout.LayoutParams(0,0,0)}
internal class UiNode {var width=100;var height=100;var visibility=View.VISIBLE;var text="";fun removeCallbacks(r:Runnable)=Unit}
internal class UiPointer {val position=PointerPosition(1f,1f,false);val expiresAtNs=Long.MAX_VALUE;fun stop()=Unit;fun start()=Unit;fun bounds(l:Float,t:Float,r:Float,b:Float,rotation:Int)=Unit}
internal object R {object string {const val disconnect=1;const val connect=2;const val connected=3;const val connecting=4;const val disconnected=5}}
internal open class UiActivityBoundary {open fun onNewIntent(intent:Intent)=Unit}
internal class ReceiverSessionService {class LocalBinder(val feature:ReceiverActivationStore)}
internal class ReceiverUiFeedbackSource(private val owner:ReceiverSessionOwner,private val filesDir:File,feature:ReceiverActivationStore?=null):UiActivityBoundary(),ReceiverSessionOwner.View {
 private val serviceBinder=feature?.let{ReceiverSessionService.LocalBinder(it)}
 private var intent=Intent()
 private fun setIntent(value:Intent){intent=value}
 private var bindingValid=true
 private var pendingExit:Pair<ReceiverLaunchScope,String?>?=null
 private var appliedDemand:ReceiverDemand?=null
 private var session:ProjectionSession?=null
 private var currentGeometry:Geometry?=null
 private var streaming=false
 var resumed=true;var focus=true
 private val taskId=7
 private var visibleActivation:String?=null
 private val registeredActivations=mutableSetOf<String>()
 private var connectRequested=false
 private val presentation=SessionPresentation()
 private val root=UiNode();private val connectionView=UiNode();private val connectButton=UiNode()
 private lateinit var pointer:UiPointer
 private lateinit var screen:UiPart
 private lateinit var hidInput:UiPart;private lateinit var functionInput:UiPart;private lateinit var gestures:UiPart
 private lateinit var heldOwner:UiPart;private lateinit var heldInput:UiPart;private lateinit var padContact:UiPart
 private lateinit var localInput:UiPart;private lateinit var completedInput:UiPart
 private var inputGeneration=0L;private var edgeScheduled=false
 private val edgeMotion=object{fun stop()=Unit}
 private val confirmTap=Runnable{};private val edgeTick=Runnable{}
 private val display:Display?=null
 private class Display(val rotation:Int)
 val sessionView:ReceiverSessionOwner.View get()=this
 var depth=0;var maxDepth=0;var callbacks=0
 init{pointer=UiPointer();screen=UiPart()}
 private fun hasWindowFocus()=focus
 private fun registerTask(scope:ReceiverLaunchScope,activation:String?)=Unit
 private fun getString(id:Int)=id.toString()
 private fun showStatus(value:String)=Unit
 override fun input(source:ProjectionSession,event:ReceiverInput)=Unit
 private fun countedSnapshot(snapshot:ReceiverSessionOwner.Snapshot){
  callbacks++;depth++;check(depth<8){"Actual Activity callback recursion"};maxDepth=maxOf(maxDepth,depth)
  try{showSnapshot(snapshot)}finally{depth--}
 }
 fun produce()=publishHeadPointer()
 fun apply(d:ReceiverDemand)=applyReceiverDemand(d)
 fun attach()=attachPresentation()
 fun selectLocal(){presentation.showPanel();publishHeadPointer()}
 fun restore(d:ReceiverDemand,panel:Boolean){appliedDemand=d;presentation.restoreSelection(panel,true)}
 fun retire(){bindingValid=false}
 fun pendingExit(){pendingExit=ReceiverLaunchScope("exit","exitT",7) to null}
 fun delivered():Intent=intent
 val panel get()=presentation.panelVisible
'''
    # Only insert observation around the callback; all method bodies and cancellation/resize feedback remain exact.
    generated=out/'ReceiverUiFeedbackSource.kt';generated.write_text(header+state.replace('showSnapshot(snapshot)','countedSnapshot(snapshot)')+'\n'+'\n'.join(blocks)+'\n}\ninternal object ReceiverWake {\n'+constants+'\n'+parse+'\n}\n')
    return generated,{'source':str(activity),'parser':str(parser),'methods':[{ 'sha256':hashlib.sha256(b.encode()).hexdigest(),'body':b} for b in [state]+blocks], 'wakeSource':str(wake),'wakeParserBody':parse,'scope':'Complete Activity method bodies and callback, observation-only callback wrapper, explicit UI/pointer/native cancel boundaries'}
