package com.code2hack.eyeprojector.directrg

import android.app.Instrumentation
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Test-owned observation only. Serialization and status reads stay on the instrumentation thread. */
internal class WindowPresentationRecorder(private val id: String, private val runStartedMonoNs: Long) :
    WindowPresentationObserver {
    private data class Row(val sequence: Long, val receipt: SuccessfulWindowReceipt)
    private data class Marker(val kind: String, val readStartMonoNs: Long, val readEndMonoNs: Long,
        val observedElapsedMs: Long, val observedUptimeMs: Long, val suppliedElapsedMs: Long?,
        val eventDownUptimeMs: Long?)

    private val rows = ConcurrentLinkedQueue<Row>()
    private val markers = ConcurrentLinkedQueue<Marker>()
    private val sequence = AtomicLong()
    private val observerFailures = AtomicLong()
    @Volatile private var accepting = true
    private var installed = false
    private var installAttempted = false
    private var exactDetached = false
    private var observerReleased = false
    private val outcomes = linkedMapOf<String, String>()
    private val failures = mutableListOf<Pair<String, String>>() // Instrumentation-thread operations only.

    override fun presented(receipt: SuccessfulWindowReceipt) {
        if (!accepting) return
        try { rows.add(Row(sequence.incrementAndGet(), receipt)) }
        catch (_: Throwable) { observerFailures.incrementAndGet() }
    }

    private fun attempt(name: String, operation: () -> Unit) {
        try { operation(); outcomes[name] = "SUCCESS" }
        catch (error: Throwable) {
            outcomes[name] = "FAILED:${error.javaClass.simpleName}"
            failures.add(name to error.javaClass.name)
        }
    }

    private fun onMain(instrumentation: Instrumentation, operation: () -> Unit) {
        nativeInputObservationOnMain({ task -> instrumentation.runOnMainSync { task() } }, operation)
    }

    fun install(instrumentation: Instrumentation) {
        installAttempted = true
        attempt("observerInstall") { onMain(instrumentation) {
            if (ReceiverSessionOwner.windowPresentationObserver == null) {
                ReceiverSessionOwner.windowPresentationObserver = this
                installed = true
            } else {
                outcomes["observerInstallAvailability"] = "OCCUPIED"
            }
        } }
    }

    private fun marker(kind: String, suppliedElapsedMs: Long? = null, eventDownUptimeMs: Long? = null) {
        if (!accepting) return
        try {
            val start = System.nanoTime()
            val elapsed = SystemClock.elapsedRealtime()
            val uptime = SystemClock.uptimeMillis()
            val end = System.nanoTime()
            markers.add(Marker(kind, start, end, elapsed, uptime, suppliedElapsedMs, eventDownUptimeMs))
        } catch (_: Throwable) { observerFailures.incrementAndGet() }
    }

    fun measurementStarted(elapsedStartMs: Long) = marker("measurement-start", suppliedElapsedMs = elapsedStartMs)
    fun tapDispatchStarting(eventUptimeMs: Long) = marker("synthetic-tap-dispatch-start", eventDownUptimeMs = eventUptimeMs)
    fun tapDispatchReturned(eventUptimeMs: Long) = marker("synthetic-tap-dispatch-return", eventDownUptimeMs = eventUptimeMs)

    private fun snapshot(value: OwnerWindowSnapshot?): Any = value?.let {
        JSONObject().put("readStartMonoNs", it.readStartMonoNs).put("readEndMonoNs", it.readEndMonoNs)
            .put("windowGenerationBefore", it.windowGenerationBefore).put("windowGenerationAfter", it.windowGenerationAfter)
            .put("currentSessionStartedMonoNs", it.currentSessionStartedMonoNs ?: JSONObject.NULL)
            .put("currentSessionMatchesOriginal", it.currentSessionMatchesOriginal ?: JSONObject.NULL)
            .put("outputMatchesOriginal", it.outputMatchesOriginal ?: JSONObject.NULL)
            .put("windowMatchesCallback", it.windowMatchesCallback).put("closed", it.closed)
            .put("mediaState", it.mediaState).put("displayedGeneration", it.displayedGeneration)
            .put("currentDisplayed", it.currentDisplayed).put("uiUsable", it.uiUsable).put("localPanel", it.localPanel)
            .put("inputRevision", it.inputRevision).put("actualReportedMode", it.actualReportedMode ?: JSONObject.NULL)
            .put("actualReportRevision", it.actualReportRevision ?: JSONObject.NULL)
            .put("reportMatchesOriginalOwner", it.reportMatchesOriginalOwner ?: JSONObject.NULL)
            .put("reportMatchesCurrentDemand", it.reportMatchesCurrentDemand ?: JSONObject.NULL)
            .put("actualUsabilityMode", it.actualUsabilityMode ?: JSONObject.NULL)
            .put("actualUsabilityRevision", it.actualUsabilityRevision ?: JSONObject.NULL)
            .put("actualUsabilityInputRevision", it.actualUsabilityInputRevision ?: JSONObject.NULL)
            .put("usabilityMatchesOriginalOwner", it.usabilityMatchesOriginalOwner ?: JSONObject.NULL)
            .put("sessionGeometryWidth", it.sessionGeometryWidth ?: JSONObject.NULL)
            .put("sessionGeometryHeight", it.sessionGeometryHeight ?: JSONObject.NULL)
            .put("metadataReferencesStable", it.metadataReferencesStable)
    } ?: JSONObject.NULL

    /** Every leg is diagnostic-only and independent of the existing measurement and Activity teardown. */
    fun finish(instrumentation: Instrumentation, report: JSONObject, currentStatus: () -> JSONObject,
               minimumStartMonoNs: Long) {
        accepting = false // Also makes an already queued delivery harmless if main dispatch cannot complete.
        attempt("observerDetach") { onMain(instrumentation) {
            if (ReceiverSessionOwner.windowPresentationObserver === this) {
                ReceiverSessionOwner.windowPresentationObserver = null
                exactDetached = true
            }
            observerReleased = ReceiverSessionOwner.windowPresentationObserver !== this
        } }

        var detail: JSONObject? = null
        attempt("metadataCreate") {
            detail = JSONObject().put("schemaVersion", 1).put("runId", id)
                .put("producerRunStartedMonoNs", runStartedMonoNs).put("minimumStartMonoNs", minimumStartMonoNs)
                .put("scope", "Successful eglSwapBuffers callback receipt and serialized owner observations; no GPU or optical presentation time")
                .put("snapshotScope", "Before/after scalar observation spans across renderer/main/control work; no atomic cross-thread snapshot")
                .put("aliasScope", "Per-run diagnostic aliases derived from original session S and callback generation; no authority or native identity")
                .put("sequenceScope", "Recorder enqueue sequence; independent callback, owner and Android clock domains remain explicit")
                .put("drawCause", "UNKNOWN").put("frameIdentity", "UNKNOWN").put("windowGLDimensions", "UNKNOWN")
                .put("cachedFrameCause", "UNKNOWN")
                .put("packetPlateauScope", "Only actual same-session report samples can establish sampled non-config VIDEO counter plateaus; no per-callback frame cause inferred")
                .put("opticalPresentation", "UNKNOWN").put("nativeHidInput", "UNKNOWN")
                .put("originalHSettlement", "UNKNOWN").put("fullCleanup", "UNKNOWN")
        }

        var rowsExported = 0L
        attempt("receiptDrain") {
            val values = JSONArray()
            val sessions = linkedMapOf<Long, Int>()
            val windows = linkedMapOf<Pair<Long, Long>, Int>()
            while (true) {
                val row = rows.poll() ?: break
                val receipt = row.receipt
                val original = receipt.originalSessionStartedMonoNs
                val sessionAlias = original?.let { sessions.getOrPut(it) { sessions.size + 1 } }
                val windowAlias = original?.let { windows.getOrPut(it to receipt.callbackGeneration) { windows.size + 1 } }
                values.put(JSONObject().put("runId", id).put("sequence", row.sequence)
                    .put("originalSessionStartedMonoNs", original ?: JSONObject.NULL)
                    .put("sessionAlias", sessionAlias?.let { "session-$it" } ?: JSONObject.NULL)
                    .put("outputAlias", sessionAlias?.let { "output-$it" } ?: JSONObject.NULL)
                    .put("windowAlias", windowAlias?.let { "window-$it" } ?: JSONObject.NULL)
                    .put("callbackGeneration", receipt.callbackGeneration).put("callbackMonoNs", receipt.callbackMonoNs)
                    .put("callbackReadEndMonoNs", receipt.callbackReadEndMonoNs)
                    .put("existingAcceptanceBranchEntered", receipt.existingAcceptanceBranchEntered)
                    .put("callbackCompleted", receipt.callbackCompleted)
                    .put("before", snapshot(receipt.before)).put("after", snapshot(receipt.after)))
                rowsExported++
            }
            detail?.put("receipts", values)
        }
        attempt("markerDrain") {
            val values = JSONArray()
            while (true) {
                val value = markers.poll() ?: break
                values.put(JSONObject().put("kind", value.kind).put("readStartMonoNs", value.readStartMonoNs)
                    .put("readEndMonoNs", value.readEndMonoNs).put("observedElapsedMs", value.observedElapsedMs)
                    .put("observedUptimeMs", value.observedUptimeMs)
                    .put("measurementStartElapsedMs", value.suppliedElapsedMs ?: JSONObject.NULL)
                    .put("eventDownUptimeMs", value.eventDownUptimeMs ?: JSONObject.NULL))
            }
            detail?.put("markers", values)
        }

        var status: JSONObject? = null
        var statusReadStartMonoNs: Long? = null
        var statusReadEndMonoNs: Long? = null
        attempt("finalStatusRead") {
            statusReadStartMonoNs = System.nanoTime()
            try { status = currentStatus() }
            finally { statusReadEndMonoNs = System.nanoTime() }
        }
        attempt("finalStatusPublication") {
            val observed = System.nanoTime() // Reapply the existing predicate after every blocking capture/read.
            val current = status
            val rejection = if (current == null) "NO_VALIDATED_STATUS" else receiverStatusRejection(
                current.optString("state"), diagnosticClockLong(current.opt("startedMonoNs")),
                diagnosticClockLong(current.opt("elapsedMs")), minimumStartMonoNs, observed)
            val copied = current?.takeIf { rejection == null }?.let { source -> JSONObject().apply {
                for (key in listOf("state", "startedMonoNs", "elapsedMs", "packets", "changingPackets", "rendered",
                        "touchesSent", "width", "height", "presentationActive", "driverStatusRead")) {
                    if (source.has(key)) put(key, source.get(key))
                }
            } }
            val expected = diagnosticClockLong((report.opt("first") as? JSONObject)?.opt("startedMonoNs"))
            val actual = copied?.let { diagnosticClockLong(it.opt("startedMonoNs")) }
            val matches: Any = if (expected == null || actual == null) JSONObject.NULL else actual == expected
            detail?.put("finalStatusReadStartMonoNs", statusReadStartMonoNs ?: JSONObject.NULL)
                ?.put("finalStatusReadEndMonoNs", statusReadEndMonoNs ?: JSONObject.NULL)
                ?.put("finalStatusObservedMonoNs", observed)?.put("finalStatusValidated", copied != null)
                ?.put("finalStatusValidationRejection", rejection ?: JSONObject.NULL)
                ?.put("expectedSessionStartedMonoNs", expected ?: JSONObject.NULL)
                ?.put("finalStatusMatchesMeasurementSession", matches)
                ?.put("finalStatusValidationScope", "Current-run freshness at finalStatusObservedMonoNs, not later report-file publication; expected session S comes from the existing first measurement status")
                ?.put("finalStatus", copied ?: JSONObject.NULL)
        }
        attempt("terminalMetadata") {
            detail?.put("installAttempted", installAttempted)?.put("observerInstalled", installed)
                ?.put("captureDisabled", !accepting)?.put("exactObserverDetached", exactDetached)
                ?.put("observerReleased", observerReleased)?.put("rowsRecorded", sequence.get())
                ?.put("rowsExported", rowsExported)?.put("observerFailures", observerFailures.get())
                ?.put("outcomes", JSONObject(outcomes as Map<*, *>))
                ?.put("failures", JSONArray().apply { failures.forEach { (leg, type) ->
                    put(JSONObject().put("leg", leg).put("errorClass", type))
                } })
                ?.put("drainScope", "Rows available after disable and exact main-thread detach attempt; failed detach or export leaves coverage UNKNOWN")
        }
        attempt("reportAttach") { detail?.let { report.put("windowPresentationObservation", it) } }
    }
}
