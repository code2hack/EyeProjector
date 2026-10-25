package com.code2hack.eyeprojector.directhost.notifications.probetest

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.code2hack.eyeprojector.directhost.notifications.*
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.protocol.*
import kotlin.system.exitProcess

private const val privateFailure = "PRIVATE_EXCEPTION_SENTINEL_NEVER_REPORT"
private const val privateKey = "PRIVATE_RAW_KEY"
private const val privateOpaque = "PRIVATE_OPAQUE_ID_NEVER_REPORT"
private fun expect(value: Boolean, message: String) { check(value) { message } }

private class Probe(val context: Context = Context()) {
    val instrumentation = Instrumentation()
    val arguments = Bundle().apply { putString("runId", "host_probe_130"); putString("waitMs", "1000") }
    val output = Bundle()
    fun run(mode: String = "status"): Map<String, String> {
        // Explicit host clock only; diagnostic deadlines do not consume real test wall time.
        if (SystemClock.stepMillis == 0L) SystemClock.stepMillis = 2000L
        arguments.putString("notificationMode", mode)
        NotificationProbeChecks.run(instrumentation, context, arguments, output)
        return output.snapshot().also(::sanitized)
    }
    fun simulatedPostingEvent(origin: NotificationEventOrigin = NotificationEventOrigin.POSTED_CALLBACK,
        adjustKey: Boolean = false, adjustTime: Boolean = false) {
        context.notifications.onNotify = { record ->
            Looper.onMain {
                context.runtime.emit(NotificationIngressObservation(NotificationEventResult(
                    object : NotificationRecord("example.unrelated", "unused", 0) {
                        override val key: String get() = error("Unrelated key must not be read")
                        override val postTime: Long get() = error("Unrelated timestamp must not be read")
                    }, NotificationEventOrigin.POSTED_CALLBACK, NotificationEventKind.NEW_RECORD)))
                context.runtime.emit(NotificationIngressObservation(NotificationEventResult(
                    NotificationRecord(record.packageName, record.key + if (adjustKey) "_other" else "",
                        record.postTime + if (adjustTime) 1 else 0), origin, NotificationEventKind.NEW_RECORD)))
            }
        }
    }
    fun observersClosed() {
        expect(context.runtime.eventRegistrations == 0 && context.runtime.statusRegistrations == 0 && context.runtime.pipelineRegistrations == 0, "Observer registrations leaked")
        expect(context.runtime.eventCloses == 1 && context.runtime.statusCloses == 1 && context.runtime.pipelineCloses == 1, "Each observer must close exactly once")
    }
}

private fun sanitized(fields: Map<String, String>) {
    val rendered = fields.entries.joinToString("|") { it.key + "=" + it.value }
    expect(!rendered.contains(privateKey) && !rendered.contains(privateFailure) &&
        !rendered.contains("PRIVATE_PAYLOAD") && !rendered.contains("UNRELATED_PRIVATE") && !rendered.contains(privateOpaque) &&
        !rendered.contains("eyeprojector_probe_"), "Diagnostic rendered raw metadata or exception/payload material")
    expect(fields["result"] != "PASS", "Diagnostic must not turn readiness or receipt into PASS")
}

private fun exactCleanup(probe: Probe) {
    val manager = probe.context.notifications
    expect(manager.posts.size == 1 && manager.canceled == manager.posts, "Cleanup must cancel only the exact attempted pair")
    expect(manager.created.size == 1 && manager.deleted == manager.created, "Cleanup must delete only the exact owned channel")
    expect(manager.registrationCountAtCancel == 0, "Observers must detach before cancellation can emit removal")
    expect(manager.records.isEmpty() && manager.channels.isEmpty(), "Owned resources must be released")
    probe.observersClosed()
}

/** Explicit immutable pipeline fact fixtures, never a replacement main-source claim/queue. */
private class PipelineTokens(val observation: NotificationIngressObservation, packageName: String) {
    val selection = NotificationSelection(observation, PhoneSessionController())
    val demand = ActivationHandle(privateOpaque + "_H", ActivationCause.NOTIFICATION, privateOpaque + "_D", ActivationGoal.PROJECTED)
    private val attempt = ActivationAttempt(demand, privateOpaque + "_A")
    val context = ProjectionContext(privateOpaque + "_C", privateOpaque + "_TOKEN")
    val ready = ActivationReady(demand, attempt, context,
        ActivationBinding(privateOpaque + "_H", demand.demandId, ActivationGoal.PROJECTED, 1, attempt.id,
            ReceiverIncarnation(privateOpaque + "_RECEIVER", 1), 1), 1)
    val arm = Any()
    val carrier = NotificationLaunchCarrier(selection, ready, Any())
    val result = ProjectionDisplayState(requestId = 1, generation = "fixture", displayId = 7, requestedPackage = packageName,
        status = ProjectionLaunchStatus.STARTED, foregroundPackage = packageName, context = context)
    fun event(stage: NotificationPipelineStage, outcome: NotificationDispatchOutcome? = null,
        readyValue: ActivationReady<ProjectionContext>? = if (stage == NotificationPipelineStage.DEMAND_ADMITTED) null else ready,
        carrierValue: NotificationLaunchCarrier? = if (stage in setOf(NotificationPipelineStage.SUBMISSION_STARTED, NotificationPipelineStage.SETTLED)) carrier else null) =
        NotificationPipelineObservation(stage, observation, selection, demand, if (readyValue == null) null else arm,
            readyValue, carrierValue, outcome,
            if (outcome?.commitment == NotificationCommitment.COMMITTED) LaunchCommitPermit.State.COMMITTED else LaunchCommitPermit.State.PENDING)
}

