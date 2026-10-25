package com.code2hack.eyeprojector.directrg

import java.util.concurrent.atomic.AtomicReference

/** Dispatch must wait for callback completion. Contain on that thread, then fail on the caller. */
internal fun nativeInputObservationOnMain(dispatch: (() -> Unit) -> Unit, operation: () -> Unit) {
    val failure = AtomicReference<Throwable?>()
    try {
        dispatch {
            try { operation() }
            catch (error: Throwable) { failure.set(error) }
        }
    } catch (error: Throwable) {
        val original = failure.get()
        if (original == null || original === error) throw error
        original.addSuppressed(error)
        throw original
    }
    failure.get()?.let { throw it }
}

/** Test-run facts and failure ordering, independent of Android event handling. */
internal class NativeInputObservationRunState(val requestedWindowMs: Long) {
    var windowStartMs: Long? = null
    var windowEndMs: Long? = null
    var windowCompleted = false
    var sinkInstalled = false
    var exactSinkDetached = false
    var cleanupComplete = false
    var exportComplete = false
    var observerFailures = 0L
    var exportFailures = 0L
    var rowsRecorded = 0L
    var rowsWritten = 0L
    var primary: Throwable? = null; private set
    var interrupted = false; private set
    val outcomes = linkedMapOf<String, String>()

    fun failed(error: Throwable) {
        if (error is InterruptedException) interrupted = true
        val original = primary
        if (original == null) primary = error
        else if (original !== error) original.addSuppressed(error)
    }

    fun attempt(name: String, operation: () -> Unit) {
        try { operation(); outcomes[name] = "SUCCESS" }
        catch (error: Throwable) { outcomes[name] = "FAILED:${error.javaClass.simpleName}"; failed(error) }
    }

    val actualMeasurementMs get() = windowStartMs?.let { start -> windowEndMs?.minus(start) }
    val captureComplete get() = windowStartMs != null && windowEndMs != null && windowCompleted &&
        (actualMeasurementMs ?: -1) >= requestedWindowMs && sinkInstalled && exactSinkDetached &&
        cleanupComplete && exportComplete && observerFailures == 0L && exportFailures == 0L &&
        rowsRecorded == rowsWritten && primary == null
}
