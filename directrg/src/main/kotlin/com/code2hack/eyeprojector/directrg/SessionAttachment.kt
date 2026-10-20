package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState

/** An epoch is attempted once. Polling and old readiness cannot undo a local Disconnect. */
internal class SessionAttachment {
    private val consumed = mutableSetOf<String>()
    var epoch: String? = null
        private set

    fun available(state: SessionState): Boolean {
        if (epoch != null || state.phase != SessionPhase.AVAILABLE || state.epoch in consumed) return false
        consumed.add(state.epoch)
        epoch = state.epoch
        return true
    }

    fun release(): String? = epoch.also { epoch = null }

    fun suppress(epoch: String) { consumed.add(epoch) }
}
