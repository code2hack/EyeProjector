package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.code2hack.eyeprojector.directhost.anchor.execution.CapturedAnchorRequest
import com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationCapture
import com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationTargets
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.FunctionIntent
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test-only lease. All handler reads/writes occur on the supplied original FIFO. */
internal class RegistrationDiagnosticLease<T>(
    private val enqueue: (() -> Unit) -> Unit,
    private val get: () -> ((T) -> Unit)?,
    private val set: (((T) -> Unit)?) -> Unit,
    private val observe: (T) -> Unit,
    private val observerFailure: (Throwable) -> Unit,
    private val originalFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    @Volatile private var closed = false
    private var original: ((T) -> Unit)? = null
    private var wrapper: ((T) -> Unit)? = null
    @Volatile var installed = "NOT_RUN"; private set
    @Volatile var restored = "NOT_RUN"; private set
    val installDone = CountDownLatch(1)
    val restoreDone = CountDownLatch(1)

    fun install() = enqueue {
        try { synchronized(lock) {
            if (closed) { installed = "CANCELED_BEFORE_INSTALL"; return@synchronized }
            val previous = get()
            if (previous == null) { installed = "NO_EXISTING_HANDLER"; return@synchronized }
            original = previous
            val own: (T) -> Unit = { request ->
                if (!closed) try { observe(request) } catch (error: Throwable) {
                    try { observerFailure(error) } catch (_: Throwable) { /* Diagnostic only. */ }
                }
                try { previous(request) } catch (error: Throwable) {
                    if (!closed) try { originalFailure(error) } catch (_: Throwable) { /* Keep the original. */ }
                    throw error
                }
            }
            wrapper = own; set(own); installed = "WRAPPED_EXISTING_HANDLER"
        } } finally { installDone.countDown() }
    }

    fun close() {
        // Shares install's lock: a delayed install cannot mutate the handler after closure.
        synchronized(lock) { closed = true }
        enqueue {
            try { synchronized(lock) {
                val own = wrapper
                restored = when {
                    own == null -> "NO_OWN_HANDLER_INSTALLED"
                    get() !== own -> "OWN_HANDLER_REPLACED_NOT_OVERWRITTEN"
                    else -> { set(original); "RESTORED_EXACT_PRIOR_HANDLER" }
                }
            } } finally { restoreDone.countDown() }
        }
    }
}

