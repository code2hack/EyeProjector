package com.code2hack.eyeprojector.directrg

/** Test instruction progress only. Predicates and submission never attest a Phone/app effect. */
internal class AnchorAdbPhasePlan(private val record: (String, String, String?) -> Unit) {
    data class Step(val id: String, val op: String, val timeoutMs: Long,
                    val before: Map<String, Boolean>, val after: Map<String, Boolean>)
    private val finished = mutableSetOf<String>()
    private var active: Step? = null
    private var startedMs = 0L
    private var submitted = false
    private var closed = false
    var completed = 0; private set
    var inconclusive = 0; private set

    fun tick(steps: List<Step>, nowMs: Long, observed: Map<String, Boolean>, submit: (Step) -> Unit) {
        if (closed) return
        if (active == null) {
            active = steps.firstOrNull { it.id !in finished } ?: return
            startedMs = nowMs; submitted = false
            record("phase_start", active!!.id, null)
        }
        val step = active!!
        if (nowMs - startedMs >= step.timeoutMs) {
            inconclusive++; finish("phase_inconclusive", step, "predicate_timeout"); return
        }
        if (!submitted) {
            if (!step.before.all { observed[it.key] == it.value }) return
            try { submit(step); submitted = true; record("command_submitted", step.id, null) }
            catch (error: Exception) {
                inconclusive++; finish("phase_inconclusive", step, error.javaClass.simpleName); return
            }
            return // Observe postconditions on a later tick, after real callbacks may run.
        }
        if (step.after.all { observed[it.key] == it.value }) {
            completed++; finish("phase_complete", step, "own_predicate_only")
        }
    }

    private fun finish(event: String, step: Step, reason: String?) {
        finished.add(step.id); active = null; record(event, step.id, reason)
    }

    fun close() {
        if (closed) return
        closed = true
        active?.let { inconclusive++; record("phase_inconclusive", it.id, "parent_closed") }
        active = null
    }
}