private fun pipelineProbe(delivery: (Probe, NotificationIngressObservation, PipelineTokens) -> Unit): Probe = Probe().also { probe ->
    probe.context.notifications.onNotify = { platform ->
        val ingress = NotificationIngressObservation(NotificationEventResult(
            NotificationRecord(platform.packageName, platform.key, platform.postTime), NotificationEventOrigin.POSTED_CALLBACK, NotificationEventKind.NEW_RECORD))
        val tokens = PipelineTokens(ingress, platform.packageName)
        Looper.onMain { delivery(probe, ingress, tokens) }
    }
}

private fun scenarios(): List<Pair<String, () -> Unit>> = listOf(
    "actual helper status-only reports independent facts without notify or state mutation" to {
        val p = Probe()
        val chosen = p.context.chosen.toSet(); val enabled = p.context.enabled.toMap()
        p.context.runtime.currentStatus = NotificationAccessStatus(NotificationAccessApproval.DENIED, true, NotificationListenerBaseline.OBSERVED, true)
        val report = p.run()
        expect(report["notification.outcome"] == "STATUS_ONLY" && report["result"] == "DIAGNOSTIC_COMPLETE", "Status-only outcome mismatch")
        expect(report["notification.listenerApproval"] == "DENIED" && report["notification.listenerConnected"] == "true", "Access approval and actual connection were conflated")
        expect(p.context.notifications.posts.isEmpty() && p.context.notifications.created.isEmpty(), "Status-only performed notification effects")
        expect(p.context.chosen == chosen && p.context.enabled == enabled, "Probe modified canonical fixture state")
        expect(report["notification.permissionMutated"] == "false" && report["notification.canonicalChoicesMutated"] == "false", "Mutation scope omitted")
        expect(report["notification.claim"] == "UNOBSERVED" && report["notification.pipelineCorrelation"] == "NO_MATCHED_ORIGINAL_RECEIPT", "Readiness was promoted to pipeline evidence")
        p.observersClosed()
    },
    "actual helper denied or undeclared Android33 posting readiness never notifies" to {
        for (kind in listOf("grant", "declaration", "disabled")) {
            val p = Probe()
            when (kind) {
                "grant" -> p.context.grantedPermissions = emptySet()
                "declaration" -> p.context.declaredPermissions = emptySet()
                else -> p.context.notifications.notificationsEnabled = false
            }
            val report = p.run("post-owned")
            expect(report["notification.outcome"] == "ORDINARY_POST_PERMISSION_SETUP_REQUIRED", "Posting prerequisite was not reported")
            expect(p.context.notifications.posts.isEmpty() && p.context.notifications.created.isEmpty(), "Denied readiness performed posting effects")
            p.observersClosed()
        }
    },
    "actual helper unchosen readiness returns setup required without notifying" to {
        val p = Probe(); p.context.chosen = emptySet()
        val report = p.run("post-owned")
        expect(report["notification.outcome"] == "CHOSEN_SETUP_REQUIRED", "Unchosen app incorrectly passed readiness")
        expect(p.context.notifications.posts.isEmpty(), "Unchosen readiness notified")
        p.observersClosed()
    },
    "actual helper listener unavailability is finite diagnostic readiness not notify" to {
        val p = Probe()
        p.context.runtime.currentStatus = NotificationAccessStatus(NotificationAccessApproval.GRANTED, false, NotificationListenerBaseline.DISCONNECTED, true)
        SystemClock.stepMillis = 2000
        val report = p.run("post-owned")
        expect(report["notification.outcome"] == "LISTENER_BASELINE_NOT_READY_WITHIN_WINDOW", "Unavailable listener readiness omitted")
        expect(p.context.notifications.posts.isEmpty(), "Disconnected listener readiness notified")
        p.observersClosed()
    },
    "actual helper observer-driven owned notify simulation correlates exact metadata and releases exact resources" to {
        val p = Probe(); p.simulatedPostingEvent()
        val report = p.run("post-owned")
        expect(report["notification.outcome"] == "OWNED_SYSTEM_RECEIPT_OBSERVED", "Simulated observer delivery was not correlated")
        expect(report["notification.eventCorrelation"] == "EXACT_OWNED_PLATFORM_KEY_AND_VERSION", "Owned correlation qualifier missing")
        expect(report["notification.systemReceipt"] == "OBSERVED" && report["notification.eventOrigin"] == "POSTED_CALLBACK", "Posted observation missing")
        expect(report["notification.claim"] == "UNOBSERVED" && report["notification.foreground"] == "UNOBSERVED", "Metadata receipt invented launch evidence")
        expect(p.context.runtime.eventDeliveries == 2, "Host fixture did not exercise unrelated and matching observer records")
        exactCleanup(p)
    },
    "actual helper Off negative control still observes own notification without canonical writes" to {
        val p = Probe(); p.context.enabled[p.context.packageName] = false; p.simulatedPostingEvent()
        val report = p.run("post-owned")
        expect(report["notification.eligibilityAtDiagnosticRead"] == "OFF" && report["notification.outcome"] == "OWNED_SYSTEM_RECEIPT_OBSERVED", "Off was turned into an ingress filter")
        expect(p.context.enabled[p.context.packageName] == false, "Probe changed Off preference")
        exactCleanup(p)
    },
    "actual helper keeps owned notification until receipt status and remaining finite window expire" to {
        val p = Probe(); p.simulatedPostingEvent(); SystemClock.stepMillis = 1L
        var reported = false
        p.instrumentation.onStatus = { status ->
            if (status.getString("notification.stage") == "RECEIPT_OBSERVED_AWAITING_ORIGINAL_PIPELINE") {
                expect(p.context.notifications.canceled.isEmpty() && p.context.notifications.records.size == 1, "Receipt arrival prematurely cleaned the asynchronous fixture")
                expect((status.getString("notification.remainingWindowMs")?.toLongOrNull() ?: 0L) > 0, "Retained observation had no remaining diagnostic window")
                reported = true
                SystemClock.advance(2000)
            }
        }
        p.context.notifications.onCancel = {
            expect(reported && SystemClock.elapsedRealtime() >= 1000L, "Owned cleanup preceded its observation deadline")
        }
        val report = p.run("post-owned")
        expect(reported && report["notification.postReceiptObservation"] == "REMAINDER_OF_DIAGNOSTIC_WINDOW", "Receipt was not retained for the declared finite observation")
        expect(report["notification.claim"] == "UNOBSERVED" && report["notification.settlement"] == "UNOBSERVED", "Holding the fixture invented pipeline completion")
        exactCleanup(p)
    },
    "actual helper API32 owned posting does not require Android33 declaration or grant" to {
        Build.VERSION.SDK_INT = 32
        val p = Probe(); p.context.declaredPermissions = emptySet(); p.context.grantedPermissions = emptySet(); p.simulatedPostingEvent()
        val report = p.run("post-owned")
        expect(report["notification.targetPostingPermission"] == "NOT_REQUIRED_API32", "API32 permission qualifier missing")
        expect(report["notification.outcome"] == "OWNED_SYSTEM_RECEIPT_OBSERVED", "API32 route was incorrectly blocked")
        exactCleanup(p)
    },
    "actual helper wrong key version or removed event cannot satisfy exact owned posting" to {
        for (kind in listOf("key", "version", "removed")) {
            val p = Probe()
            p.simulatedPostingEvent(if (kind == "removed") NotificationEventOrigin.REMOVED_CALLBACK else NotificationEventOrigin.POSTED_CALLBACK,
                adjustKey = kind == "key", adjustTime = kind == "version")
            SystemClock.stepMillis = 2000
            val report = p.run("post-owned")
            expect(report["notification.outcome"] == "OWNED_RECEIPT_NOT_CORRELATED_WITHIN_WINDOW" && report["notification.systemReceipt"] == "UNOBSERVED", "Nonmatching record was mislabeled exact owned delivery")
            exactCleanup(p)
        }
    },
    "actual helper external observe mode distinguishes package evidence and target-only permission scope" to {
        val p = Probe(); val external = "example.externalfixture"
        p.context.chosen = setOf(external); p.arguments.putString("postingPackage", external)
        p.instrumentation.onStatus = { status ->
            if (status.getString("notification.stage") == "AWAITING_ORDINARY_EXTERNAL_APP_EVENT") Looper.onMain { p.context.runtime.emit(NotificationIngressObservation(NotificationEventResult(
                NotificationRecord(external, privateKey, 23), NotificationEventOrigin.POSTED_CALLBACK, NotificationEventKind.UPDATED_OR_REPOSTED))) }
        }
        val report = p.run("observe-event")
        expect(report["notification.outcome"] == "PACKAGE_CALLBACK_OBSERVED" && report["notification.eventCorrelation"] == "PACKAGE_CALLBACK_ONLY", "External callback was overstated or absent")
        expect(report["notification.postingPermissionScope"] == "TARGET_APP_ONLY_EXTERNAL_APP_UNOBSERVED", "Target permission was attributed to another app")
        expect(p.context.notifications.posts.isEmpty() && p.instrumentation.statuses.map { it["notification.stage"] } ==
            listOf("AWAITING_ORDINARY_EXTERNAL_APP_EVENT", "RECEIPT_OBSERVED_AWAITING_ORIGINAL_PIPELINE"), "Observe mode posted or omitted its explicit observation/retention stage")
        p.observersClosed()
    },
    "actual helper external observation window excludes readiness traffic and accepts only later prompted callback" to {
        for (later in listOf(false, true)) {
            val p = Probe(); val external = "example.externalfixture"
            p.context.chosen = setOf(external); p.arguments.putString("postingPackage", external)
            p.context.runtime.onRefresh = {
                p.context.runtime.emit(NotificationIngressObservation(NotificationEventResult(
                    NotificationRecord(external, privateKey, 11), NotificationEventOrigin.POSTED_CALLBACK, NotificationEventKind.DUPLICATE_OR_REPLAY)))
            }
            if (later) p.instrumentation.onStatus = { status ->
                if (status.getString("notification.stage") == "AWAITING_ORDINARY_EXTERNAL_APP_EVENT") Looper.onMain { p.context.runtime.emit(NotificationIngressObservation(NotificationEventResult(
                    NotificationRecord(external, privateKey, 12), NotificationEventOrigin.POSTED_CALLBACK, NotificationEventKind.UPDATED_OR_REPOSTED))) }
            }
            SystemClock.stepMillis = 2000
            val report = p.run("observe-event")
            expect(report["notification.outcome"] == if (later) "PACKAGE_CALLBACK_OBSERVED" else "NO_CALLBACK_WITHIN_WINDOW", "Readiness traffic satisfied the later external observation window")
            expect(report["notification.systemReceipt"] == if (later) "OBSERVED" else "UNOBSERVED", "Pre-window callback was promoted to prompted evidence")
            if (later) expect(report["notification.eventKind"] == "UPDATED_OR_REPOSTED", "The report described stale readiness traffic")
            expect(p.context.notifications.posts.isEmpty(), "External observer mode generated its own notification")
            p.observersClosed()
        }
    },
    "actual helper late callback after false observation sample cannot become timed-out receipt success" to {
        val p = Probe(); val external = "example.externalfixture"
        p.context.chosen = setOf(external); p.arguments.putString("postingPackage", external)
        SystemClock.stepMillis = 1
        p.instrumentation.onStatus = { status ->
            if (status.getString("notification.stage") == "AWAITING_ORDINARY_EXTERNAL_APP_EVENT") {
                p.instrumentation.afterNextMain = {
                    SystemClock.advance(2000)
                    p.context.runtime.emit(NotificationIngressObservation(NotificationEventResult(
                        NotificationRecord(external, privateKey, 45), NotificationEventOrigin.POSTED_CALLBACK, NotificationEventKind.NEW_RECORD)))
                }
            }
        }
        val report = p.run("observe-event")
        expect(p.context.runtime.eventDeliveries == 1 && report["notification.outcome"] == "NO_CALLBACK_WITHIN_WINDOW" &&
            report["notification.systemReceipt"] == "UNOBSERVED", "Late callback was described as a selected timely receipt")
        p.observersClosed()
    },
    "actual helper cannot post as a different requested package" to {
        val p = Probe(); p.context.chosen = setOf("example.externalfixture"); p.arguments.putString("postingPackage", "example.externalfixture")
        val report = p.run("post-owned")
        expect(report["notification.outcome"] == "POSTING_PACKAGE_IS_NOT_TARGET_APP" && p.context.notifications.posts.isEmpty(), "Probe spoofed posting package")
        p.observersClosed()
    },
    "actual helper channel creation partial failure cleans exact channel and sanitizes exception" to {
        val p = Probe(); p.context.notifications.createFailure = SecurityException(privateFailure)
        val report = p.run("post-owned")
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.faultFamily"] == "SECURITY", "API failure was not sanitized")
        expect(p.context.notifications.created == p.context.notifications.deleted && p.context.notifications.created.size == 1, "Partially-created channel was not cleaned")
        expect(p.context.notifications.posts.isEmpty() && p.context.notifications.canceled.isEmpty(), "Creation failure canceled an unattempted notification")
        p.observersClosed()
    },
    "actual helper main-thread chosen failure returns sanitized structured fault without uncaught main exception" to {
        val p = Probe(); p.context.eligibilityFailure = IllegalStateException(privateFailure)
        val report = p.run("post-owned")
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.faultFamily"] == "STATE", "Main failure was not transported as a structured diagnostic")
        expect(p.instrumentation.unhandledMainFailures == 0, "A diagnostic exception escaped the main Runnable")
        expect(p.context.notifications.posts.isEmpty() && p.context.notifications.created.isEmpty(), "Failed eligibility read posted or created a channel")
        p.observersClosed()
    },
    "actual helper failed notify or metadata query preserves attempted cleanup and fault family" to {
        for (failure in listOf("notify", "active")) {
            val p = Probe()
            if (failure == "notify") p.context.notifications.notifyFailure = IllegalStateException(privateFailure)
            else p.context.notifications.activeFailure = IllegalArgumentException(privateFailure)
            val report = p.run("post-owned")
            expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.outcome"] == "DIAGNOSTIC_FAULT", "Posting/API fault was misreported as receipt")
            expect(report["notification.faultFamily"] == if (failure == "notify") "STATE" else "ARGUMENT", "Wrong sanitized fault family")
            exactCleanup(p)
        }
    },
    "actual helper independently closes observers and deletes channel after cleanup failure" to {
        val p = Probe(); p.simulatedPostingEvent()
        p.context.runtime.eventCloseFailure = IllegalStateException(privateFailure)
        p.context.notifications.cancelFailure = IllegalStateException(privateFailure)
        val report = p.run("post-owned")
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.observerCleanup"] == "FAULT_OR_INCOMPLETE", "Cleanup failure was hidden")
        expect(p.context.runtime.eventCloses == 1 && p.context.runtime.statusCloses == 1 && p.context.runtime.pipelineCloses == 1, "One failing close prevented independent cleanup")
        expect(p.context.notifications.deleted == p.context.notifications.created && p.context.notifications.deleted.size == 1, "Notification cancel failure prevented owned channel cleanup")
        expect(report["notification.ownedNotificationCleanup"] == "FAULT" && report["notification.ownedChannelCleanup"] == "DELETE_RETURNED", "Cleanup outcomes were conflated")
    },
    "actual helper resource cleanup fault does not mislabel successful observer detachment" to {
        val p = Probe(); p.simulatedPostingEvent(); p.context.notifications.deleteFailure = IllegalStateException(privateFailure)
        val report = p.run("post-owned")
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.cleanup"] == "FAULT_OR_INCOMPLETE", "Resource cleanup fault was hidden")
        expect(report["notification.observerCleanup"] == "CLOSE_RETURNED" && report["notification.ownedChannelCleanup"] == "FAULT", "Resource and observer cleanup were conflated")
        p.observersClosed()
    },
    "actual helper pipeline close fault cannot skip event or status detachment" to {
        val p = Probe(); p.context.runtime.pipelineCloseFailure = IllegalStateException(privateFailure)
        val report = p.run()
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.observerCleanup"] == "FAULT_OR_INCOMPLETE",
            "Pipeline observer cleanup fault was hidden")
        expect(p.context.runtime.pipelineCloses == 1 && p.context.runtime.eventCloses == 1 && p.context.runtime.statusCloses == 1 &&
            p.context.runtime.pipelineRegistrations + p.context.runtime.eventRegistrations + p.context.runtime.statusRegistrations == 0,
            "Pipeline close fault prevented another exact observer detachment")
    },
    "actual helper preserves unrelated notification and channel resources" to {
        val p = Probe(); p.simulatedPostingEvent()
        // Same package and numeric ID deliberately require the independent tag comparison.
        val unrelated = object : StatusBarNotification(p.context.packageName, 1, "UNRELATED_PRIVATE_TAG", "unused", 4) {
            override val key: String get() = error("Unrelated app key must not be read")
            override val postTime: Long get() = error("Unrelated app timestamp must not be read")
        }
        p.context.notifications.records.add(unrelated)
        val channel = android.app.NotificationChannel("unrelated-existing", "Other", NotificationManager.IMPORTANCE_DEFAULT)
        p.context.notifications.channels[channel.id] = channel
        val report = p.run("post-owned")
        expect(report["notification.outcome"] == "OWNED_SYSTEM_RECEIPT_OBSERVED", "Owned event was not correlated beside unrelated data")
        expect(p.context.notifications.records.single() === unrelated && p.context.notifications.channels.values.single() === channel, "Cleanup altered unrelated resources")
        expect(p.context.notifications.canceled == p.context.notifications.posts && p.context.notifications.deleted == p.context.notifications.created, "Cleanup was not scoped to its invocation")
        p.observersClosed()
    },
    "actual helper invalid caller and wait values never echo arbitrary text" to {
        for (key in listOf("runId", "postingPackage", "waitMs")) {
            val p = Probe(); p.arguments.putString(key, "$privateFailure<>/$privateKey")
            val report = p.run()
            expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.outcome"] == "INVALID_ARGUMENTS", "Invalid arguments were not bounded/sanitized")
            expect(p.context.notifications.posts.isEmpty(), "Invalid arguments performed posting effects")
        }
    },
    "actual report identity labels neither render opaque objects nor imply admission acceptance" to {
        val report = NotificationProbeReport("host_report_130", "example.report")
        val identity = object { override fun toString(): String = error("Opaque identity must not be rendered") }
        val receipt = NotificationProbeReport.Receipt(identity, Any(), Any(), identity, "example.report", privateKey, 7,
            "POSTED_CALLBACK", "UPDATED_OR_REPOSTED", "READY", true)
        report.receipt(receipt)
        expect(!report.hasOwnedReceipt(privateKey, 8) && report.hasOwnedReceipt(privateKey, 7), "Report lost exact posting-version distinction")
        expect(report.describeOwnedReceipt(privateKey, 7), "Report could not describe owned metadata")
        sanitized(report.fields())
        expect(report.fields()["admissionReceiptCaptured"] == "true" && report.fields()["claim"] == "UNOBSERVED" &&
            report.fields()["semanticNovelty"] == "UNKNOWN" && report.fields()["baselineCompleteness"] == "UNKNOWN", "Report promoted metadata/provenance into acceptance")
    },
    "actual helper correlates modeled typed pipeline facts published before exact original receipt" to {
        val p = pipelineProbe { probe, ingress, tokens ->
            probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.DEMAND_ADMITTED))
            probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.READY_OBSERVED))
            probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.SUBMISSION_STARTED))
            probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.SETTLED,
                NotificationDispatchOutcome(NotificationCommitment.COMMITTED, tokens.result)))
            probe.context.runtime.emit(ingress)
        }
        val report = p.run("post-owned")
        expect(report["notification.pipelineCorrelation"] == "EXACT_ORIGINAL_RECEIPT_AND_WORK" &&
            report["notification.claim"] == "COMMITTED_FROM_MATCHING_SETTLEMENT", "Matching immutable pipeline observations were not correlated")
        expect(report["notification.pipelineStages"] == "DEMAND_ADMITTED,READY_OBSERVED,SUBMISSION_STARTED,SETTLED" &&
            report["notification.settlementCount"] == "1", "Original pipeline stage provenance was lost")
        expect(report["notification.foreground"] == "MATCHED_ORIGINAL_CONTEXT_AND_PACKAGE_IN_TYPED_RESULT", "Matching typed original-context result was not qualified")
        expect(report["notification.selectionG"]?.matches(Regex("G[0-9]+")) == true && report["notification.projectedDemandD"]?.matches(Regex("D[0-9]+")) == true,
            "Opaque G/D labels were omitted")
        exactCleanup(p)
    },
    "actual helper ignores pipeline for different receipt even when metadata values match" to {
        val p = pipelineProbe { probe, ingress, _ ->
            val different = NotificationIngressObservation(ingress.event, ingress.scope, ingress.admission)
            val other = PipelineTokens(different, probe.context.packageName)
            probe.context.runtime.emitPipeline(other.event(NotificationPipelineStage.SETTLED,
                NotificationDispatchOutcome(NotificationCommitment.COMMITTED, other.result)))
            probe.context.runtime.emit(ingress)
        }
        val report = p.run("post-owned")
        expect(report["notification.pipelineCorrelation"] == "NO_OBSERVATION_FOR_ORIGINAL_RECEIPT" &&
            report["notification.claim"] == "UNOBSERVED" && report["notification.foreground"] == "UNOBSERVED", "Equal metadata adopted another receipt's work")
        exactCleanup(p)
    },
    "actual helper identity mismatch cannot establish claim or foreground" to {
        for (mismatch in listOf("ready-handle", "carrier-selection")) {
            val p = pipelineProbe { probe, ingress, tokens ->
                val ready = if (mismatch == "ready-handle") tokens.ready.copy(handle =
                    ActivationHandle(privateOpaque + "_WRONG", ActivationCause.NOTIFICATION, "wrong", ActivationGoal.PROJECTED)) else tokens.ready
                val selection = if (mismatch == "carrier-selection") NotificationSelection(ingress, PhoneSessionController()) else tokens.selection
                val carrier = NotificationLaunchCarrier(selection, ready, Any())
                probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.SETTLED,
                    NotificationDispatchOutcome(NotificationCommitment.COMMITTED, tokens.result), ready, carrier))
                probe.context.runtime.emit(ingress)
            }
            val report = p.run("post-owned")
            expect(report["notification.pipelineCorrelation"] == "IDENTITY_MISMATCH" && report["notification.claim"] == "UNOBSERVED" &&
                report["notification.foreground"] == "UNOBSERVED", "Mismatched typed identities were promoted to original work")
            exactCleanup(p)
        }
    },
    "actual helper committed null or fault remains distinct from foreground success" to {
        for (fault in listOf(false, true)) {
            val p = pipelineProbe { probe, ingress, tokens ->
                probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.SETTLED,
                    NotificationDispatchOutcome(NotificationCommitment.COMMITTED, faultStage = if (fault) NotificationFaultStage.BINDER else null)))
                probe.context.runtime.emit(ingress)
            }
            val report = p.run("post-owned")
            expect(report["notification.claim"] == "COMMITTED_FROM_MATCHING_SETTLEMENT" && report["notification.settlement1.resultPresent"] == "false",
                "Committed nullable result was mistaken for no commitment")
            expect(report["notification.settlement1.faultStage"] == if (fault) "BINDER" else "NONE", "Typed fault provenance was lost")
            expect(report["notification.foreground"] == "UNOBSERVED", "Committed null/fault implied foreground success")
            exactCleanup(p)
        }
    },
    "actual helper foreground requires committed matching original context and both package observations" to {
        for (mismatch in listOf("context", "requested", "foreground", "not-committed")) {
            val p = pipelineProbe { probe, ingress, tokens ->
                val result = tokens.result.copy(
                    context = if (mismatch == "context") ProjectionContext("replacement", "replacement") else tokens.context,
                    requestedPackage = if (mismatch == "requested") "example.other" else probe.context.packageName,
                    foregroundPackage = if (mismatch == "foreground") "example.other" else probe.context.packageName)
                probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.SETTLED,
                    NotificationDispatchOutcome(if (mismatch == "not-committed") NotificationCommitment.NOT_COMMITTED else NotificationCommitment.COMMITTED,
                        result, rejection = if (mismatch == "not-committed") NotificationRejection.ACTIVATION_CHANGED else null)))
                probe.context.runtime.emit(ingress)
            }
            val report = p.run("post-owned")
            expect(report["notification.foreground"] == "UNOBSERVED", "Uncommitted/replacement/other-package result established original foreground")
            if (mismatch == "not-committed") expect(report["notification.claim"] == "NOT_COMMITTED_FROM_MATCHING_SETTLEMENT", "Typed rejection disappeared")
            exactCleanup(p)
        }
    },
    "actual helper final snapshot includes original settlement arriving during retained window" to {
        lateinit var original: PipelineTokens
        val p = pipelineProbe { probe, ingress, tokens ->
            original = tokens
            probe.context.runtime.emitPipeline(tokens.event(NotificationPipelineStage.DEMAND_ADMITTED))
            probe.context.runtime.emit(ingress)
        }
        p.instrumentation.onStatus = { status ->
            if (status.getString("notification.stage") == "RECEIPT_OBSERVED_AWAITING_ORIGINAL_PIPELINE") {
                Looper.onMain { p.context.runtime.emitPipeline(original.event(NotificationPipelineStage.SETTLED,
                    NotificationDispatchOutcome(NotificationCommitment.COMMITTED, original.result))) }
            }
        }
        val report = p.run("post-owned")
        expect(report["notification.claim"] == "COMMITTED_FROM_MATCHING_SETTLEMENT" && report["notification.settlementCount"] == "1",
            "Later original settlement was lost after initial receipt description")
        exactCleanup(p)
    },
    "actual helper publishes public owned intent before notification or channel effects" to {
        val p = Probe(); p.simulatedPostingEvent()
        var token: String? = null
        p.instrumentation.onStatus = { status ->
            if (status.getString("notification.stage") == "OWNED_FIXTURE_INTENT") {
                expect(p.context.notifications.created.isEmpty() && p.context.notifications.posts.isEmpty(), "Owned intent was published after effects")
                token = status.getString("notification.fixtureToken")
            }
        }
        val report = p.run("post-owned")
        expect(token?.matches(Regex("[0-9a-f-]{36}")) == true && report["notification.fixtureToken"] == token, "Owned cleanup token was not retained")
        expect(p.context.notifications.created.single() == "eyeprojector_probe_$token" && p.context.notifications.posts.single().first == "eyeprojector_probe_$token",
            "Retained token does not derive exact owned resources")
        exactCleanup(p)
    },
    "actual helper cleanup-owned derives only retained UUID resources without runtime or eligibility reads" to {
        val p = Probe(); val token = "12345678-1234-1234-1234-123456789abc"; val owned = "eyeprojector_probe_$token"
        p.arguments.putString("fixtureToken", token)
        p.context.eligibilityFailure = IllegalStateException(privateFailure); p.context.packageInfoFailure = IllegalStateException(privateFailure)
        p.context.notifications.channels[owned] = android.app.NotificationChannel(owned, "Owned", 3)
        p.context.notifications.channels["unrelated"] = android.app.NotificationChannel("unrelated", "Other", 3)
        p.context.notifications.records.add(StatusBarNotification(p.context.packageName, 1, owned, privateKey, 1))
        val other = StatusBarNotification(p.context.packageName, 1, "UNRELATED_PRIVATE_TAG", privateKey, 2)
        p.context.notifications.records.add(other)
        val report = p.run("cleanup-owned")
        expect(report["notification.outcome"] == "CLEANUP_CALLS_RETURNED" && report["result"] == "DIAGNOSTIC_COMPLETE", "Owned recovery cleanup did not report attempted calls")
        expect(report["notification.originalInvocationSettlement"] == "REQUIRES_ROOT_RECONCILIATION" && report["notification.cleanupResourceSettlement"] == "UNKNOWN",
            "Cleanup manufactured settlement/resource verification")
        expect(p.context.notifications.canceled == listOf(owned to 1) && p.context.notifications.deleted == listOf(owned) &&
            p.context.notifications.records.single() === other && p.context.notifications.channels.keys == setOf("unrelated"), "Cleanup-owned altered unrelated resources")
        expect(p.context.runtimeReads == 0 && p.context.eligibilityReads == 0 && p.context.notifications.activeReads == 0 && p.instrumentation.mainCalls == 0,
            "Cleanup-owned read runtime/canonical state or enumerated notification metadata")
        expect(p.context.notifications.posts.isEmpty() && p.context.notifications.created.isEmpty(), "Cleanup-owned posted or recreated resources")
    },
    "actual helper invalid cleanup token or foreign package has no resource or runtime effects" to {
        for (variant in listOf("missing", "invalid", "foreign")) {
            val p = Probe()
            p.arguments.putString("fixtureToken", if (variant == "invalid") privateFailure else if (variant == "missing") "" else "12345678-1234-1234-1234-123456789abc")
            if (variant == "foreign") p.arguments.putString("postingPackage", "example.externalfixture")
            val report = p.run("cleanup-owned")
            expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.outcome"] == "INVALID_OWNED_CLEANUP_ARGUMENTS", "Invalid cleanup identity was accepted")
            expect(p.context.runtimeReads == 0 && p.context.eligibilityReads == 0 && p.context.notifications.canceled.isEmpty() && p.context.notifications.deleted.isEmpty(),
                "Invalid cleanup performed reads or resource effects")
        }
    },
    "actual helper cleanup-owned still attempts independent channel release after cancel fault" to {
        val p = Probe(); val token = "12345678-1234-1234-1234-123456789abc"
        p.arguments.putString("fixtureToken", token); p.context.notifications.cancelFailure = SecurityException(privateFailure)
        val report = p.run("cleanup-owned")
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.outcome"] == "CLEANUP_FAULT", "Owned recovery failure was hidden")
        expect(report["notification.ownedNotificationCleanup"] == "FAULT" && report["notification.ownedChannelCleanup"] == "DELETE_RETURNED" &&
            p.context.notifications.deleted == listOf("eyeprojector_probe_$token"), "Cancel failure prevented independent channel cleanup")
        expect(p.context.runtimeReads == 0 && p.context.eligibilityReads == 0, "Recovery failure queried unrelated app state")
    },
)

