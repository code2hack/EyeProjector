package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidEdge

/** One fresh-DOWN decision. Route closure holds primitives/original proxy, never a native mapping. */
internal data class PhoneHidCapture(
    val disposition: PhoneHidDisposition,
    val wake: HidWakeRequest? = null,
    val route: ((PhoneHidEdge) -> PhoneHidRoute?)? = null,
)

/** Local injection seam; a capture is not a platform wake or injection result. */
internal interface PhoneHidActivation {
    /** Pure current-state snapshot. Must not publish feedback or reenter input. */
    fun needsWake(): Boolean
    /** Compatibility defaults let isolated port tests declare their local state explicitly. */
    fun capture(): PhoneHidCapture = PhoneHidCapture(if (needsWake()) PhoneHidDisposition.UNKNOWN else PhoneHidDisposition.USABLE)
    fun request(cause: ActivationCause, capture: PhoneHidCapture) = request(cause)
    /** May reenter input, including Stop, a terminal edge or another physical DOWN. */
    fun request(cause: ActivationCause)

    /** Shared Phone session integration is separately allocated; preserve existing routing meanwhile. */
    object Unwired : PhoneHidActivation {
        override fun needsWake() = false
        override fun request(cause: ActivationCause) = Unit
    }
}
