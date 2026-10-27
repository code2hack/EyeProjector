package com.code2hack.eyeprojector.directrg

/**
 * One Function sequence, shared by the native reports and future HID press/release input.
 *
 * Edges count presses; firmware summaries describe that same sequence and only raise its
 * classification. In particular, CLICK and UP are never two presses. Summary-only input
 * needs DOUBLE/TRIPLE reports: identical CLICK reports have no physical identity.
 *
 * The quiet interval is a modeled arbitration interval, not measured Rokid firmware timing.
 * Reports without IDs arriving after that interval cannot be distinguished from fresh input.
 */
internal class FunctionGestureRecognizer(
    private val now: () -> Long,
    private val context: () -> Any?,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (Action) -> Unit,
    val timing: Timing = Timing(),
) {
    enum class Report { DOWN, UP, CLICK, DOUBLE, TRIPLE, LONG }
    enum class Action { SINGLE, DOUBLE, TRIPLE, LONG }
    data class Timing(val quietMs: Long = 500, val longMs: Long = 800)
    private class Sequence(val context: Any) {
        var held = false
        var downAt = 0L
        var releasedAt = 0L
        var presses = 0
        var summary = 0
    }

    private var sequence: Sequence? = null
    private var cancelTimer: (() -> Unit)? = null
    private var timerGeneration = 0L
    private var retiredUntil = Long.MIN_VALUE
    private var retiredHeld = false

    fun accept(report: Report) {
        val at = now()
        val current = context()
        if (current == null) { cancel(); return }
        if (sequence?.context?.let { it != current } == true) cancel()
        // A held long press must release before another DOWN can start a sequence.
        if (retiredHeld) {
            if (report == Report.UP) retiredHeld = false
            retiredUntil = at + timing.quietMs
            return
        }
        if (sequence == null && report != Report.DOWN && at < retiredUntil) {
            retiredUntil = at + timing.quietMs
            return
        }
        val s = sequence ?: Sequence(current).also { sequence = it }
        when (report) {
            Report.DOWN -> if (!s.held) { s.held = true; s.downAt = at }
            Report.UP -> {
                if (s.held) {
                    s.held = false
                    if (at - s.downAt >= timing.longMs) { finish(s, Action.LONG); return }
                    s.presses = (s.presses + 1).coerceAtMost(3)
                } else s.summary = maxOf(s.summary, 1)
                s.releasedAt = at
            }
            Report.CLICK, Report.DOUBLE, Report.TRIPLE -> {
                // A completed-click summary also closes a missing/reordered UP.
                if (s.held) { s.held = false; s.presses = (s.presses + 1).coerceAtMost(3) }
                s.summary = maxOf(s.summary, when (report) {
                    Report.TRIPLE -> 3
                    Report.DOUBLE -> 2
                    else -> 1
                })
                s.releasedAt = at
            }
            Report.LONG -> { finish(s, Action.LONG); return }
        }
        arm(s)
    }

    /** Retain quiet ownership across panel/session/focus changes and callback-driven cancel. */
    fun cancel() {
        // Cancellation ends edge ownership too; a fresh DOWN after focus/reconnect
        // must be usable even when the retired source never delivered its release.
        retiredHeld = false
        if (sequence != null) retiredUntil = now() + timing.quietMs
        sequence = null
        cancelScheduled()
    }

    private fun cancelScheduled() {
        timerGeneration++
        cancelTimer?.invoke()
        cancelTimer = null
    }

    private fun arm(s: Sequence) {
        cancelScheduled()
        val generation = timerGeneration
        val deadline = if (s.held) s.downAt + timing.longMs else s.releasedAt + timing.quietMs
        cancelTimer = schedule(deadline) {
            if (generation == timerGeneration && sequence === s) {
                if (context() != s.context) cancel()
                else if (now() < deadline) arm(s)
                else if (s.held) finish(s, Action.LONG)
                else finish(s, when (maxOf(s.presses, s.summary)) {
                    3 -> Action.TRIPLE
                    2 -> Action.DOUBLE
                    else -> Action.SINGLE
                })
            }
        }
    }

    private fun finish(s: Sequence, action: Action) {
        // Navigation may synchronously cancel input or replace the current view.
        // Retire before calling it so a duplicate cannot acquire the next context.
        retiredHeld = s.held
        retiredUntil = now() + timing.quietMs
        sequence = null
        cancelScheduled()
        if (context() == s.context) dispatch(action)
    }
}

/** Operation ownership stays in ReceiverActivity's existing contextual methods. */
internal class FunctionNavigation(
    private val projectedDouble: () -> Unit,
    private val localDouble: () -> Unit,
    private val back: () -> Unit,
    private val sleep: () -> Unit,
) {
    fun dispatch(action: FunctionGestureRecognizer.Action, projection: Boolean) {
        when (action) {
            FunctionGestureRecognizer.Action.SINGLE -> back()
            FunctionGestureRecognizer.Action.DOUBLE -> if (projection) projectedDouble() else localDouble()
            FunctionGestureRecognizer.Action.TRIPLE -> sleep()
            FunctionGestureRecognizer.Action.LONG -> Unit
        }
    }
}
