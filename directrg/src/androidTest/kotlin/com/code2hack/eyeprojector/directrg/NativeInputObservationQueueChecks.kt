package com.code2hack.eyeprojector.directrg

/** Cached host checks exercise the actual test recorder queue, not Android event delivery. */
internal fun nativeInputObservationQueueChecks() {
    val queue = NativeInputObservationQueue()
    val original = mutableMapOf<String, Any?>("eventTimeMs" to 1000L)
    queue.capture("key", 7, 1002, 9_000_000_000) { original }
    original["eventTimeMs"] = 2000L
    queue.capture("key", 7, 1024, 9_022_000_000) { error("test observation failure") }
    queue.capture("stage", 7, 2022, 10_000_000_000) { mapOf("stage" to "cancel") }
    queue.close()
    queue.capture("key", 7, 3000, 11_000_000_000) { error("must not copy after detach") }
    val rows = mutableListOf<NativeInputObservationQueue.Row>()
    check(queue.drain(rows::add) == 2L)
    check(rows.map { it.seq } == listOf(1L, 2L))
    check(rows[0].fields["eventTimeMs"] == 1000L) // Mutable event metadata cannot change a saved observation.
    check(rows[0].uptimeMs == 1002L && rows[0].elapsedNs == 9_000_000_000L)
    check(rows[1].fields["stage"] == "cancel")
    check(queue.recorded == 2L && queue.failureCount == 1L)
    check(queue.drain { error("already drained") } == 0L)
    check(queue.totalCallbackNs >= queue.maxCallbackNs && queue.maxCallbackNs > 0)
    val backgroundOnly = NativeInputObservationQueue()
    backgroundOnly.capture("sample", null, 1, 2) { mapOf("controlsWritten" to 3L) }
    check(backgroundOnly.totalCallbackNs == 0L) // Do not call background work input-callback overhead.
    val failingWriter = NativeInputObservationQueue()
    failingWriter.capture("key", 1, 1, 2) { emptyMap() }
    var failed = false
    try { failingWriter.drain { error("writer I/O failure") } } catch (_: IllegalStateException) { failed = true }
    check(failed) // Writer failure must reach the runner, not silently become a successful capture.

    fun completeState() = NativeInputObservationRunState(1000).apply {
        windowStartMs = 100; windowEndMs = 1100; windowCompleted = true
        sinkInstalled = true; exactSinkDetached = true; cleanupComplete = true; exportComplete = true
        rowsRecorded = 2; rowsWritten = 2
    }
    check(completeState().captureComplete)
    check(!completeState().apply { windowCompleted = false }.captureComplete)
    check(!completeState().apply { windowEndMs = 200 }.captureComplete)
    check(!completeState().apply { exportFailures = 1 }.captureComplete)
    check(!completeState().apply { observerFailures = 1 }.captureComplete)
    check(!completeState().apply { rowsWritten = 1 }.captureComplete)
    check(!completeState().apply { sinkInstalled = false }.captureComplete)
    check(!completeState().apply { cleanupComplete = false }.captureComplete)
    val captureError = IllegalArgumentException("capture failed")
    val releaseError = IllegalStateException("unregister failed")
    val attempts = NativeInputObservationRunState(1000)
    val releases = mutableListOf<String>()
    attempts.failed(captureError)
    attempts.attempt("detach") { releases.add("detach"); throw releaseError }
    attempts.attempt("lifecycle") { releases.add("lifecycle") }
    attempts.attempt("thread") { releases.add("thread") }
    attempts.attempt("writer") { releases.add("writer") }
    check(releases == listOf("detach", "lifecycle", "thread", "writer"))
    check(attempts.primary === captureError && captureError.suppressed.single() === releaseError)
    check(attempts.outcomes["writer"] == "SUCCESS")
    attempts.attempt("join") { throw InterruptedException("cleanup canceled") }
    check(attempts.primary === captureError && attempts.interrupted)
}
