package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

private val controlFields = listOf("state", "startedMonoNs", "elapsedMs", "width", "height",
    "presentationActive", "controlsAccepted", "controlsWritten", "controlsCanceled", "controlsFailed",
    "controlsRejected", "controlsPending", "controlsInFlight", "touchesSent", "heldTouchFramesWritten",
    "scrollsSent", "navigationsSent", "controlSubmissionToWriteMeanMs", "controlSubmissionToWriteMaxMs")

/** Receives original values, copies immediately, and never retains a recyclable event. */
private class NativeInputRecorder : ReceiverActivity.NativeInputObserver {
    data class Device(val name: String, val observedElapsedNs: Long)
    val queue = NativeInputObservationQueue()
    val inventory = AtomicReference<Map<Int, Device>>(emptyMap())
    private val actions = IdentityHashMap<PadGestureRecognizer.Action, Long>() // Main-thread callbacks only.
    private var nextAction = 0L

    private fun device(id: Int): Map<String, Any?> {
        val known = inventory.get()[id]
        return mapOf("deviceId" to id, "deviceName" to known?.name,
            "deviceNameObservedElapsedNs" to known?.observedElapsedNs,
            "deviceNameScope" to "InputManager inventory; no event-time Binder lookup")
    }

    override fun key(activityId: Int, event: KeyEvent, receiptUptimeMs: Long, receiptElapsedNs: Long) {
        queue.capture("key", activityId, receiptUptimeMs, receiptElapsedNs) {
            device(event.deviceId) + mapOf("source" to event.source, "keyCode" to event.keyCode,
                "scanCode" to event.scanCode, "action" to event.action, "flags" to event.flags,
                "repeat" to event.repeatCount, "downTimeMs" to event.downTime, "eventTimeMs" to event.eventTime)
        }
    }

    override fun motion(activityId: Int, entry: String, event: MotionEvent, receiptUptimeMs: Long, receiptElapsedNs: Long) {
        queue.capture(entry, activityId, receiptUptimeMs, receiptElapsedNs) {
            device(event.deviceId) + mapOf("source" to event.source, "action" to event.action,
                "actionMasked" to event.actionMasked, "actionIndex" to event.actionIndex,
                "flags" to event.flags, "pointerCount" to event.pointerCount,
                "pointerIds" to (0 until event.pointerCount).map(event::getPointerId),
                "downTimeMs" to event.downTime, "eventTimeMs" to event.eventTime)
        }
    }

    override fun stage(activityId: Int, stage: String, action: PadGestureRecognizer.Action?, deadlineUptimeMs: Long?,
                       outcome: String?, local: Boolean?, generation: Long, receiptUptimeMs: Long, receiptElapsedNs: Long) {
        queue.capture("stage", activityId, receiptUptimeMs, receiptElapsedNs) {
            val actionId = action?.let { actions[it] ?: (++nextAction).also { id -> actions[it] = id } }
            val details = when (action) {
                is PadGestureRecognizer.Action.Tap -> mapOf("actionType" to "tap", "downTimeMs" to action.downTimeMs,
                    "upTimeMs" to action.upTimeMs, "tapTiming" to action.timing.name)
                is PadGestureRecognizer.Action.Scroll -> mapOf("actionType" to "scroll", "pixels" to action.pixels)
                is PadGestureRecognizer.Action.Contact -> mapOf("actionType" to "contact-${action.event.phase.name.lowercase()}",
                    "downTimeMs" to action.event.downTimeMs, "contactEventTimeMs" to action.event.timeMs)
                is PadGestureRecognizer.Action.LongPress -> mapOf("actionType" to "long-classification", "downTimeMs" to action.markerDownTimeMs)
                is PadGestureRecognizer.Action.Back -> mapOf("actionType" to "back")
                null -> emptyMap()
            }
            if (stage == "dispatch_result" && action != null) actions.remove(action)
            details + mapOf("stage" to stage, "actionId" to actionId, "actionTimeMs" to action?.timeMs,
                "deadlineUptimeMs" to if (stage == "deadline") deadlineUptimeMs else null,
                "aiCallbackUptimeMs" to if (stage == "ai_callback") deadlineUptimeMs else null,
                "outcome" to outcome, "local" to local, "generation" to generation)
        }
    }
}

