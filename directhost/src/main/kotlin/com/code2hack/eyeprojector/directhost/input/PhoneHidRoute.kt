package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase

/** Opaque original proxy/owner/press capture. Completion means only a local Binder submission. */
class PhoneHidRoute internal constructor(
    private val down: PhoneHidEdge,
    internal val bindingGeneration: Long,
    private val send: (PhoneHidEdge) -> Unit,
) {
    internal fun submit(edge: PhoneHidEdge) {
        if (down.samePress(edge)) runCatching { send(edge) }
    }
    internal fun cancel(eventTimeMs: Long) = submit(down.copy(phase = PhoneHidPhase.CANCEL,
        eventTimeMs = eventTimeMs.coerceAtLeast(down.downTimeMs), repeatCount = 0))
}