/** No Activity, input, config, preference, provider creation or Session operation. */
internal class AnchorRegistrationDiagnostic(
    private val context: Context, private val args: Bundle, private val result: Bundle,
) {
    private val pending = LinkedBlockingQueue<Pair<String, () -> Map<String, Any?>>>()
    private var sequence = 0L

    fun run() {
        result.putString("result", "DIAGNOSTIC_ERROR")
        var lease: RegistrationDiagnosticLease<CapturedAnchorRequest>? = null
        var output: java.io.BufferedWriter? = null
        fun emit(kind: String, fields: Map<String, Any?>) {
            val row = fields + mapOf("schema" to "eyeprojector.anchor-registration-diagnostic.v1",
                "kind" to kind, "sequence" to ++sequence, "pid" to Process.myPid(),
                "uid" to Process.myUid(), "receiptUptimeMs" to SystemClock.uptimeMillis(),
                "physicalInput" to false, "registrationDispatched" to false)
            output?.apply { write(JSONObject(row).toString()); newLine(); flush() }
        }
        fun drain() { while (true) { val row = pending.poll() ?: break; emit(row.first, row.second()) } }
        try {
            val id = args.getString("runId") ?: error("runId required")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,48}")))
            val seconds = args.getString("observeSeconds")?.toInt() ?: 10
            require(seconds in 0..30)
            val file = File(context.filesDir, "probe-$id-registration-diagnostic.jsonl")
            check(file.createNewFile()) { "Existing diagnostic file" }
            output = file.bufferedWriter()
            result.putString("file", "files/${file.name}")
            emit("ready", mapOf("runId" to id, "observeSeconds" to seconds,
                "scope" to "Existing process read/passive original-handler observation; no original failed-request inference from a fresh snapshot"))
            // get(context) may construct/install a new runtime. Read ONLY the existing singleton.
            val field = HostBootstrap::class.java.getDeclaredField("instance").apply { isAccessible = true }
            val bootstrap = field.get(null) as? HostBootstrap
            if (bootstrap == null) {
                emit("unavailable", mapOf("reason" to "NO_EXISTING_HOST_BOOTSTRAP"))
                result.putString("result", "DIAGNOSTIC_UNAVAILABLE")
                return
            }
            val runtime = bootstrap.anchorRuntime
            val status = bootstrap.status()
            emit("actual_bootstrap_status", mapOf("setup" to status.setup.name, "backend" to status.backend,
                "detail" to status.detail, "source" to projection(status.projectionContext),
                "uiTargetReaderConnected" to (UiTargetAccess.reader() != null)))
            fun requestMetadata(request: CapturedAnchorRequest, kind: String, witness: String) {
                val capturedIntent = request.original.intent.copy(context = request.original.intent.context.copy())
                val capturedSource = request.source.copy()
                val capturedSnapshot = copy(request.snapshot)
                val transaction = request.original.transactionId
                val identity = System.identityHashCode(request.original.identity)
                val pure = runCatching { AnchorRegistrationTargets.capture(capturedIntent, capturedSnapshot) }
                val pureFailure = failure(pure.exceptionOrNull())
                queue(kind) { mapOf("transactionId" to transaction,
                    "originalRouteIdentity" to identity, "intent" to intent(capturedIntent),
                    "source" to source(capturedSource), "snapshot" to snapshot(capturedSnapshot),
                    "pureCapture" to pure.getOrNull()?.let(::capture), "pureCaptureException" to pureFailure?.json(),
                    "witness" to witness) }
            }
            fun runtimeState(phase: String) {
                val done = CountDownLatch(1)
                runtime.onLane {
                    try {
                        val state = runtime.registrationProviderState.name
                        val installation = failure(runtime.registrationInstallationFailure)
                        val last = failure(runtime.coordinator.lastFailure)
                        val handlerPresent = runtime.coordinator.registration != null
                        queue("actual_runtime_state") { mapOf("phase" to phase, "providerState" to state,
                            "installationFailure" to installation?.json(), "coordinatorLastFailure" to last?.json(),
                            "registrationHandlerPresent" to handlerPresent) }
                        if (phase == "before") try {
                            val retainedField = runtime.coordinator.javaClass.getDeclaredField("registrationRequest").apply { isAccessible = true }
                            val retained = retainedField.get(runtime.coordinator) as? CapturedAnchorRequest
                            if (retained == null) queue("retained_original_request_absent") {
                                mapOf("witness" to "No retained request at this FIFO sample; no inference about earlier failure")
                            } else requestMetadata(retained, "retained_original_request", "RETAINED_ORIGINAL_REQUEST; historical retained transaction, no replay or handler invocation")
                        } catch (error: Throwable) {
                            val copied = failure(error)
                            queue("retained_request_read_exception") { mapOf("failure" to copied?.json()) }
                        }
                    } finally { done.countDown() }
                }
                emit("runtime_read", mapOf("phase" to phase, "completed" to done.await(2, TimeUnit.SECONDS)))
            }
            runtimeState("before")
            if (seconds > 0) {
                lease = RegistrationDiagnosticLease(runtime::onLane, { runtime.coordinator.registration },
                    { runtime.coordinator.registration = it }, { request ->
                        requestMetadata(request, "actual_registration_request", "Actual original handler request; not diagnostic dispatch")
                    }, { error -> val copied = failure(error); queue("observer_exception") { mapOf("failure" to copied?.json()) } },
                    { error -> val copied = failure(error); queue("original_handler_exception") { mapOf("failure" to copied?.json(), "rethrowsSameThrowable" to true) } })
                lease.install()
                val completed = lease.installDone.await(2, TimeUnit.SECONDS)
                emit("observer_install", mapOf("completed" to completed, "result" to lease.installed))
            }
            val sourceRead = AtomicReference<ManagedUiSource?>()
            val readSource = CountDownLatch(1)
            bootstrap.uiTargetContext(status.projectionContext) { sourceRead.set(it?.copy()); readSource.countDown() }
            val sourceDone = readSource.await(3, TimeUnit.SECONDS)
            val observed = sourceRead.get()
            emit("current_ui_target_context", mapOf("callbackReceived" to sourceDone,
                "source" to observed?.let(::source), "nullCause" to if (sourceDone && observed == null)
                    "Public callback exposes null, not its internal caught exception" else null))
            val pkg = observed?.foregroundPackage
            if (observed != null && pkg != null) {
                val targetRead = CountDownLatch(1)
                bootstrap.uiTargets(observed.context, pkg) { value ->
                    val copied = copy(value)
                    queue("current_ui_targets") { mapOf("snapshot" to snapshot(copied),
                        "witness" to "Fresh read only; not the original rejected registration request") }
                    targetRead.countDown()
                }
                emit("ui_targets_read", mapOf("callbackReceived" to targetRead.await(3, TimeUnit.SECONDS)))
            } else emit("ui_targets_unavailable", mapOf("reason" to "NO_CURRENT_SOURCE_FOREGROUND_PACKAGE"))
            val until = SystemClock.elapsedRealtime() + seconds * 1000L
            while (SystemClock.elapsedRealtime() < until) { drain(); Thread.sleep(50) }
            runtimeState("after")
            result.putString("result", "DIAGNOSTIC_COMPLETE")
        } catch (error: Throwable) {
            result.putString("result", "DIAGNOSTIC_ERROR")
            result.putString("failureType", error.javaClass.name)
            result.putString("failure", error.stackTraceToString())
            emit("diagnostic_exception", mapOf("failure" to failure(error)?.json()))
        } finally {
            try {
                lease?.let {
                    it.close()
                    val completed = it.restoreDone.await(3, TimeUnit.SECONDS)
                    result.putString("observerRestore", if (completed) it.restored else "PENDING_ORIGINAL_FIFO")
                    emit("observer_restore", mapOf("completed" to completed, "result" to result.getString("observerRestore")))
                }
                drain()
                emit("diagnostic_closed", mapOf("result" to result.getString("result"),
                    "observerInstalled" to lease?.installed, "observerRestore" to result.getString("observerRestore")))
            } finally { output?.close() }
        }
    }

    private fun queue(kind: String, fields: () -> Map<String, Any?>) {
        val uptime = SystemClock.uptimeMillis(); val elapsed = SystemClock.elapsedRealtimeNanos()
        pending.offer(kind to { fields() + mapOf("capturedUptimeMs" to uptime, "capturedElapsedNs" to elapsed) })
    }
    private data class Failure(val type: String, val message: String?, val stack: List<StackTraceElement>, val cause: Failure?) {
        fun json(): Map<String, Any?> = mapOf("type" to type, "message" to message,
            "stack" to stack.map { it.toString() }, "cause" to cause?.json())
    }
    private fun failure(error: Throwable?, cause: Boolean = true): Failure? = error?.let {
        Failure(it.javaClass.name, it.message, it.stackTrace.toList(), if (cause && it.cause !== it) failure(it.cause, false) else null)
    }
    private fun copy(s: UiTargetSnapshot) = s.copy(request = s.request?.let { it.copy(source = it.source.copy()) },
        windows = s.windows.map { w -> w.copy(window = w.window.copy(), targets = w.targets.map { it.copy(actionIds = it.actionIds.toList()) }) })
    private fun projection(c: ProjectionContext) = mapOf("ownerEpoch" to c.ownerEpoch, "projectionToken" to c.projectionToken)
    private fun source(s: ManagedUiSource): Map<String, Any?> = mapOf("context" to projection(s.context),
        "displayGeneration" to s.displayGeneration, "displayId" to s.displayId, "displayUniqueId" to s.displayUniqueId,
        "foregroundPackage" to s.foregroundPackage, "foregroundActivity" to s.foregroundActivity,
        "geometry" to mapOf("logical" to listOf(s.geometry.logical.width, s.geometry.logical.height),
            "physical" to listOf(s.geometry.physical.width, s.geometry.physical.height), "video" to listOf(s.geometry.video.width, s.geometry.video.height),
            "densityDpi" to s.geometry.densityDpi, "rotation" to s.geometry.rotation, "scalingDisabled" to s.geometry.scalingDisabled))
    private fun snapshot(s: UiTargetSnapshot): Map<String, Any?> = mapOf("status" to s.status.name,
        "failureClass" to s.failureClass, "startedUptimeMs" to s.startedUptimeMs, "finishedUptimeMs" to s.finishedUptimeMs,
        "request" to s.request?.let { mapOf("source" to source(it.source), "selectedPackage" to it.selectedPackage, "windowId" to it.windowId) },
        "windows" to s.windows.map { w -> mapOf("status" to w.status.name, "failureClass" to w.failureClass,
            "window" to mapOf("displayId" to w.window.displayId, "windowId" to w.window.windowId, "type" to w.window.type,
                "layer" to w.window.layer, "active" to w.window.active, "focused" to w.window.focused, "bounds" to w.window.bounds.let { listOf(it.left,it.top,it.right,it.bottom) }),
            "targets" to w.targets.map { t -> mapOf("ordinal" to t.ordinal, "windowId" to t.windowId, "packageName" to t.packageName,
                "resourceId" to t.resourceId, "className" to t.className, "bounds" to t.bounds.let { listOf(it.left,it.top,it.right,it.bottom) },
                "actionIds" to t.actionIds, "clickable" to t.clickable, "longClickable" to t.longClickable, "visible" to t.visible, "enabled" to t.enabled) }) })
    private fun intent(i: FunctionIntent): Map<String, Any?> = mapOf("kind" to i.kind.name, "source" to i.source.name,
        "gestureId" to i.gestureId, "sequence" to i.sequence, "usability" to i.context.usability.let { mapOf("ownerEpoch" to it.ownerEpoch,
            "incarnationId" to it.incarnation.id, "incarnationSequence" to it.incarnation.sequence, "revision" to it.revision, "inputRevision" to it.inputRevision, "mode" to it.mode.name) },
        "pointer" to i.context.pointer.let { mapOf("revision" to it.revision, "sequence" to it.sequence, "available" to it.available,
            "projected" to it.projected, "x" to it.x, "y" to it.y, "fit" to it.fit?.let { f -> listOf(f.left,f.top,f.width,f.height) },
            "geometry" to it.geometry?.let { g -> listOf(g.width,g.height) }, "videoTarget" to it.target()?.let { t -> listOf(t.x,t.y) }) })
    private fun capture(c: AnchorRegistrationCapture): Map<String, Any?> = when (c) {
        is AnchorRegistrationCapture.Rejected -> mapOf("outcome" to "REJECTED", "reason" to c.reason.name)
        is AnchorRegistrationCapture.Selected -> mapOf("outcome" to "SELECTED", "highlight" to c.target.highlight.let { listOf(it.left,it.top,it.right,it.bottom) })
    }
}
