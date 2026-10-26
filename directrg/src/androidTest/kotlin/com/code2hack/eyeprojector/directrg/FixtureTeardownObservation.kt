package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Fixture-local observations only; the captured target owns every cancellation argument. */
internal class FixtureTeardownRecorder private constructor(
    activity: Activity?,
    private val acceptedSessionStartedMonoNs: Long?,
    private val deadlineElapsedMs: Long?,
) {
    private val referenceLock = Any()
    @Volatile private var originalActivity: Activity? = activity
    @Volatile private var target: ReceiverActivity.FixtureTeardownTarget? = null
    @Volatile private var capturedSessionStartedMonoNs: Long? = null
    @Volatile private var accepting = true
    private val activityCaptured = activity != null
    private var targetCaptured = false
    private val terminalObservations = CountDownLatch(2)
    private val completionReached = AtomicBoolean()
    private val destroyTerminalReached = AtomicBoolean()
    private val completionCount = AtomicLong()
    private val destroyEntryCount = AtomicLong()
    private val destroyReturnCount = AtomicLong()
    private val destroyFailureCount = AtomicLong()
    private val observerFailures = AtomicLong()
    private val captureEnter = AtomicReference<Long?>()
    private val captureReturn = AtomicReference<Long?>()
    private val requestEnter = AtomicReference<Long?>()
    private val requestReturn = AtomicReference<Long?>()
    private val completionReceipt = AtomicReference<Long?>()
    private val finishEnter = AtomicReference<Long?>()
    private val finishReturn = AtomicReference<Long?>()
    private val finishFailure = AtomicReference<Long?>()
    private val finishFailureClass = AtomicReference<String?>()
    private val destroyEnter = AtomicReference<Long?>()
    private val destroyReturn = AtomicReference<Long?>()
    private val destroyFailure = AtomicReference<Long?>()
    private val destroyFailureClass = AtomicReference<String?>()
    private val outcomes = linkedMapOf<String, String>() // Instrumentation-thread operations only.
    private val failures = mutableListOf<Pair<String, String>>()
    private var before: ReceiverActivity.FixtureTeardownObservation? = null

    companion object {
        fun capture(instrumentation: Instrumentation, activity: Activity?, acceptedStatus: JSONObject,
                    deadlineElapsedMs: Long?): FixtureTeardownRecorder {
            val expected = try { diagnosticClockLong(acceptedStatus.opt("startedMonoNs")) }
                catch (_: Throwable) { null }
            val recorder = FixtureTeardownRecorder(activity, expected, deadlineElapsedMs)
            if (activity is ReceiverActivity && expected != null) {
                recorder.attempt("targetCapture") { recorder.onMain(instrumentation) {
                    // The queued operation owns only the recorder, never the Activity authority.
                    val originalActivity = synchronized(recorder.referenceLock) {
                        if (recorder.accepting) recorder.originalActivity as? ReceiverActivity else null
                    } ?: return@onMain
                    recorder.captureEnter.set(System.nanoTime())
                    val captured = originalActivity.captureFixtureTeardownTarget(expected)
                    val originalStart = captured?.sessionStartedMonoNs
                    // Release can run while the already-started factory is reading memory.
                    synchronized(recorder.referenceLock) {
                        if (recorder.accepting) {
                            recorder.target = captured
                            recorder.capturedSessionStartedMonoNs = originalStart
                            recorder.targetCaptured = captured != null
                        }
                    }
                    recorder.captureReturn.set(System.nanoTime())
                } }
                if (recorder.target == null && recorder.outcomes["targetCapture"] == "RETURNED") {
                    recorder.outcomes["targetCapture"] = "UNKNOWN_NO_ORIGINAL_TARGET"
                }
            } else recorder.outcomes["targetCapture"] = "UNKNOWN_NO_OWNED_ACTIVITY_OR_SESSION"
            return recorder
        }
    }

    private fun attempt(name: String, operation: () -> Unit) {
        try { operation(); outcomes[name] = "RETURNED" }
        catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            outcomes[name] = "FAILED:${error.javaClass.simpleName}"
            failures.add(name to error.javaClass.name)
        }
    }
    private fun onMain(instrumentation: Instrumentation, operation: () -> Unit) {
        nativeInputObservationOnMain({ task -> instrumentation.runOnMainSync { task() } }, operation)
    }
    private inline fun mark(operation: () -> Unit) {
        if (!accepting) return
        try { operation() } catch (_: Throwable) { observerFailures.incrementAndGet() }
    }
    private fun completed() = mark {
        completionCount.incrementAndGet()
        completionReceipt.compareAndSet(null, System.nanoTime())
        if (completionReached.compareAndSet(false, true)) terminalObservations.countDown()
    }

    /** No caller/queued lambda retains target authority while waiting for main dispatch. */
    private fun originalTarget(): ReceiverActivity.FixtureTeardownTarget? = synchronized(referenceLock) {
        if (accepting) target else null
    }
    private fun hasOriginalTarget(): Boolean = synchronized(referenceLock) { accepting && target != null }

    /** One call from the fixture; no resolution, retry or successor adoption occurs here. */
    fun requestDisconnect(instrumentation: Instrumentation) {
        if (!hasOriginalTarget()) {
            outcomes["disconnectRequest"] = "UNKNOWN_NO_ORIGINAL_TARGET"
            return
        }
        attempt("beforeObservation") { onMain(instrumentation) {
            originalTarget()?.let { before = it.observe() }
        } }
        attempt("disconnectRequest") { onMain(instrumentation) {
            val original = originalTarget() ?: return@onMain
            // A request already selected here can finish after release; no product barrier is added.
            requestEnter.set(System.nanoTime())
            original.disconnect(::completed)
            requestReturn.set(System.nanoTime())
        } }
        if (requestEnter.get() == null && outcomes["disconnectRequest"] == "RETURNED") {
            outcomes["disconnectRequest"] = "UNKNOWN_NO_ACTIVE_ORIGINAL_TARGET_AT_DISPATCH"
        }
    }

    fun finishRequested() = mark { finishEnter.compareAndSet(null, System.nanoTime()); Unit }
    fun finishReturned() = mark { finishReturn.compareAndSet(null, System.nanoTime()); Unit }
    fun finishFailed(error: Throwable) = mark {
        finishFailure.compareAndSet(null, System.nanoTime())
        finishFailureClass.compareAndSet(null, error.javaClass.name)
        Unit
    }
    fun destroyEntered(activity: Activity) = mark {
        if (activity === originalActivity) {
            destroyEntryCount.incrementAndGet()
            destroyEnter.compareAndSet(null, System.nanoTime())
        }
    }
    fun destroyReturned(activity: Activity) = mark {
        if (activity === originalActivity) {
            destroyReturnCount.incrementAndGet()
            destroyReturn.compareAndSet(null, System.nanoTime())
            if (destroyTerminalReached.compareAndSet(false, true)) terminalObservations.countDown()
        }
    }
    fun destroyFailed(activity: Activity, error: Throwable) = mark {
        if (activity === originalActivity) {
            destroyFailureCount.incrementAndGet()
            destroyFailure.compareAndSet(null, System.nanoTime())
            destroyFailureClass.compareAndSet(null, error.javaClass.name)
            // A known throw terminates observation of this attempt, never a successful return.
            if (destroyTerminalReached.compareAndSet(false, true)) terminalObservations.countDown()
        }
    }

    private fun snapshot(value: ReceiverActivity.FixtureTeardownObservation?): Any = value?.let {
        JSONObject().put("readStartMonoNs", it.readStartMonoNs).put("readEndMonoNs", it.readEndMonoNs)
            .put("originalSessionStartedMonoNs", it.originalSessionStartedMonoNs)
            .put("ownerState", it.ownerState).put("ownerSessionPresent", it.ownerSessionPresent)
            .put("ownerSessionMatchesOriginal", it.ownerSessionMatchesOriginal)
            .put("currentSessionStartedMonoNs", it.currentSessionStartedMonoNs ?: JSONObject.NULL)
            .put("currentActivationMatchesOriginal", it.currentActivationMatchesOriginal ?: JSONObject.NULL)
            .put("originalTriggerCaptured", it.originalTriggerCaptured)
            .put("originalActivationCaptured", it.originalActivationCaptured)
            .put("originalTriggerCanceled", it.originalTriggerCanceled ?: JSONObject.NULL)
            .put("originalTriggerAcknowledged", it.originalTriggerAcknowledged ?: JSONObject.NULL)
            .put("originalActivationCancellationRecorded", it.originalActivationCancellationRecorded ?: JSONObject.NULL)
            .put("originalActivationPendingCancel", it.originalActivationPendingCancel ?: JSONObject.NULL)
    } ?: JSONObject.NULL

    /** Called after the independent original Activity.finish attempt, before existing report publication. */
    fun complete(instrumentation: Instrumentation, report: JSONObject, currentStatus: () -> JSONObject,
                 minimumStartMonoNs: Long) {
        try {
            var waitStartElapsedMs: Long? = null
            var waitReturnElapsedMs: Long? = null
            var remainingMs: Long? = null
            var awaitCalled = false
            var awaitReturned: Boolean? = null
            attempt("observationWait") {
                val began = SystemClock.elapsedRealtime()
                waitStartElapsedMs = began
                val deadline = deadlineElapsedMs
                remainingMs = deadline?.let { if (it > began) it - began else 0L }
                if (hasOriginalTarget() && remainingMs?.let { it > 0 } == true) {
                    awaitCalled = true
                    awaitReturned = terminalObservations.await(checkNotNull(remainingMs), TimeUnit.MILLISECONDS)
                }
                waitReturnElapsedMs = SystemClock.elapsedRealtime()
            }
            var after: ReceiverActivity.FixtureTeardownObservation? = null
            if (hasOriginalTarget()) attempt("afterObservation") { onMain(instrumentation) {
                originalTarget()?.let { after = it.observe() }
            } } else outcomes["afterObservation"] = "UNKNOWN_NO_ORIGINAL_TARGET"

            var status: JSONObject? = null
            var readStart: Long? = null
            var readEnd: Long? = null
            attempt("statusRead") {
                readStart = System.nanoTime()
                try { status = currentStatus() } finally { readEnd = System.nanoTime() }
            }
            var observedAt: Long? = null
            var rejection: String? = "NO_VALIDATED_STATUS"
            var copiedStatus: JSONObject? = null
            var statusMatchesOriginal: Boolean? = null
            attempt("statusObservation") {
                val observed = System.nanoTime() // After the blocking owner/status reads.
                observedAt = observed
                val value = status
                rejection = if (value == null) "NO_VALIDATED_STATUS" else receiverStatusRejection(
                    value.optString("state"), diagnosticClockLong(value.opt("startedMonoNs")),
                    diagnosticClockLong(value.opt("elapsedMs")), minimumStartMonoNs, observed)
                copiedStatus = value?.takeIf { rejection == null }?.let { source -> JSONObject().apply {
                    for (key in listOf("state", "startedMonoNs", "elapsedMs", "presentationActive", "driverStatusRead",
                            "controlsPending", "controlsInFlight")) if (source.has(key)) put(key, source.get(key))
                } }
                val actual = copiedStatus?.let { diagnosticClockLong(it.opt("startedMonoNs")) }
                val expected = capturedSessionStartedMonoNs
                statusMatchesOriginal = if (expected == null || actual == null) null else expected == actual
            }

            // Disable future recording and drop authority/Activity references before JSON export.
            release()
            var detail: JSONObject? = null
            attempt("metadataCreate") {
                detail = JSONObject().put("schemaVersion", 1)
                    .put("scope", "Original-scoped fixture request and observed callbacks/status/lifecycle, not product release or H settlement")
                    .put("acceptedStatusSessionStartedMonoNs", acceptedSessionStartedMonoNs ?: JSONObject.NULL)
                    .put("originalSessionStartedMonoNs", capturedSessionStartedMonoNs ?: JSONObject.NULL)
                    .put("ownedActivityCaptured", activityCaptured).put("originalTargetCaptured", targetCaptured)
                    .put("captureEnteredMonoNs", captureEnter.get() ?: JSONObject.NULL)
                    .put("captureReturnedMonoNs", captureReturn.get() ?: JSONObject.NULL)
                    .put("disconnectEnteredMonoNs", requestEnter.get() ?: JSONObject.NULL)
                    .put("disconnectReturnedMonoNs", requestReturn.get() ?: JSONObject.NULL)
                    .put("completionCallbackMonoNs", completionReceipt.get() ?: JSONObject.NULL)
                    .put("completionCallbackCount", completionCount.get())
                    .put("completionScope", "Existing completion may be inline for a closed owner; callback alone is not original H/Phone/backend release")
                    .put("finishRequestedMonoNs", finishEnter.get() ?: JSONObject.NULL)
                    .put("finishReturnedMonoNs", finishReturn.get() ?: JSONObject.NULL)
                    .put("finishFailedMonoNs", finishFailure.get() ?: JSONObject.NULL)
                    .put("finishFailureClass", finishFailureClass.get() ?: JSONObject.NULL)
                    .put("finishFailureScope", "Failure can be from main dispatch or the finish call; an observed finish return and later dispatch failure may both be present, not exclusive proof Activity.finish threw")
                    .put("destroyEnteredMonoNs", destroyEnter.get() ?: JSONObject.NULL)
                    .put("destroyReturnedMonoNs", destroyReturn.get() ?: JSONObject.NULL)
                    .put("destroyFailedMonoNs", destroyFailure.get() ?: JSONObject.NULL)
                    .put("destroyFailureClass", destroyFailureClass.get() ?: JSONObject.NULL)
                    .put("destroyEntryCount", destroyEntryCount.get()).put("destroyReturnCount", destroyReturnCount.get())
                    .put("destroyFailureCount", destroyFailureCount.get())
                    .put("lifecycleScope", "Exact captured Activity dispatch entry/actual return/throw; its existing mutable-field onDestroy cancellation is unchanged, not newly pinned")
                    .put("originalObservationDeadlineElapsedMs", deadlineElapsedMs ?: JSONObject.NULL)
                    .put("waitStartElapsedMs", waitStartElapsedMs ?: JSONObject.NULL)
                    .put("remainingOriginalCeilingMs", remainingMs ?: JSONObject.NULL)
                    .put("waitCalled", awaitCalled).put("waitReturned", awaitReturned ?: JSONObject.NULL)
                    .put("waitReturnElapsedMs", waitReturnElapsedMs ?: JSONObject.NULL)
                    .put("waitScope", "One completion plus destroy-attempt-terminal (return OR throw) observation wait within remaining ORIGINAL setup ceiling; no reset or new timeout; return/throw remain distinct")
            }
            attempt("ownerObservationExport") { detail?.put("before", snapshot(before))?.put("after", snapshot(after)) }
            attempt("statusExport") {
                detail?.put("statusReadStartMonoNs", readStart ?: JSONObject.NULL)
                    ?.put("statusReadEndMonoNs", readEnd ?: JSONObject.NULL)
                    ?.put("statusObservedMonoNs", observedAt ?: JSONObject.NULL)
                    ?.put("statusValidatedAtObservation", copiedStatus != null)
                    ?.put("statusValidationRejection", rejection ?: JSONObject.NULL)
                    ?.put("statusMatchesOriginalSession", statusMatchesOriginal ?: JSONObject.NULL)
                    ?.put("status", copiedStatus ?: JSONObject.NULL)
                    ?.put("statusScope", "Existing current-run predicate reapplied at observed time; terminal STOPPED has no active3s age limit. A read does not prove a new post-request file publication; successor S is not retargeted")
            }
            attempt("terminalMetadata") {
                detail?.put("observerFailures", observerFailures.get())?.put("recordingDisabled", !accepting)
                    ?.put("targetReferenceReleased", target == null)?.put("activityReferenceReleased", originalActivity == null)
                    ?.put("outcomes", JSONObject(outcomes as Map<*, *>))
                    ?.put("failures", JSONArray().apply { failures.forEach { (leg, type) ->
                        put(JSONObject().put("leg", leg).put("errorClass", type))
                    } })
                    ?.put("measurementOutcomeScope", "Existing measurement outcome remains independent; no new full-trial/STOPPED/H pass")
                    ?.put("callbackQuiescence", "UNOBSERVED")?.put("productRelease", "UNKNOWN")
                    ?.put("originalHSettlement", "UNKNOWN")?.put("instrumentationExit", "NOT_YET_OBSERVED")
                    ?.put("referenceReleaseScope", "Recorder fields cleared under their publication lock; queued work cannot select/reinstall authority after release, already-selected work may finish later. No quiescence or product barrier claim")
                    ?.put("publicationScope", "Observations precede existing report write and Instrumentation.finish; natural child terminal is separate host evidence")
            }
            attempt("reportAttach") { detail?.let { report.put("fixtureTeardown", it) } }
        } finally { release() }
    }

    fun release() {
        synchronized(referenceLock) {
            accepting = false
            try { target = null } finally { originalActivity = null }
        }
    }
}
