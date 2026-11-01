package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorPreferences
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetAccess
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/** One finite app-process companion. No UiAutomation, daemon reflection or implicit Start/Stop. */
internal class AnchorAdbPhoneDriver(private val instrumentation: Instrumentation,
    private val context: Context, private val args: Bundle, private val result: Bundle) {
    private val id = args.getString("runId") ?: error("Run identity required")
    private val inbox = File(context.filesDir, "probe-$id-phone-inbox.json")
    private val outbox = File(context.filesDir, "probe-$id-phone-events.jsonl")
    private val backupFile = File(context.filesDir, "probe-$id-phone-anchor-prior.json")
    private val retirementFile = File(context.filesDir, "probe-$id-phone-retirement.json")
    private val bootstrap by lazy { HostBootstrap.get(context) }
    private val store by lazy { AppAnchorPreferences.open(context) }
    private val anchorAdapter by lazy { AnchorAdbPhoneAnchorAdapter(store) }
    private val commands = AnchorAdbPhoneCommands()
    private val ledger = AnchorAdbPhoneAnchorLedger()
    private val callbacks = ConcurrentLinkedQueue<Map<String, Any?>>()
    private var activity: Activity? = null
    private var stagedStart: Button? = null
    private var stagedStop: Button? = null
    private val traceLease = AnchorAdbPhoneTraceLease<PhoneInputTraceConfig> { bootstrap.configureInputTrace(it) }
    private var startSubmitted = false
    private var startsSubmitted = 0L
    private var ownedStart: Long? = null
    private var beforeStart: Pair<String, String>? = null
    private var ownedSource: Pair<String, String>? = null
    private var observedSuccessor = false
    private var verifiedRetirement = false
    private var finishRequested = false
    @Volatile private var acceptingCallbacks = true
    private var sequence = 0L
    private var failed = false
    private var writer: java.io.BufferedWriter? = null
    private var lastStatus: String? = null

    fun run() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        val seconds = args.getString("seconds")?.toInt() ?: 180
        require(seconds in 1..660) // Finite test parent, not a product expiration.
        check(outbox.createNewFile()) { "Existing mission outbox" }
        writer = FileOutputStream(outbox, true).bufferedWriter(Charsets.UTF_8)
        val started = SystemClock.elapsedRealtime()
        try {
            record("ready", mapOf("inbox" to inbox.name, "outbox" to outbox.name,
                "seconds" to seconds, "traceContract" to TRACE_CONTRACT, "restoreContract" to RESTORE_CONTRACT))
            while (!finishRequested && SystemClock.elapsedRealtime() - started < seconds * 1000L) {
                publishStatus()
                while (true) record("callback_observation", callbacks.poll() ?: break)
                if (inbox.exists()) {
                    val value = try { JSONObject(inbox.readText()) } catch (_: Exception) { null }
                    if (value != null) {
                        check(value.getString("runId") == id) { "Mission inbox run mismatch" }
                        val entries = value.getJSONArray("commands")
                        val list = (0 until entries.length()).map { entries.getJSONObject(it) }
                        commands.next(list.map { it.getString("id") })?.let { commandId ->
                            val command = list.single { it.getString("id") == commandId }
                            try {
                                record("command_begin", mapOf("commandId" to commandId, "operation" to command.getString("op")))
                                val outcome = execute(command)
                                record("command_result", outcome + mapOf("commandId" to commandId,
                                    "operation" to command.getString("op"), "targetAppAcknowledgment" to false))
                            } catch (error: Exception) {
                                record("command_result", mapOf("commandId" to commandId, "operation" to command.optString("op"),
                                    "result" to "INCONCLUSIVE", "errorType" to error.javaClass.simpleName))
                            }
                        }
                    }
                }
                Thread.sleep(50)
            }
        } catch (error: Throwable) {
            failed = true
            result.putString("failureType", error.javaClass.name)
        } finally {
            // No implicit Stop, cancellation or Anchor write on timeout/exception.
            try {
                if (traceLease.owned) {
                    val originalOwned = traceLease.close()
                    record("trace_config_restored", mapOf("result" to if (originalOwned)
                        "RESTORED_PRIOR_CONFIG" else "INCONCLUSIVE_UNEXPECTED_INTERVENING_CONFIG",
                        "startsOrStopsSession" to false))
                }
            } catch (error: Throwable) { failed = true; result.putString("traceRestoreFailure", error.javaClass.name) }
            acceptingCallbacks = false
            try {
                while (true) record("callback_observation", callbacks.poll() ?: break)
                record("parent_closed", mapOf("commandsClaimed" to commands.count,
                    "anchorBackupRetained" to (ledger.original != null), "ownedSource" to source(ownedSource),
                    "originalRetirementVerified" to verifiedRetirement, "cleanupNormalStopSubmitted" to false,
                    "activityFinishSubmitted" to false, "reason" to if (finishRequested) "host_finish_request" else "finite_parent_end"))
            } catch (_: Throwable) { failed = true }
            try { writer?.close() } catch (_: Throwable) { failed = true }
            writer = null
            result.putString("result", if (failed) "DIAGNOSTIC_ERROR" else "DIAGNOSTIC_COMPLETE")
            result.putString("scope", "Companion completion only; no control/app/physical PASS")
            result.putString("events", outbox.name)
            result.putBoolean("physicalPass", false)
            result.putBoolean("targetAppAcknowledgment", false)
        }
    }

    private fun execute(command: JSONObject): Map<String, Any?> = when (command.getString("op")) {
        "observe" -> mapOf("result" to "OBSERVED", "status" to status())
        "refresh_status" -> { bootstrap.refresh(); mapOf("result" to "SUBMITTED", "injectionKind" to "normal_status_refresh") }
        "configure_trace" -> configureTrace(command)
        "stage_host" -> stageHost()
        "normal_start", "normal_stop" -> normalControl(command)
        "capture_owned_source" -> captureSource()
        "projection_display" -> {
            val captured = bootstrap.status().projectionContext
            bootstrap.projectionDisplayState(captured) { value ->
                if (acceptingCallbacks) callbacks.add(mapOf("callbackKind" to "projection_display", "result" to if (value == null) "UNAVAILABLE" else "OBSERVED",
                    "requestId" to value?.requestId, "generation" to value?.generation, "displayId" to value?.displayId,
                    "launchStatus" to value?.status?.name, "source" to value?.context?.let { source(it.ownerEpoch to it.projectionToken) },
                    "callbackUptimeMs" to SystemClock.uptimeMillis(), "callbackElapsedNs" to SystemClock.elapsedRealtimeNanos()))
            }
            mapOf("result" to "SUBMITTED", "injectionKind" to "normal_projection_display_query", "source" to source(captured.ownerEpoch to captured.projectionToken))
        }
        "anchor_backup" -> backup()
        "anchor_readback" -> mapOf("result" to "OBSERVED", "anchor" to snapshotJson(anchorSnapshot()))
        "anchor_account_registration" -> accountRegistration(command)
        "verify_original_retirement" -> verifyRetirement()
        "anchor_restore" -> restoreAnchor()
        "finish_parent" -> { finishRequested = true; mapOf("result" to "REQUESTED", "scope" to "Test parent only, no Session stop") }
        else -> mapOf("result" to "UNAVAILABLE", "reason" to "unsupported_companion_operation")
    }

    private fun configureTrace(command: JSONObject): Map<String, Any?> {
        if (traceLease.owned) return mapOf("result" to "UNAVAILABLE", "reason" to "trace_config_already_owned")
        val path = command.getString("path")
        require(File(path).isAbsolute)
        val next = PhoneInputTraceConfig(path, id)
        traceLease.configure(next)
        writeNew(File(context.filesDir, "probe-$id-phone-trace-prior.json"), JSONObject()
            .put("runId", id).put("previousPresent", traceLease.prior != null)
            .put("previousPath", traceLease.prior?.path ?: JSONObject.NULL).put("previousRun", traceLease.prior?.run ?: JSONObject.NULL))
        return mapOf("result" to "CONFIGURED_NEXT_NORMAL_START", "path" to path, "runTag" to id,
            "previousPresent" to (traceLease.prior != null), "startsOrStopsSession" to false,
            "pathPrerequisite" to "Manager must establish existing parent and original Shell UID write access; not inferred by app")
    }

    private fun stageHost(): Map<String, Any?> {
        check(activity == null) { "Original Host already staged" }
        val created = instrumentation.startActivitySync(Intent(context, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity = created
        onMain {
            fun find(label: Int): Button? {
                val matches = mutableListOf<Button>()
                fun visit(view: View) {
                    if (view is Button && view.text == created.getString(label)) matches.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(created.window.decorView)
                return matches.singleOrNull()
            }
            stagedStart = find(R.string.host_start); stagedStop = find(R.string.host_stop)
            stagedStop?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        return mapOf("result" to "STAGED", "injectionKind" to "normal_own_activity_start_and_own_scroll",
            "activityIdentity" to System.identityHashCode(created), "startFound" to (stagedStart != null),
            "stopFound" to (stagedStop != null), "hostControls" to controlMetadata(),
            "scope" to "Stage before Hold; later Stop never launches/reopens a page")
    }

    private fun normalControl(command: JSONObject): Map<String, Any?> {
        val start = command.getString("op") == "normal_start"
        val host = activity ?: return mapOf("result" to "UNAVAILABLE", "reason" to "stage_host_before_controls_and_hold")
        val old = bootstrap.status()
        val route = command.optString("route", "own_view")
        var submitted = false
        onMain {
            if (!host.isDestroyed && !host.isFinishing && host.hasWindowFocus()) {
                if (route == "own_view") {
                    val button = if (start) stagedStart else stagedStop
                    if (button?.isAttachedToWindow == true && button.isShown && button.isEnabled) submitted = button.performClick()
                } else if (route == "controller") {
                    val controller = PhoneSessionController.get(context)
                    if (start) controller.start() else controller.stopFromUser()
                    submitted = true
                }
            }
        }
        if (start && submitted) {
            startSubmitted = true; startsSubmitted++; verifiedRetirement = false
            beforeStart = old.sessionEpoch to old.projectionToken
        }
        return mapOf("result" to if (submitted) "SUBMITTED" else "UNAVAILABLE", "injectionKind" to
            if (route == "own_view") "own_view_performClick" else "normal_controller_callback",
            "operation" to if (start) "Start" else "Stop", "sourceBefore" to source(old.sessionEpoch to old.projectionToken),
            "scope" to "Normal original production path; queued/click return is not admission or retirement acknowledgment")
    }

    private fun captureSource(): Map<String, Any?> {
        val value = bootstrap.status(); val captured = value.sessionEpoch to value.projectionToken
        if (!startSubmitted || !value.projectionContext.available || captured == beforeStart || value.backend !in setOf("SESSION_STARTING", "STREAMING"))
            return mapOf("result" to "UNAVAILABLE", "reason" to "new_live_source_after_own_normal_start_not_observed")
        if (ownedSource != null && ownedSource != captured) return mapOf("result" to "INCONCLUSIVE", "reason" to "original_owned_source_replaced")
        if (ownedSource == null) { ownedSource = captured; ownedStart = startsSubmitted }
        return mapOf("result" to "CAPTURED_ORIGINAL_SOURCE", "source" to source(captured), "status" to status())
    }

    private fun anchorSnapshot(): AnchorAdbPhoneAnchorLedger.Snapshot {
        return anchorAdapter.snapshot()
    }

    private fun backup(): Map<String, Any?> {
        if (ledger.original != null) return mapOf("result" to "ALREADY_BACKED_UP", "backup" to backupFile.name)
        val value = anchorSnapshot()
        val current = bootstrap.status()
        writeNew(backupFile, JSONObject(snapshotJson(value)).put("runId", id)
            .put("source", JSONObject(source(current.sessionEpoch to current.projectionToken)))
            .put("ownedSource", JSONObject(source(ownedSource))))
        ledger.backup(value)
        return mapOf("result" to "BACKED_UP", "backup" to backupFile.name, "anchor" to snapshotJson(value), "writesAnchor" to false)
    }

    private fun accountRegistration(command: JSONObject): Map<String, Any?> {
        val current = bootstrap.status().projectionContext
        if (ownedSource == null || ownedSource != (current.ownerEpoch to current.projectionToken) ||
            observedSuccessor || UiTargetAccess.reader() == null) return mapOf("result" to "UNAVAILABLE",
            "reason" to "original_live_source_or_connected_ui_access_missing")
        val witness = command.getString("registrationWitness"); require(witness.isNotBlank())
        val value = anchorSnapshot()
        val outcome = ledger.accountRegisteredValue(value, witness)
        if (outcome == "ACCOUNTED_HOST_ATTRIBUTED_REGISTRATION") writeNew(File(context.filesDir, "probe-$id-phone-anchor-test.json"),
            JSONObject(snapshotJson(value)).put("runId", id).put("registrationWitness", witness))
        return mapOf("result" to outcome, "anchor" to snapshotJson(value), "writesAnchor" to false,
            "attribution" to "Host attributes actual post-chooser store value; this command manufactures no registration")
    }

    private fun verifyRetirement(): Map<String, Any?> {
        val original = ownedSource ?: return mapOf("result" to "UNAVAILABLE", "reason" to "original_source_not_captured")
        val readStarted = SystemClock.elapsedRealtimeNanos()
        val local = File(context.filesDir, "bootstrap-status.json")
        var raw: String? = local.takeIf { it.exists() }?.readText()
        var origin = "app_normal_bootstrap_status_file"
        var witness = local.name
        if (retirementFile.exists()) {
            val forwarded = JSONObject(retirementFile.readText())
            check(forwarded.getString("runId") == id)
            raw = forwarded.getString("rawStatus")
            check(sha256(raw.toByteArray()) == forwarded.getString("sha256"))
            witness = forwarded.getString("sourceWitness"); check(witness.isNotBlank())
            origin = "host_forwarded_original_normal_status_readback"
        }
        if (raw == null) return mapOf("result" to "UNAVAILABLE", "reason" to "normal_status_readback_missing")
        val root = JSONObject(raw)
        val candidates = mutableListOf<JSONObject>()
        root.optJSONObject("managedProjection")?.let(candidates::add)
        root.optJSONArray("retiredManagedProjections")?.let { for (i in 0 until it.length()) candidates.add(it.getJSONObject(i)) }
        val matched = candidates.singleOrNull { it.optString("ownerEpoch") == original.first && it.optString("projectionToken") == original.second }
        val trace = matched?.optJSONObject("inputTrace")
        val proof = matched?.let { AnchorAdbPhoneRetirement(it.optString("ownerEpoch"), it.optString("projectionToken"),
            it.optString("state"), trace?.optBoolean("enabled") == true, trace?.optBoolean("closing") == true,
            trace?.optBoolean("terminated") == true, trace?.takeIf { t -> t.has("originalObligations") }?.getInt("originalObligations")) }
        verifiedRetirement = proof?.matches(original.first, original.second) == true
        return mapOf("result" to if (verifiedRetirement) "ORIGINAL_SESSION_RETIREMENT_OBSERVED" else "INCONCLUSIVE", "source" to source(original),
            "statusOrigin" to origin, "sourceWitness" to witness, "statusSha256" to sha256(raw.toByteArray()),
            "statusReadStartElapsedNs" to readStarted, "statusReadEndElapsedNs" to SystemClock.elapsedRealtimeNanos(),
            "managedState" to proof?.managedState, "traceClosing" to proof?.closing, "traceTerminated" to proof?.terminated,
            "originalObligations" to proof?.originalObligations, "scope" to "Exact source and terminal original observer metadata; no clock subtraction/contact join")
    }

    private fun restoreAnchor(): Map<String, Any?> {
        if (observedSuccessor || ownedStart != startsSubmitted || bootstrap.status().projectionContext.available)
            return mapOf("result" to "INCONCLUSIVE", "reason" to "live_or_later_start_source_not_retired",
                "writesAnchor" to false)
        var mutation: AnchorAdbPhoneAnchorAdapter.Restore? = null
        val outcome = ledger.restore(verifiedRetirement, anchorSnapshot()) { expected, prior ->
            anchorAdapter.restore(expected, prior).also { mutation = it }.snapshot
        }
        return mapOf("result" to if (mutation?.superseded == true) "INCONCLUSIVE_APPLIED_THEN_SUPERSEDED" else outcome,
            "anchor" to snapshotJson(anchorSnapshot()), "writesAnchor" to (mutation?.applied == true),
            "appliedThenSuperseded" to mutation?.superseded, "committedRevision" to mutation?.committedRevision,
            "restoreContract" to RESTORE_CONTRACT,
            "scope" to "Atomic expected test-context+complete-value compare/restore; no refresh/retry, prior revision never restored")
    }

    private fun status(): Map<String, Any?> {
        val value = bootstrap.status()
        if (ownedSource != null && value.projectionContext.available && ownedSource != (value.sessionEpoch to value.projectionToken))
            observedSuccessor = true
        val connected = UiTargetAccess.reader() != null
        return mapOf("setup" to value.setup.name, "backend" to value.backend, "detail" to value.detail,
            "source" to source(value.sessionEpoch to value.projectionToken), "uiAccessConnected" to connected,
            "registrationAvailability" to if (!connected) "UNAVAILABLE_CONNECTED_ACCESS_NOT_OBSERVED" else "CONNECTED_ONLY_NOT_TARGET_READINESS",
            "stagedHostIdentity" to activity?.let(System::identityHashCode), "hostControls" to controlMetadata(),
            "originalOwnedSource" to source(ownedSource), "successorObserved" to observedSuccessor)
    }
    private fun controlMetadata(): Map<String, Any?> {
        var value: Map<String, Any?> = emptyMap()
        onMain {
            fun state(button: Button?) = button?.let { mapOf("viewIdentity" to System.identityHashCode(it),
                "attached" to it.isAttachedToWindow, "shown" to it.isShown, "enabled" to it.isEnabled) }
            value = mapOf("focused" to activity?.hasWindowFocus(), "finishing" to activity?.isFinishing,
                "destroyed" to activity?.isDestroyed, "start" to state(stagedStart), "stop" to state(stagedStop))
        }
        return value
    }
    private fun publishStatus() {
        val value = status(); val encoded = JSONObject(value).toString()
        if (encoded != lastStatus) { lastStatus = encoded; record("status_observed", value) }
    }
    private fun snapshotJson(value: AnchorAdbPhoneAnchorLedger.Snapshot) = mapOf("packageName" to FIXTURE,
        "instanceId" to value.instanceId, "revision" to value.revision, "present" to (value.encoded != null),
        "encoded" to value.encoded, "mode" to value.mode)
    private fun source(value: Pair<String, String>?) = mapOf("ownerEpoch" to value?.first, "projectionToken" to value?.second)
    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun record(kind: String, fields: Map<String, Any?>) {
        val row = JSONObject(fields).put("schema", "eyeprojector.anchor-adb-phone.v1").put("runId", id)
            .put("sequence", ++sequence).put("kind", kind).put("receiptUptimeMs", SystemClock.uptimeMillis())
            .put("receiptElapsedNs", SystemClock.elapsedRealtimeNanos()).put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid()).put("physicalPass", false).put("targetAppAcknowledgment", false)
        writer?.apply { write(row.toString()); newLine(); flush() }
    }
    private fun writeNew(file: File, value: JSONObject) {
        check(file.createNewFile()) { "Existing mission backup" }
        file.writeText(value.toString(2) + "\n")
    }
    companion object {
        const val FIXTURE = "com.code2hack.eyeprojector.hostprobe"
        const val TRACE_CONTRACT = "68a7827b861ad953b5757e0048c9ebfbba017564337fc058d4fbde00a5a35f42"
        const val RESTORE_CONTRACT = "5444b14ffdbb30f2209dfec666ba429485047871729f8c1e28a7ff93e916127b"
        private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}
