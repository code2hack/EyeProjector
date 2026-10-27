package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidEdge

/** Records the exact444 facade contract; capture itself submits exactly one observed DOWN. */
class PhoneSessionController {
    data class Call(val kind: String, val route: PhoneHidRoute?, val edge: PhoneHidEdge?, val at: Long?)
    val calls = mutableListOf<Call>()
    val bootstrap=HostBootstrap()
    internal fun captureHidAuthority()=com.code2hack.eyeprojector.directhost.activation.HidInputAuthority(null,null,ProjectionContext(),0,0,0)
    internal fun isHidAuthorityCurrent(scope:com.code2hack.eyeprojector.directhost.activation.HidInputAuthority)=true
    internal fun retireHidRegion(scope:com.code2hack.eyeprojector.directhost.activation.HidInputAuthority)=Unit
    internal fun installHidRegion(scope:com.code2hack.eyeprojector.directhost.activation.HidInputAuthority,region:String)=scope
    internal fun requestHidActivation(wake:com.code2hack.eyeprojector.directhost.activation.HidWakeRequest,original:com.code2hack.eyeprojector.directhost.activation.HidInputAuthority?=null)=requestActivation(com.code2hack.eyeprojector.directhost.activation.ActivationCause.HID_PRESS)
    fun observeState(observer:()->Unit)=Unit
    fun needsActivation() = false
    internal fun requestActivation(cause: com.code2hack.eyeprojector.directhost.activation.ActivationCause) =
        com.code2hack.eyeprojector.directhost.activation.ActivationHandle("fixture",cause)
    fun onHidInputInvalidated(action:(Boolean)->Unit)=Unit
    fun onInputInvalidated(action: () -> Unit) = Unit
    var next = 0
    var targetAvailable = true
    var onCapture: (PhoneHidEdge) -> Unit = {}
    fun capturePhoneHidRoute(down: PhoneHidEdge): PhoneHidRoute? {
        val route = if (targetAvailable) PhoneHidRoute(++next) else null
        calls.add(Call("capture", route, down, null))
        onCapture(down)
        return route
    }
    fun submitPhoneHid(route: PhoneHidRoute, edge: PhoneHidEdge) { calls.add(Call("submit", route, edge, null)) }
    fun cancelPhoneHid(route: PhoneHidRoute, eventTimeMs: Long) { calls.add(Call("cancel", route, null, eventTimeMs)) }
    fun clear() { calls.clear(); targetAvailable = true; onCapture = {} }
    companion object {
        val fixture = PhoneSessionController()
        fun get(context: Context) = fixture
    }
}

/** Deliberately unavailable native setup; isolated controller tests select an explicit local port. */
class HostBootstrap {
 internal fun capturePhoneHidSnapshot(expected:ProjectionContext):com.code2hack.eyeprojector.directhost.activation.snapshot.PhoneHidSnapshotSource?=null
}
data class ProjectionContext(val ownerEpoch:String="",val projectionToken:String="") {
 val available get()=ownerEpoch.isNotEmpty()&&projectionToken.isNotEmpty()
}
