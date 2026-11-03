package com.code2hack.eyeprojector.protocol

/** Serialized gesture grammar shared by Phone learning and runtime matching.
 * Learning discovers short/multi after 500 ms of silence following UP. A captured saved gesture
 * completes on its matching release; the quiet period only expires incomplete runtime sequences.
 * Long resolves on UP at >=800 ms.
 * Every sequence retains its first context. Cancellation and stale timers cannot finish a successor.
 */
class PhoneHidGestureRecognizer<C : Any>(
    private val now: () -> Long,
    private val current: (C) -> Boolean,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (PhoneHidGesture, C) -> Unit,
    val multiClickMs: Long = 500,
    val longPressMs: Long = 800,
    private val match: (C) -> PhoneHidGesture? = { null },
) {
    private class Sequence<C>(val source: Any, val captured: C, val first: Any, val expected: PhoneHidGesture?) {
        val members = mutableSetOf(first)
        var held: Any? = first
        var downAt = 0L
        var releasedAt = 0L
        var clicks = 0
    }
    private var sequence: Sequence<C>? = null
    private var cancelTimer: (() -> Unit)? = null
    private var generation = 0L

    fun capturedSequence(press: Any): C? = sequence?.takeIf { press in it.members }?.captured

    fun down(press: Any, source: Any, captured: C, at: Long) {
        var old = sequence
        if (old != null && press in old.members) return
        if (old != null && (!current(old.captured) || old.source != source || old.held != null)) {
            cancel(); old = null
        }
        if (old != null && at >= old.releasedAt + multiClickMs) {
            expire(old)
            // Completion may reenter and install its own successor.
            if (sequence != null) return
            old = null
        }
        if (!current(captured)) return
        clearTimer()
        if (old == null) sequence = Sequence(source, captured, press, match(captured)).also { it.downAt = at }
        else { old.members.add(press); old.held = press; old.downAt = at }
    }

    fun up(press: Any, at: Long) {
        val old = sequence ?: return
        if (old.held != press) return
        old.held = null
        if (!current(old.captured) || at < old.downAt) { cancel(); return }
        if (at - old.downAt >= longPressMs) {
            // A hold following clicks is not a valid short/multi prefix or a separate long.
            if (old.clicks == 0) finish(old, PhoneHidGesture.LONG) else cancel()
        } else {
            old.clicks++
            old.releasedAt = at
            val expected = old.expected
            if (expected != null && expected.kind != PhoneHidGesture.Kind.LONG && old.clicks == expected.clicks)
                finish(old, expected)
            else arm(old)
        }
    }

    fun cancel(press: Any? = null) {
        if (press != null && sequence?.members?.contains(press) != true) return
        sequence = null
        clearTimer()
    }

    private fun clearTimer() {
        generation++
        val cancel = cancelTimer
        cancelTimer = null
        cancel?.invoke()
    }

    private fun arm(old: Sequence<C>) {
        clearTimer()
        val expected = generation
        val deadline = old.releasedAt + multiClickMs
        val cancel = schedule(deadline) {
            if (sequence !== old || generation != expected) return@schedule
            if (!current(old.captured)) cancel()
            else if (now() < deadline) arm(old)
            else expire(old)
        }
        if (sequence === old && generation == expected) cancelTimer = cancel else cancel()
    }

    private fun expire(old: Sequence<C>) {
        if (old.expected == null) finish(old, PhoneHidGesture.clicks(old.clicks))
        else cancel()
    }

    private fun finish(old: Sequence<C>, gesture: PhoneHidGesture) {
        if (sequence !== old) return
        sequence = null
        clearTimer()
        if (current(old.captured) && (old.expected == null || old.expected == gesture)) dispatch(gesture, old.captured)
    }
}
