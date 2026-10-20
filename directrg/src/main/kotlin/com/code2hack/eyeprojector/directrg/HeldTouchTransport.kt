package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Touch

/** Same executor as taps and navigation. Cancellation follows an already started DOWN. */
internal class HeldTouchTransport(
    private val controls: OrderedControlDispatcher,
    private val isCurrent: (Touch) -> Boolean,
    private val write: (Touch) -> Unit,
) {
    private var submitted: Touch? = null
    private var contactId: Long? = null
    // Executor-owned: cancellation is queued behind the in-flight write, never races its result.
    private var written: Touch? = null
    @Volatile private var epoch = 0L
    @Volatile private var releaseGeneration = 0L
    @Volatile private var releasedGeneration = 0L

    @Synchronized fun submit(event: Touch, timeMs: Long): Boolean {
        if (event.phase == Touch.Phase.CANCEL) {
            if (contactId != event.contactId) return false
            controls.cancelPending()
            return cancel(timeMs)
        }
        if (!isCurrent(event)) return false
        when (event.phase) {
            Touch.Phase.DOWN -> if (submitted != null) return false
            else -> if (submitted?.contactId != event.contactId) return false
        }
        val generation = epoch
        val contactGeneration = if (event.phase == Touch.Phase.DOWN) releaseGeneration + 1 else releaseGeneration
        val queued = controls.submit(timeMs, { generation == epoch && isCurrent(event) }, "held-touch") {
            write(event)
            written = if (event.phase == Touch.Phase.DOWN || event.phase == Touch.Phase.MOVE) event else null
            if (event.phase == Touch.Phase.UP) releasedGeneration = maxOf(releasedGeneration, contactGeneration)
        }
        if (queued) {
            if (event.phase == Touch.Phase.DOWN) releaseGeneration = contactGeneration
            contactId = event.contactId
            submitted = if (event.phase == Touch.Phase.DOWN || event.phase == Touch.Phase.MOVE) event else null
        }
        return queued
    }

    /** Caller cancels other queued input in the same critical section before calling this. */
    @Synchronized fun cancel(timeMs: Long): Boolean {
        epoch++; submitted = null; contactId = null
        val contactGeneration = releaseGeneration
        if (contactGeneration == releasedGeneration) return true
        return controls.submit(timeMs, { true }, "touch-cancel", preserveTiming = false) {
            written?.let { write(it.copy(phase = Touch.Phase.CANCEL)) }
            written = null
            releasedGeneration = maxOf(releasedGeneration, contactGeneration)
        }
    }
}
