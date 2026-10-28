package com.code2hack.eyeprojector.directhost

import java.util.concurrent.TimeUnit

internal data class OwnedChildReleaseStep(val stage: String, val elapsedNanos: Long,
    val returned: Boolean, val interruptions: Int = 0, val failure: Throwable? = null)

internal data class OwnedChildReleaseObservation(val initiallyInterrupted: Boolean,
    val steps: List<OwnedChildReleaseStep>)

/** Retains the existing three child-wait budgets even when the cleanup caller is interrupted. */
internal object OwnedChildRelease {
    fun release(process: java.lang.Process, waitNanos: Long = TimeUnit.SECONDS.toNanos(2),
        nanoTime: () -> Long = System::nanoTime): OwnedChildReleaseObservation {
        val initiallyInterrupted = Thread.interrupted()
        var interrupted = initiallyInterrupted
        val steps = ArrayList<OwnedChildReleaseStep>()
        fun wait(stage: String): Boolean {
            val started = nanoTime()
            var interruptions = 0
            var returned = false
            var failure: Throwable? = null
            try {
                while (true) {
                    val remaining = waitNanos - (nanoTime() - started)
                    if (remaining <= 0) break
                    try {
                        returned = process.waitFor(remaining, TimeUnit.NANOSECONDS)
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                        interruptions++
                        Thread.interrupted()
                    }
                }
            } catch (error: Throwable) { failure = error }
            steps.add(OwnedChildReleaseStep(stage, nanoTime() - started, returned, interruptions, failure))
            return returned
        }
        fun terminate(stage: String, operation: () -> Unit) {
            val started = nanoTime()
            val result = runCatching(operation)
            steps.add(OwnedChildReleaseStep(stage, nanoTime() - started, result.isSuccess,
                failure = result.exceptionOrNull()))
        }
        try {
            if (!wait("initialWait")) {
                terminate("destroy") { process.destroy() }
                if (!wait("destroyWait")) {
                    terminate("force") { process.destroyForcibly() }
                    wait("forceWait")
                }
            }
            // The owning Session enforces actual child liveness before marking release complete.
            return OwnedChildReleaseObservation(initiallyInterrupted, steps.toList())
        } finally {
            if (Thread.interrupted()) interrupted = true
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
