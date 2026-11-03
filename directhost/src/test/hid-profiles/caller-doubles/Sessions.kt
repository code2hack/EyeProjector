package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.activation.snapshot.PhoneHidSnapshotSource
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidEdge

internal data class ProjectionContext(val available: Boolean = true)
internal object UserControls { const val RECOGNITION = "recognition" }
internal class PhoneSessionController {
    val bootstrap = Bootstrap()
    val calls = mutableListOf<Triple<String, PhoneHidRoute, PhoneHidEdge?>>()
    var next = 0
    fun userControl() = true
    fun captureHidAuthority() = HidInputAuthority(null, null, ProjectionContext(), 0, 0, 0)
    fun isHidAuthorityCurrent(authority: HidInputAuthority) = true
    fun retireHidRegion(authority: HidInputAuthority) = Unit
    fun installHidRegion(authority: HidInputAuthority, region: String): HidInputAuthority? = authority
    fun requestHidActivation(wake: HidWakeRequest, original: HidInputAuthority? = null) = ActivationHandle()
    fun onHidInputInvalidated(callback: (Boolean) -> Unit) = Unit
    fun observeState(callback: () -> Unit) = Unit
    fun capture(down: PhoneHidEdge): PhoneHidRoute = PhoneHidRoute(++next).also { calls.add(Triple("capture", it, down)) }
    fun submitPhoneHid(route: PhoneHidRoute, edge: PhoneHidEdge) { calls.add(Triple("submit", route, edge)) }
    fun cancelPhoneHid(route: PhoneHidRoute, at: Long) { calls.add(Triple("cancel", route, null)) }
    companion object {
        val fixture = PhoneSessionController()
        fun get(context: Context) = fixture
    }
}
internal class Bootstrap {
    val modeOwner = ModeOwner()
    fun userControl(kind: String) = true
    fun capturePhoneHidSnapshot(context: ProjectionContext): PhoneHidSnapshotSource? = null
}
internal class ModeOwner { fun captureOriginal(): com.code2hack.eyeprojector.directhost.input.PhoneHidOriginal? = null }