/** One finite case. Root/Owner operate the normal UI; this driver neither launches nor finishes it. */
internal fun runNativeInputObservation(instrumentation: Instrumentation, args: Bundle, id: String, report: JSONObject) {
    val seconds = args.getString("seconds")?.toInt() ?: 90
    require(seconds in 1..660) // Same existing instrumentation measurement budget; no product expiry.
    val case = args.getString("case") ?: "unclassified"
    val context = instrumentation.targetContext
    val application = context.applicationContext as Application
    val recorder = NativeInputRecorder()
    val trace = File(context.filesDir, "probe-$id-native-events.jsonl")
    check(trace.createNewFile()) { "Existing measurement trace" }
    val detail = JSONObject().put("schema", 1).put("case", case).put("traceFile", trace.name)
        .put("physicalInputPass", false).put("physicalHoldReleasePass", false)
        .put("syntheticInputInjected", false).put("activityLaunchedOrFinished", false)
        .put("scope", "App-delivered event observation; no raw-kernel/physical origin or target injection acknowledgment")
    report.put("nativeObservation", detail).put("passedScope", "recorder completion only; no physical feature acceptance")
    val manager = context.getSystemService(InputManager::class.java)
    val inventoryThread = HandlerThread("native-input-inventory")
    var inventoryStartAttempted = false
    var listenerRegistrationAttempted = false
    var lifecycleRegistrationAttempted = false
    var sinkInstallAttempted = false
    var installed = false
    var detached = false
    val state = NativeInputObservationRunState(seconds * 1000L)
    var currentActivity: WeakReference<ReceiverActivity>? = null
    fun boundary(state: String) {
        recorder.queue.capture("capture_boundary", null, SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) {
            mapOf("state" to state)
        }
    }
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        private fun observe(activity: Activity, state: String) {
            if (activity !is ReceiverActivity) return
            if (state == "resumed") currentActivity = WeakReference(activity)
            recorder.queue.capture("lifecycle", System.identityHashCode(activity), SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) {
                mapOf("state" to state)
            }
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) = observe(a, "created")
        override fun onActivityResumed(a: Activity) = observe(a, "resumed")
        override fun onActivityPaused(a: Activity) = observe(a, "paused")
        override fun onActivityDestroyed(a: Activity) = observe(a, "destroyed")
        override fun onActivityStarted(a: Activity) = observe(a, "started")
        override fun onActivityStopped(a: Activity) = observe(a, "stopped")
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    }
    fun inventory() {
        val at = SystemClock.elapsedRealtimeNanos()
        recorder.queue.capture("device_inventory", null, SystemClock.uptimeMillis(), at) {
            val devices = manager.inputDeviceIds.mapNotNull { deviceId ->
                manager.getInputDevice(deviceId)?.let { deviceId to NativeInputRecorder.Device(it.name, at) }
            }.toMap()
            recorder.inventory.set(devices)
            mapOf("devices" to devices.map { (deviceId, info) -> mapOf("deviceId" to deviceId, "name" to info.name) },
                "inventoryReadStartElapsedNs" to at, "inventoryReadEndElapsedNs" to SystemClock.elapsedRealtimeNanos())
        }
    }
    val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = inventory()
        override fun onInputDeviceRemoved(id: Int) = inventory()
        override fun onInputDeviceChanged(id: Int) = inventory()
    }
    var writer: BufferedWriter? = null
    var stream: FileOutputStream? = null
    var writerOpened = false
    fun drain() {
        try {
            val output = writer ?: return
            recorder.queue.drain { row ->
                val json = JSONObject(row.fields).put("type", row.type).put("seq", row.seq).put("case", case)
                    .put("activityId", row.activityId ?: JSONObject.NULL)
                    .put("receiptUptimeMs", row.uptimeMs).put("receiptElapsedNs", row.elapsedNs)
                output.write(json.toString()); output.newLine()
                state.rowsWritten++ // Count each completed row, including a partial drain before later failure.
            }
            output.flush() // Instrumentation thread only.
        } catch (error: Throwable) { state.exportFailures++; throw error }
    }
    try {
        try {
            stream = FileOutputStream(trace)
            writer = stream!!.bufferedWriter()
            writerOpened = true
        } catch (error: Throwable) { state.exportFailures++; throw error }
        inventory() // Test-thread Binder work; never in a raw event callback.
        inventoryStartAttempted = true
        inventoryThread.start()
        listenerRegistrationAttempted = true
        manager.registerInputDeviceListener(listener, Handler(inventoryThread.looper))
        instrumentation.runOnMainSync {
            check(ReceiverActivity.nativeInputObserver == null) { "Another observation owns the sink" }
            lifecycleRegistrationAttempted = true
            application.registerActivityLifecycleCallbacks(lifecycle)
            sinkInstallAttempted = true
            ReceiverActivity.nativeInputObserver = recorder
            installed = true
            boundary("installed")
        }
        val started = SystemClock.elapsedRealtime()
        state.windowStartMs = started
        detail.put("measurementStartElapsedMs", started).put("measurementStartUptimeMs", SystemClock.uptimeMillis())
        File(context.filesDir, "probe-$id-native-ready.json").writeText(JSONObject()
            .put("case", case).put("seconds", seconds).put("startElapsedMs", started).toString())
        var nextSample = 0L
        while (SystemClock.elapsedRealtime() - started < seconds * 1000L) {
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed >= nextSample) {
                val sample = mutableMapOf<String, Any?>()
                instrumentation.runOnMainSync {
                    val activity = currentActivity?.get()
                    sample["activitySnapshotScope"] = if (activity == null) "No Receiver resume observed since listener installation" else "observed Receiver instance"
                    if (activity != null) {
                        try { sample["receiverInput"] = activity.inputSnapshot() }
                        catch (error: Exception) { sample["inputSnapshotErrorType"] = error.javaClass.simpleName }
                    }
                }
                sample["statusReadStartElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                try {
                    val file = File(context.filesDir, "status.json")
                    val status = JSONObject(file.readText())
                    sample["controls"] = JSONObject().apply { controlFields.forEach { key -> if (status.has(key)) put(key, status.get(key)) } }
                    sample["statusFileModifiedEpochMs"] = file.lastModified()
                } catch (error: Exception) { sample["statusReadErrorType"] = error.javaClass.simpleName }
                sample["statusReadEndElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                recorder.queue.capture("sample", null, SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) { sample }
                nextSample = elapsed + 1000
            }
            drain()
            Thread.sleep(50)
        }
        state.windowCompleted = true
    } catch (error: Throwable) { state.failed(error) }
    finally {
        if (state.windowStartMs != null) state.windowEndMs = SystemClock.elapsedRealtime()
        var sinkReleased = false
        var lifecycleReleased = !lifecycleRegistrationAttempted
        var listenerReleased = !listenerRegistrationAttempted
        var queueClosed = false
        var writerClosed = false
        var streamClosed = false
        state.attempt("observerDetach") { instrumentation.runOnMainSync {
            if (ReceiverActivity.nativeInputObserver === recorder) {
                ReceiverActivity.nativeInputObserver = null; detached = true
            }
            sinkReleased = ReceiverActivity.nativeInputObserver !== recorder
        } }
        state.attempt("lifecycleUnregister") { if (lifecycleRegistrationAttempted) instrumentation.runOnMainSync {
            application.unregisterActivityLifecycleCallbacks(lifecycle)
            currentActivity = null
            lifecycleReleased = true
        } }
        state.attempt("inputListenerUnregister") { if (listenerRegistrationAttempted) {
            manager.unregisterInputDeviceListener(listener); listenerReleased = true
        } }
        state.attempt("inventoryQuit") { if (inventoryStartAttempted) inventoryThread.quitSafely() }
        state.attempt("inventoryJoin") { if (inventoryStartAttempted) inventoryThread.join(5000) }
        state.attempt("terminalBoundary") { boundary(if (detached) "detached" else "detach-unconfirmed") }
        state.attempt("queueClose") { recorder.queue.close(); queueClosed = true }
        state.attempt("traceDrain") { drain() }
        state.attempt("traceFlush") {
            try { writer?.flush() } catch (error: Throwable) { state.exportFailures++; throw error }
        }
        state.attempt("traceClose") {
            try { writer?.close(); writerClosed = true } catch (error: Throwable) { state.exportFailures++; throw error }
        }
        state.attempt("traceStreamClose") {
            try { stream?.close(); streamClosed = true } catch (error: Throwable) { state.exportFailures++; throw error }
        }
        state.sinkInstalled = installed
        state.exactSinkDetached = detached
        state.observerFailures = recorder.queue.failureCount
        state.rowsRecorded = recorder.queue.recorded
        state.cleanupComplete = sinkReleased && lifecycleReleased && listenerReleased &&
            !inventoryThread.isAlive && queueClosed && writerClosed && streamClosed
        state.exportComplete = writerOpened && state.exportFailures == 0L &&
            state.outcomes["traceDrain"] == "SUCCESS" && state.outcomes["traceFlush"] == "SUCCESS" &&
            state.outcomes["traceClose"] == "SUCCESS" && state.outcomes["traceStreamClose"] == "SUCCESS" &&
            state.rowsWritten == state.rowsRecorded
        if (!state.captureComplete && state.primary == null) state.failed(IllegalStateException("Incomplete observation"))
        state.attempt("terminalMetadata") { detail.put("requestedWindowMs", state.requestedWindowMs)
            .put("windowStarted", state.windowStartMs != null).put("windowCompleted", state.windowCompleted)
            .put("measurementStartElapsedMs", state.windowStartMs ?: JSONObject.NULL)
            .put("measurementEndElapsedMs", state.windowEndMs ?: JSONObject.NULL)
            .put("actualMeasurementMs", state.actualMeasurementMs ?: JSONObject.NULL)
            .put("sinkInstallAttempted", sinkInstallAttempted).put("sinkInstalled", installed).put("exactSinkDetached", detached)
            .put("lifecycleRegistrationAttempted", lifecycleRegistrationAttempted)
            .put("inputListenerRegistrationAttempted", listenerRegistrationAttempted)
            .put("inventoryThreadStopped", !inventoryThread.isAlive)
            .put("cleanupComplete", state.cleanupComplete).put("exportComplete", state.exportComplete)
            .put("cleanupOutcomes", JSONObject(state.outcomes as Map<*, *>))
            .put("primaryFailureType", state.primary?.javaClass?.simpleName ?: JSONObject.NULL)
            .put("rowsRecorded", state.rowsRecorded).put("rowsWritten", state.rowsWritten)
            .put("exportFailures", state.exportFailures)
            .put("observerFailures", recorder.queue.failureCount)
            .put("callbackTotalNs", recorder.queue.totalCallbackNs).put("callbackMaxNs", recorder.queue.maxCallbackNs)
            .put("callbackTimingScope", "Only raw event/stage copy+enqueue duration; inventory and sample work excluded. UI receipt uses recorded Android uptime/elapsed clocks")
            .put("teardown", "Only exact observer/listeners removed; no Activity finish, Stop, reconnect, synthetic input or Phone command")
            .put("captureComplete", state.captureComplete)
        }
    }
    state.primary?.let { error ->
        if (state.interrupted) Thread.currentThread().interrupt()
        throw error
    }
}
