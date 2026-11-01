package com.code2hack.eyeprojector.protocol

/**
 * Learned Back/Home edges, independent of Function and pointer aiming.
 * Callers serialize access and retain their original physical press and context identities.
 * The arbitration interval reuses the existing HID double-press interval.
 */
class BackHomeGestureRecognizer<C : Any>(
    private val now: () -> Long,
    private val current: (C) -> Boolean,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (Action, C) -> Unit,
    val doublePressMs: Long = DEFAULT_DOUBLE_PRESS_MS,
) {
    companion object { const val DEFAULT_DOUBLE_PRESS_MS = 500L }

    /** Source receipt membership before any consumer queue/lock. No dispatch, timer or permission.
     * Uses this recognizer's existing source/held/release interval grammar even for discarded edges. */
    class Receipts<C : Any>(private val doublePressMs: Long = DEFAULT_DOUBLE_PRESS_MS) {
        private data class Sequence<C>(val first: Any, val latest: Any, val held: Any?, val source: Any,
            val original: C, val releasedAt: Long = 0)
        private val sequence = java.util.concurrent.atomic.AtomicReference<Sequence<C>?>()
        fun down(press: Any, source: Any, original: C, at: Long): C {
            while (true) {
                val old = sequence.get()
                if (old != null && (old.first == press || old.latest == press)) return old.original
                val extends = old != null && old.source == source && old.held == null && at < old.releasedAt + doublePressMs
                val next = if (extends) old!!.copy(latest = press, held = press)
                    else Sequence(press, press, press, source, original)
                if (sequence.compareAndSet(old, next)) return next.original
            }
        }
        fun up(press: Any, at: Long) {
            while (true) {
                val old = sequence.get() ?: return
                if (old.held != press) return
                val next = if (old.first != press) null else old.copy(held = null, releasedAt = at)
                if (sequence.compareAndSet(old, next)) return
            }
        }
        fun cancel(press: Any? = null) {
            while (true) {
                val old = sequence.get() ?: return
                if (press != null && old.first != press && old.latest != press) return
                if (sequence.compareAndSet(old, null)) return
            }
        }
    }

    enum class Action { BACK, HOME }
    private class Sequence<C>(val first: Any, val source: Any, val captured: C) {
        var latest = first
        var held: Any? = first
        var releasedAt = 0L
    }
    private var sequence: Sequence<C>? = null
    private var cancelTimer: (() -> Unit)? = null
    private var generation = 0L

    /** Read-only membership: an expired callback may have installed another sequence. */
    fun capturedSequence(press: Any): C? = sequence?.takeIf {
        it.first == press || it.latest == press
    }?.captured

    fun down(press: Any, source: Any, captured: C) {
        var old = sequence
        if (old != null && (old.first == press || old.latest == press)) return
        if (old != null && (!current(old.captured) || old.source != source || old.held != null)) {
            cancel()
            old = null
        }
        if (old != null && now() >= old.releasedAt + doublePressMs) {
            finish(old, Action.BACK)
            // Dispatch can synchronously change context or admit another sequence.
            if (sequence != null) return
            old = null
        }
        if (!current(captured)) return
        if (old == null) sequence = Sequence(press, source, captured)
        else {
            cancelScheduled()
            old.latest = press
            old.held = press
        }
    }

    fun up(press: Any, at: Long) {
        val old = sequence ?: return
        if (old.held != press) return // An unmatched/duplicate UP cannot complete a press.
        old.held = null
        if (!current(old.captured)) { cancel(); return }
        if (old.first != press) finish(old, Action.HOME)
        else {
            old.releasedAt = at
            arm(old)
        }
    }

    /** A late original-route cancellation must not retire a successor sequence. */
    fun cancel(press: Any? = null) {
        val old = sequence
        if (press != null && (old == null || press != old.first && press != old.latest)) return
        sequence = null
        cancelScheduled()
    }

    private fun cancelScheduled() {
        generation++
        val old = cancelTimer
        cancelTimer = null
        old?.invoke()
    }

    private fun arm(old: Sequence<C>) {
        cancelScheduled()
        val expected = generation
        val deadline = old.releasedAt + doublePressMs
        val scheduledCancel = schedule(deadline) {
            if (generation != expected || sequence !== old) return@schedule
            if (!current(old.captured)) cancel()
            else if (now() < deadline) arm(old)
            else finish(old, Action.BACK)
        }
        if (generation == expected && sequence === old) cancelTimer = scheduledCancel else scheduledCancel()
    }

    private fun finish(old: Sequence<C>, action: Action) {
        sequence = null // Retire before navigation can synchronously cancel or replace context.
        cancelScheduled()
        if (current(old.captured)) dispatch(action, old.captured)
    }
}
