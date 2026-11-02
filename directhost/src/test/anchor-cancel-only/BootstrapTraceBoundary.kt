package com.code2hack.eyeprojector.directhost

import android.content.Context
import java.io.File
import java.util.UUID

/** Android binding/executor/status observers only; START/config/performPending bodies are extracted. */
internal class BootstrapTraceWiring(private val context: Context, service: ShizukuHostService) {
    private enum class Action { START, STOP }
    private enum class Setup { AVAILABLE }
    private val executor = java.util.concurrent.Executor { it.run() }
    private val forget = object { fun pending()=false }
    private val contextPublisher = object { fun capture()=Unit }
    private val current = object { val backend="STOPPED" }
    private var revokedBinder: BootstrapBinderBoundary?=null
    private var pending: Action?=null
    private var pendingEpoch=""
    private var localDetail: String?=null
    private var remote: BootstrapRemoteBoundary?=BootstrapRemoteBoundary(service)
    var failure: String?=null
    var statusResult: String?=null
    private fun setup()=Setup.AVAILABLE
    private fun publish(state:String,detail:String?=null,sessionEpoch:String="",rejectedEpoch:String?=null) { if(detail=="OPERATION_FAILED")failure=detail }
    private fun readStatus(result:String,source:Unit) {statusResult=result}
    private fun bind():Nothing=error("Binding is an explicit already-connected Android boundary")
    private fun finishRevokedStop(binder:BootstrapBinderBoundary):Nothing=error("STOP is outside this START proof")
// ACTUAL_FIELDS_AND_METHODS
}
internal class BootstrapBinderBoundary {val isBinderAlive=true}
internal class BootstrapRemoteBoundary(private val actual:ShizukuHostService) {
    fun start(identity:ByteArray,password:ByteArray,legacyPeer:ByteArray?,endpoint:ByteArray,pairedPeer:ByteArray?,sessionEpoch:String)=actual.start(identity,password,legacyPeer,endpoint,pairedPeer,sessionEpoch)
    fun stop()=actual.stop()
    fun destroy():Nothing=error("STOP boundary unused")
    fun asBinder()=BootstrapBinderBoundary()
}
/** Only the unused STOP result parser is replaced; START status remains the real returned bytes. */
internal class BootstrapStatusBoundary(private val text:String) {
    fun getString(name:String):String=error("STOP parser is outside this START proof")
    fun optBoolean(name:String)=false
}
