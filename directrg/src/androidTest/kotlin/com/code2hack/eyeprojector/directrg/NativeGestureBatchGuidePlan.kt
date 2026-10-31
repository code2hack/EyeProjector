package com.code2hack.eyeprojector.directrg

/** Instruction time only. It never observes an input or acknowledges a physical gesture. */
internal class NativeGestureBatchGuidePlan {
    data class Prompt(val id: String, val instruction: String, val durationMs: Long)
    data class Snapshot(val prompt: Prompt, val step: Int, val remainingMs: Long,
                        val promptElapsedMs: Long, val activeElapsedMs: Long, val complete: Boolean) {
        val countdownSeconds get() = (remainingMs + 999) / 1000
    }
    data class Event(val name: String, val snapshot: Snapshot)

    private var index = 0
    private var promptElapsed = 0L
    private var activeElapsed = 0L
    private var lastTime: Long? = null
    private var eligibleBefore = false
    private var promptStarted = false
    private var lastCountdown: Long? = null
    private var closed = false

    val snapshot: Snapshot get() {
        val complete = index >= PROMPTS.size
        val prompt = if (complete) FINISHED else PROMPTS[index]
        return Snapshot(prompt, if (complete) STEP_COUNT else index, (prompt.durationMs - promptElapsed).coerceAtLeast(0),
            promptElapsed, activeElapsed, complete)
    }

    fun update(nowMs: Long, eligible: Boolean): List<Event> {
        if (closed || snapshot.complete) return emptyList()
        val events = mutableListOf<Event>()
        if (!eligible) {
            if (eligibleBefore) events += Event("pause", snapshot)
            eligibleBefore = false
            lastTime = nowMs
            return events
        }
        if (eligibleBefore) {
            val delta = (nowMs - (lastTime ?: nowMs)).coerceAtLeast(0)
            promptElapsed += delta
            activeElapsed += delta
        } else events += Event("resume", snapshot)
        lastTime = nowMs
        eligibleBefore = true
        if (!promptStarted) {
            promptStarted = true
            events += Event(if (index == 0) "countdown_start" else "step_start", snapshot)
        }
        if (lastCountdown != snapshot.countdownSeconds) {
            lastCountdown = snapshot.countdownSeconds
            events += Event("countdown", snapshot)
        }
        if (snapshot.remainingMs == 0L) {
            events += Event(if (index == 0) "countdown_end" else "step_end", snapshot)
            index++
            promptElapsed = 0
            promptStarted = false
            lastCountdown = null
            // A delayed callback may finish this prompt, but must never skip unseen prompts.
            // The next prompt needs its own visible draw and a new eligible update.
            eligibleBefore = false
            if (snapshot.complete) events += Event("complete", snapshot)
        }
        return events
    }

    fun close() { closed = true; eligibleBefore = false }

    companion object {
        val PROMPTS = listOf(
            Prompt("prepare", "GET READY\nGesture test starts soon\nWatch here. No reply needed.", 10_000),
            Prompt("one_single", "ONE FINGER: TAP ONCE", 20_000),
            Prompt("one_double", "ONE FINGER: DOUBLE TAP", 20_000),
            Prompt("one_hold", "ONE FINGER: HOLD 2s, RELEASE", 20_000),
            Prompt("two_single", "TWO FINGERS: TAP ONCE", 20_000),
            Prompt("two_hold", "TWO FINGERS: HOLD 2s, RELEASE", 20_000),
            Prompt("two_double", "TWO FINGERS: DOUBLE TAP\nScreen may turn off.", 20_000),
        )
        val FINISHED = Prompt("finished", "INSTRUCTIONS FINISHED\nNo more gestures. Wait.", 0)
        const val STEP_COUNT = 6
    }
}
