package com.code2hack.eyeprojector.directhost.activation

/** Local injection seam; neither method is an admission, transport or platform wake result. */
internal interface PhoneHidActivation {
    /** Pure current-state snapshot. Must not publish feedback or reenter input. */
    fun needsWake(): Boolean
    /** May reenter input, including Stop, a terminal edge or another physical DOWN. */
    fun request(cause: ActivationCause)

    /** Shared Phone session integration is separately allocated; preserve existing routing meanwhile. */
    object Unwired : PhoneHidActivation {
        override fun needsWake() = false
        override fun request(cause: ActivationCause) = Unit
    }
}