/** Report facts only: these do not exercise or prove a production claim/queue. */
private class ReportChain {
    val report = NotificationProbeReport("host_report_136", "example.report")
    val original = Any()
    val receipt = NotificationProbeReport.Receipt(Any(), Any(), Any(), original,
        "example.report", privateKey, 136L, "POSTED_CALLBACK", "UPDATED_OR_REPOSTED", "READY", true)
    val selection = Any()
    val demand = Any()
    private val ready = Any()
    private val arm = Any()
    private val activation = Any()
    private val attempt = Any()
    private val context = Any()
    fun settled(carrier: Any = Any()) = NotificationProbeReport.Pipeline(original, selection, demand,
        "SETTLED", "COMMITTED", "PROJECTED", arm, ready, activation, attempt, context, carrier,
        true, "COMMITTED", null, null, true, "STARTED", true, true, true)
    fun describeCommitted() {
        report.put("setupFact", "PRESERVED")
        report.receipt(receipt)
        report.pipeline(settled())
        report.pipeline(settled())
        expect(report.describeOwnedReceipt(privateKey, 136L), "N1 fixture was not selected")
        val fields = report.fields()
        expect(fields["claim"] == "COMMITTED_FROM_MATCHING_SETTLEMENT" &&
            fields["foreground"] == "MATCHED_ORIGINAL_CONTEXT_AND_PACKAGE_IN_TYPED_RESULT" &&
            fields["settlementCount"] == "2" && "settlement2.commitment" in fields,
            "N1 fixture never established the prior stronger diagnostic facts")
    }
}

