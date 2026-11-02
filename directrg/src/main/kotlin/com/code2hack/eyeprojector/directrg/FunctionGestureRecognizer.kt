package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.AnchorIntentKind

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
    private val captureOriginal: ((Any, Long, Long?) -> Any?)? = null,
    private val retireOriginal: ((Long, Any) -> Unit)? = null,
    private val registrationRelease: (Gesture, Boolean) -> Unit = { _, _ -> },
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
    data class Gesture(val kind: AnchorIntentKind, val gestureId: Long, val sequence: Long, val original: Any, val physicalHeld: Boolean = false)
    private class Original(val id: Long, val captured: Any) {
        var kind = AnchorIntentKind.ACTIVATE
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
    private var registrationHold: Gesture? = null
    val awaitingRegistrationRelease: Boolean get() = registrationHold != null

    /** Read-only existing ownership/schedule identity, including sequences which already finished. */
    internal fun retirementState(): Any = listOf(sequence, ownershipGeneration, timerGeneration, registrationHold)

    fun accept(report: Report, at: Long = now()) {
        // This original terminal edge survives only the intentional chooser transition.
        // Summaries are never evidence that the physical button was released.
        registrationHold?.let { original ->
            if (report == Report.UP) {
                registrationHold = null; retiredHeld = false; retiredUntil = at + timing.quietMs
                registrationRelease(original, false)
                return
            }
            if (report != Report.DOWN) return
            registrationHold = null
            registrationRelease(original, true)
            retiredHeld = false; retiredUntil = at + timing.quietMs
            return // A new press cancels this chooser; its UP is not a fresh Short.
        }
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
                        held.kind = AnchorIntentKind.REGISTER
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
                while (s.originals.size < count) if (original(s,s.originals.firstOrNull()?.id) == null) return
                s.releasedAt = at
            }
            Report.LONG -> {
                val original = s.held ?: s.originals.lastOrNull() ?: original(s) ?: return
                original.kind = AnchorIntentKind.REGISTER
                finish(s); return
            }
        }
        arm(s)
    }

    private fun original(s: Sequence, summaryOriginal: Long? = null): Original? {
        val id=identity.gesture()
        val captured = (captureOriginal?.invoke(s.owner,id,summaryOriginal) ?: if(captureOriginal==null)capture(s.owner) else null)
            ?: run { cancel(); return null }
        if(sequence!==s||context()!=s.owner){retireOriginal?.invoke(id,captured);return null}
        return Original(id, captured).also { s.originals.add(it) }
    }

    /** A fresh DOWN can acquire ownership after cancellation, even if the old UP never arrives. */
    fun cancel(preserveRegistration: Boolean = false) {
        ownershipGeneration++
        retiredHeld = false
        if (sequence != null) retiredUntil = now() + timing.quietMs
        val registered = if (preserveRegistration) null else registrationHold.also { registrationHold = null }
        val originals=sequence?.originals?.toList().orEmpty()
        sequence = null
        InputRetirement.finishAll(listOf<() -> Unit>({cancelScheduled()}, { registered?.let { registrationRelease(it, true) }; Unit })+
            originals.map { original -> {retireOriginal?.invoke(original.id,original.captured);Unit} })
    }

    private fun cancelScheduled() {
        timerGeneration++
        val original=cancelTimer;cancelTimer=null;original?.invoke()
    }

    private fun arm(s: Sequence) {
        cancelScheduled()
        if(sequence!==s)return
        val generation = timerGeneration
        val deadline = if (s.held != null) s.downAt + timing.longMs else s.releasedAt + timing.quietMs
        val cancel = schedule(deadline) {
            if (generation == timerGeneration && sequence === s) {
                if (context() != s.owner) cancel()
                else if (now() < deadline) arm(s)
                else {
                    s.held?.kind = AnchorIntentKind.REGISTER
                    finish(s)
                }
            }
        }
        if(timerGeneration==generation&&sequence===s)cancelTimer=cancel else cancel()
    }

    private fun finish(s: Sequence) {
        retiredHeld = s.held != null
        retiredUntil = now() + timing.quietMs
        sequence = null
        cancelScheduled()
        val generation = ownershipGeneration
        // Retire before callbacks. They can synchronously cancel or replace this input context.
        var next=0
        try { for (original in s.originals) {
            if (generation != ownershipGeneration || context() != s.owner) return
            val gesture = Gesture(original.kind, original.id, identity.delivery(), original.captured,
                original.kind == AnchorIntentKind.REGISTER && s.held === original && original.hasEdge)
            if (gesture.physicalHeld) registrationHold = gesture
            dispatch(gesture);next++
        } } finally {
            InputRetirement.finishAll(s.originals.drop(next).map { original ->
                {retireOriginal?.invoke(original.id,original.captured);Unit}
            })
        }
    }
}
