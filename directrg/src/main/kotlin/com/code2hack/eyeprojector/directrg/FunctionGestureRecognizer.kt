package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.FunctionIntentKind

/**
 * Function owns original gestures, never navigation or an Anchor output contact.
 *
 * Native summaries have no physical IDs. The existing 500 ms quiet interval arbitrates those
 * reports: CLICK/DOUBLE/TRIPLE describe at least that many original shorts, rather than adding
 * clicks to the observed edges. Edge-only HID input completes each short on its matching UP.
 * The native interval is a modeled duplicate boundary, not measured firmware timing.
 */
internal class FunctionGestureRecognizer(
    private val now: () -> Long,
    private val context: () -> Any?,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (Gesture) -> Unit,
    val timing: Timing = Timing(),
    private val edgeOnly: Boolean = false,
    private val capture: (Any) -> Any? = { it },
    private val identity: Identity = Identity(),
) {
    /** Source adapters retain this across Activity recreation within the Receiver incarnation. */
    class Identity {
        private val gesture = java.util.concurrent.atomic.AtomicLong()
        private val delivery = java.util.concurrent.atomic.AtomicLong()
        fun gesture() = gesture.getAndIncrement()
        fun delivery() = delivery.getAndIncrement()
    }
    enum class Report { DOWN, UP, CLICK, DOUBLE, TRIPLE, LONG }
    data class Timing(val quietMs: Long = 500, val longMs: Long = 800)
    data class Gesture(val kind: FunctionIntentKind, val gestureId: Long, val sequence: Long, val original: Any)
    private class Original(val id: Long, val captured: Any) {
        var kind = FunctionIntentKind.ACTIVATE
        var hasEdge = false
    }
    private class Sequence(val owner: Any) {
        val originals = mutableListOf<Original>()
        var held: Original? = null
        var downAt = 0L
        var releasedAt = 0L
    }
    private var sequence: Sequence? = null
    private var cancelTimer: (() -> Unit)? = null
    private var timerGeneration = 0L
    private var ownershipGeneration = 0L
    private var retiredUntil = Long.MIN_VALUE
    private var retiredHeld = false

    fun accept(report: Report, at: Long = now()) {
        val owner = context()
        if (owner == null) { cancel(); return }
        if (sequence?.owner?.let { it != owner } == true) cancel()
        if (retiredHeld) {
            if (report == Report.UP) retiredHeld = false
            retiredUntil = at + timing.quietMs
            return
        }
        if (sequence == null && report != Report.DOWN && at < retiredUntil) {
            retiredUntil = at + timing.quietMs
            return
        }
        if (edgeOnly && report != Report.DOWN && sequence == null) return
        val s = sequence ?: Sequence(owner).also { sequence = it }
        when (report) {
            Report.DOWN -> if (s.held == null) {
                val original = s.originals.firstOrNull { !it.hasEdge } ?: original(s) ?: return
                original.hasEdge = true
                s.held = original; s.downAt = at
            }
            Report.UP -> {
                val held = s.held
                if (held != null) {
                    s.held = null
                    if (at - s.downAt >= timing.longMs) {
                        held.kind = FunctionIntentKind.REGISTER
                        finish(s); return
                    }
                } else if (s.originals.isEmpty() && original(s) == null) return
                s.releasedAt = at
                if (edgeOnly) { finish(s); return }
            }
            Report.CLICK, Report.DOUBLE, Report.TRIPLE -> {
                // A completed summary closes a missing/reordered UP for the same original.
                s.held = null
                val count = when (report) { Report.TRIPLE -> 3; Report.DOUBLE -> 2; else -> 1 }
                while (s.originals.size < count) if (original(s) == null) return
                s.releasedAt = at
            }
            Report.LONG -> {
                val original = s.held ?: s.originals.lastOrNull() ?: original(s) ?: return
                original.kind = FunctionIntentKind.REGISTER
                finish(s); return
            }
        }
        arm(s)
    }

    private fun original(s: Sequence): Original? {
        val captured = capture(s.owner) ?: run { cancel(); return null }
        return Original(identity.gesture(), captured).also { s.originals.add(it) }
    }

    /** A fresh DOWN can acquire ownership after cancellation, even if the old UP never arrives. */
    fun cancel() {
        ownershipGeneration++
        retiredHeld = false
        if (sequence != null) retiredUntil = now() + timing.quietMs
        sequence = null
        cancelScheduled()
    }

    private fun cancelScheduled() {
        timerGeneration++
        cancelTimer?.invoke(); cancelTimer = null
    }

    private fun arm(s: Sequence) {
        cancelScheduled()
        val generation = timerGeneration
        val deadline = if (s.held != null) s.downAt + timing.longMs else s.releasedAt + timing.quietMs
        cancelTimer = schedule(deadline) {
            if (generation == timerGeneration && sequence === s) {
                if (context() != s.owner) cancel()
                else if (now() < deadline) arm(s)
                else {
                    s.held?.kind = FunctionIntentKind.REGISTER
                    finish(s)
                }
            }
        }
    }

    private fun finish(s: Sequence) {
        retiredHeld = s.held != null
        retiredUntil = now() + timing.quietMs
        sequence = null
        cancelScheduled()
        val generation = ownershipGeneration
        // Retire before callbacks. They can synchronously cancel or replace this input context.
        for (original in s.originals) {
            if (generation != ownershipGeneration || context() != s.owner) return
            dispatch(Gesture(original.kind, original.id, identity.delivery(), original.captured))
        }
    }
}