private fun expectNoChainProof(fields: Map<String, String>) {
    for (key in listOf("selectionG", "projectedDemandD", "demandGoal", "arm", "ready", "activationH", "attemptA",
        "contextC", "carrier", "observedPermitState", "claim", "settlement", "foreground")) {
        expect(fields[key] == "UNOBSERVED", "Stale original chain field retained: $key")
    }
    expect(fields["pipelineStages"] == "NONE" && fields["settlementCount"] == "0", "Stale chain stage/count retained")
    expect(fields.keys.none { it.matches(Regex("settlement[0-9]+\\..*")) }, "Stale dynamic settlementN fields retained")
    expect(fields["runId"] == "host_report_136" && fields["postingPackage"] == "example.report" &&
        fields["setupFact"] == "PRESERVED" && fields["semanticNovelty"] == "UNKNOWN" &&
        fields["baselineCompleteness"] == "UNKNOWN", "Recomputation removed run/setup/uncertainty facts")
    sanitized(fields)
}

private fun correction136Scenarios(): List<Pair<String, () -> Unit>> = listOf(
    "C133-01 mismatchN1 recomputes all chain proof after previously committed matching N1" to {
        for (mismatch in listOf("selection", "demand", "identities")) {
            val chain = ReportChain(); chain.describeCommitted()
            val before = chain.report.fields()
            chain.report.pipeline(chain.settled().copy(
                selection = if (mismatch == "selection") Any() else chain.selection,
                demand = if (mismatch == "demand") Any() else chain.demand,
                identitiesMatch = mismatch != "identities"))
            chain.report.refreshPipeline()
            val after = chain.report.fields()
            expect(after["pipelineCorrelation"] == "IDENTITY_MISMATCH", "N1 mismatch was not diagnosed")
            expectNoChainProof(after)
            for (key in listOf("receiptLabel", "ingressLabel", "membershipLabel", "epochLabel"))
                expect(after[key] == before[key], "Same original N1 identity label changed: $key")
        }
    },
    "C133-01 newN2empty removes every stale N1 settlementN and stronger chain fact" to {
        val chain = ReportChain(); chain.describeCommitted()
        val before = chain.report.fields()
        val second = chain.receipt.copy(ingress = Any(), membership = Any(), epoch = Any(), original = Any(), postTime = 137L)
        chain.report.receipt(second)
        expect(chain.report.describeOwnedReceipt(privateKey, 137L), "N2 fixture was not selected")
        val after = chain.report.fields()
        expect(after["pipelineCorrelation"] == "NO_OBSERVATION_FOR_ORIGINAL_RECEIPT" &&
            after["receiptLabel"] != before["receiptLabel"], "N2 without pipeline was not distinguished from N1")
        expectNoChainProof(after)
        expect(chain.report.describeOwnedReceipt(privateKey, 136L), "Original N1 could not be selected again")
        val restored = chain.report.fields()
        expect(restored["receiptLabel"] == before["receiptLabel"] && restored["selectionG"] == before["selectionG"] &&
            restored["projectedDemandD"] == before["projectedDemandD"] && restored["settlementCount"] == "2",
            "Recomputation discarded the invocation-local original identity registry or journal")
    },
    "C133-02 report-refresh main-boundary fault cannot skip exact original pipeline close" to {
        val p = Probe(); p.simulatedPostingEvent(); SystemClock.stepMillis = 1L
        var faultArmed = false
        p.instrumentation.onStatus = { status ->
            if (status.getString("notification.stage") == "RECEIPT_OBSERVED_AWAITING_ORIGINAL_PIPELINE") {
                // The retained window makes no more main calls; the next is final refresh.
                p.instrumentation.failNextMain = true
                faultArmed = true
                SystemClock.advance(2000L)
            }
        }
        val report = p.run("post-owned")
        expect(faultArmed && p.instrumentation.failedMainCalls == 1 && p.instrumentation.unhandledMainFailures == 0,
            "Fixture did not inject exactly one pre-Runnable report-refresh boundary fault")
        // Assert lifetime before new reporting fields: old compound cleanup leaves pipeline open.
        p.observersClosed()
        expect(report["result"] == "DIAGNOSTIC_ERROR" && report["notification.reportRefresh"] == "FAULT_UNKNOWN" &&
            report["notification.cleanup"] == "FAULT_OR_INCOMPLETE", "Refresh boundary fault was hidden")
        expect(report["notification.primaryOutcome"] == "OWNED_SYSTEM_RECEIPT_OBSERVED" &&
            report["notification.primaryDiagnosticResult"] == "DIAGNOSTIC_COMPLETE", "Cleanup fault replaced original diagnostic provenance")
        for (key in listOf("pipelineObserverCleanup", "eventObserverCleanup", "statusObserverCleanup"))
            expect(report["notification.$key"] == "CLOSE_RETURNED", "Independent exact observer close outcome missing: $key")
        expect(report["notification.observerCleanup"] == "CLOSE_RETURNED" &&
            report["notification.cleanupResourceSettlement"] == "UNKNOWN", "Refresh fault manufactured observer failure or resource settlement")
        exactCleanup(p)
    },
)

fun main(args: Array<String>) {
    var failures = 0
    check(args.isEmpty() || args.contentEquals(arrayOf("--correction136-only"))) { "Unknown test selection" }
    val tests = if (args.isEmpty()) scenarios() + correction136Scenarios() else correction136Scenarios()
    for ((name, run) in tests) {
        Build.VERSION.SDK_INT = 35; SystemClock.reset()
        try { run(); println("PASS $name") }
        catch (failure: Throwable) { failures++; println("FAIL $name family=${failure.javaClass.simpleName} line=${failure.stackTrace.firstOrNull()?.lineNumber}") }
        finally { Build.VERSION.SDK_INT = 35; SystemClock.reset() }
    }
    println("RESULT notification-probe actual-helper host scenarios=${tests.size} failures=$failures Android/system/device-proof=false")
    exitProcess(if (failures == 0) 0 else 1)
}
