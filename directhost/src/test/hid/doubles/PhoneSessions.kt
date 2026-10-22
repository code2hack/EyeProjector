package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidEdge

/** Records the exact444 facade contract; capture itself submits exactly one observed DOWN. */
class PhoneSessionController {
    data class Call(val kind: String, val route: PhoneHidRoute?, val edge: PhoneHidEdge?, val at: Long?)
    val calls = mutableListOf<Call>()
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
