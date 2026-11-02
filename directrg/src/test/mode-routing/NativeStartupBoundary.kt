package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.content.*
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Session/OS presentation boundaries only; methods marked ACTUAL are inserted verbatim. */
internal class ProjectionSession {
    val cycles=AtomicInteger()
    fun cycleMode() { cycles.incrementAndGet() }
    fun presentation(active:Boolean) {}
}
internal object NativePadReader {
    const val SOCKET_NAME="eyeprojector-native-pad-99"
    const val POWER_SOCKET_NAME="eyeprojector-native-power-99"
}
internal class Part {
    fun start() {};fun stop() {};fun close() {}
}
internal data class ReceiverLaunchScope(val id:String="scope",val trigger:String="",val task:Int=1)
internal object ReceiverWake {
    const val ACTIVATION="activation"
    fun readIntent(intent:Intent):Any?=null
}
internal class ReceiverSessionService {
    class LocalBinder(val owner:ReceiverSessionOwner):IBinder {
        fun detachCameraPermissionHost(host:Any) {}
    }
}
internal class ReceiverSessionOwner(private val context:Context):AutoCloseable {
    interface View
    private var closed=false
    private var nativeFunction:NativeFunctionInput?=null
    private var nativePad:NativePadShellContact?=null
    var session:ProjectionSession?=ProjectionSession()
    private var listener:View?=null
    private var uiUsable=false
    private var displayed=false
    private var window:Surface?=null
    private val windowGeneration=AtomicLong()
    private val output:Output?=Output()
    private class Output { fun attach(surface:Surface?,generation:Long) {} }
    private fun failOutput() { error("Unexpected presentation failure") }
    private fun publish() {}
    fun presentation(view:View,usable:Boolean,panel:Boolean) { if(listener===view) uiUsable=usable }
    fun disconnect(complete:(()->Unit)?=null,scope:ReceiverLaunchScope?=null,activationId:String?=null) { complete?.invoke() }
    fun launch(scope:ReceiverLaunchScope) {}
    private class Attachment(val epoch:String?="owner")
    private val attachment=Attachment()
    private class Peer(val epoch:String,val phase:Phase)
    private class Phase(val active:Boolean)
    private val peer:Peer?=null
    private val cameraOwner:Any?=null
    private class Client { fun close(epoch:String?) {} }
    private val client=Client()
    private fun releaseMedia() { session=null }
    // ACTUAL_OWNER_METHODS
}
internal class ActivityNativeCaller:Activity(),ServiceConnection {
    private var owner:ReceiverSessionOwner?=null
    private var serviceBinder:ReceiverSessionService.LocalBinder?=null
    private var pendingExit:Pair<ReceiverLaunchScope,String?>?=null
    private var pendingForget:(()->Unit)?=null
    private var bindingValid=true
    private var disconnectBeforeBinding=false
    private var connectBeforeBinding=false
    private val launchScope=ReceiverLaunchScope()
    private var visibleActivation:String?=null
    private var resumed=false
    private lateinit var root:Any
    private lateinit var pointer:Part;private lateinit var longTouch:Part;private lateinit var padContact:Part;private lateinit var nativeSwipeInput:Part
    private val viewToken=Any()
    val touches=CopyOnWriteArrayList<HeldPointerDispatcher.Event>()
    private val heldOwner=HeldInputOwner(HeldPointerDispatcher({PointerPosition(120f,230f,true)}) {
        { event -> touches.add(event);true }
    })
    private var shellPadContact=NativePadShellContact(heldOwner,{viewToken},{PointerPosition(120f,230f,true)},{},{})
    private var functionInput=NativeFunctionInput(this,{owner?.session},{(it as ProjectionSession).cycleMode()})
    private val sessionView=object:ReceiverSessionOwner.View {}
    private val cameraPermissionHost=Any()
    private val cameraPermissionRequests=mutableMapOf<Any,Any>()
    private var bound=true
    private val serviceConnection:ServiceConnection get()=this
    private val pairingPanel=Part()
    init { root=Any();pointer=Part();longTouch=Part();padContact=Part();nativeSwipeInput=Part() }
    private fun validWake(local:ReceiverSessionService.LocalBinder,intent:Intent)=true
    private fun registerTask(scope:ReceiverLaunchScope,activationId:String?) {}
    private fun attachCameraPermissionHost() {}
    private fun attachPresentation() { owner?.attach(sessionView,null) }
    private fun applyReceiverDemand(demand:Any) {}
    private fun cancelInputOperation(cancelFunction:Boolean,reason:String) { shellPadContact.cancel() }
    private fun localInputVisible()=false
    fun selectedPad()=shellPadContact
    fun selectedFunction()=functionInput
    // ACTUAL_ACTIVITY_METHODS
}
