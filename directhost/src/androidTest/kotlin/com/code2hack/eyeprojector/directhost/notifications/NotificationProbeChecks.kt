package com.code2hack.eyeprojector.directhost.notifications

import android.Manifest
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.code2hack.eyeprojector.directhost.apps.ChosenApps
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual-Android diagnostic, never a replacement listener/activation/launch route. The only
 * event entry is the production runtime's observer of real system NLS callbacks. This driver
 * makes no canonical choice/preference writes and cannot grant either notification permission.
 */
internal object NotificationProbeChecks {
    fun run(instrumentation: Instrumentation, context: Context, arguments: Bundle, output: Bundle) {
        val runId = arguments.getString("runId").orEmpty()
        val packageName = arguments.getString("postingPackage", context.packageName).orEmpty()
        val mode = arguments.getString("notificationMode", "status").orEmpty()
        // Invalid caller text is never echoed through the outer runner's arbitrary stack trace.
        if (!runId.matches(Regex("[A-Za-z0-9_-]{1,48}")) ||
            !packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) ||
            mode !in setOf("status", "observe-event", "post-owned", "cleanup-owned")) {
            output.putString("result", "DIAGNOSTIC_ERROR")
            output.putString("notification.outcome", "INVALID_ARGUMENTS")
            return
        }
        val report = NotificationProbeReport(runId, packageName)
        report.put("mode", mode)
        report.put("targetPackage", context.packageName)
        report.put("apiLevel", Build.VERSION.SDK_INT.toString())
        report.put("processScope", "CURRENT_INSTRUMENTED_APP_PROCESS")
        report.put("historyAfterProcessRestart", "NOT_RECONSTRUCTED")
        report.put("instrumentationFinish", "EXITS_INSTRUMENTED_APP_PROCESS")
        if (mode == "cleanup-owned") {
            cleanupOwned(context, arguments, output, report)
            return
        }
        val timeout = arguments.getString("waitMs", "20000")?.toLongOrNull()
        if (timeout == null || timeout !in 1000L..60000L) {
            report.put("outcome", "INVALID_ARGUMENTS")
            write(output, report, false)
            return
        }
        val signal = Semaphore(0)
        var runtime: PhoneNotificationRuntime? = null
        var statusRegistration: Closeable? = null
        var eventRegistration: Closeable? = null
        var pipelineRegistration: Closeable? = null
        var manager: NotificationManager? = null
        val invocation = UUID.randomUUID().toString()
        val channelId = "eyeprojector_probe_$invocation"
        val tag = "eyeprojector_probe_$invocation"
        val notificationId = 1 // Unique tag makes this exact pair owned by this invocation.
        var channelOwned = false
        var postAttempted = false
        var diagnosticComplete = true

        fun <T> main(work: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            // Keep a diagnostic API fault from escaping on Android's main thread. In particular,
            // runOnMainSync itself does not promise to transport a thrown callback to this thread.
            instrumentation.runOnMainSync {
                try { value.set(work()) } catch (error: Throwable) { failure.set(error) }
            }
            failure.get()?.let { throw it }
            return value.get()
        }
        fun status(): NotificationAccessStatus = main { runtime!!.status() }
        fun eligibility(): Pair<Boolean, Boolean> = main {
            val chosen = ChosenApps.open(context).read().any { it.packageName == packageName }
            val enabled = AppNotificationPreferences.open(context).isEnabled(packageName)
            report.put("chosenAtDiagnosticRead", chosen.toString())
            report.put("onAtDiagnosticRead", enabled.toString())
            report.put("eligibilityAtDiagnosticRead", if (!chosen) "NOT_CHOSEN" else if (!enabled) "OFF" else "CHOSEN_ON")
            chosen to enabled
        }
        fun describeStatus(value: NotificationAccessStatus) {
            report.put("listenerApproval", value.approval.name)
            report.put("listenerConnected", value.connected.toString())
            report.put("baselineObservation", value.baseline.name)
            report.put("chosenReadAvailable", value.chosenAvailable.toString())
            report.put("runtimeLaunchFailed", value.launchFailed.toString())
        }

        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            main {
                runtime = PhoneNotificationRuntime.get(context)
                statusRegistration = runtime!!.observeStatus { signal.release() }
                eventRegistration = runtime!!.observeEvents { observation ->
                    val event = observation.event
                    // Retain only requested-package metadata, never payload/extras/raw-key output.
                    if (event.record.packageName == packageName) {
                        report.receipt(NotificationProbeReport.Receipt(
                            observation.scope.connection, observation.scope.membership, event.epoch, observation,
                            event.record.packageName, event.record.key, event.record.postTime,
                            event.origin.name, event.kind.name, event.baseline.name, observation.admission != null,
                            observation.admission, try { observation.diagnostic } catch (lookupFailure: Throwable) {
                                recordSafely { report.uncorrelatedHelperFault(notificationDiagnosticFault(lookupFailure)) }
                                null
                            },
                        ))
                        signal.release()
                    }
                }
                pipelineRegistration = runtime!!.observePipeline { event ->
                    // Entry itself precedes the first ingress getter. This run-scoped fact does
                    // not yet identify an original receipt and must not borrow the latest one.
                    recordSafely { report.helperCallbackEntry() }
                    var box: NotificationObservationTrace? = null
                    var appendInvoked = false
                    var matchingPackage = false
                    try {
                        val original = event.ingress
                        // A diagnostic lookup/recording failure must not replace a detailed-row
                        // fault or prevent an otherwise normal observer from constructing its row.
                        try { box = original.diagnostic?.takeIf { it.original === original } }
                        catch (recordingFailure: Throwable) {
                            recordSafely { report.uncorrelatedHelperFault(notificationDiagnosticFault(recordingFailure)) }
                        }
                        recordSafely { box?.let { it.helperEntered++ } }
                        if (original.event.record.packageName == packageName) {
                            matchingPackage = true
                            val ready = event.ready
                            val carrier = event.carrier
                            val outcome = event.outcome
                            val result = outcome?.result
                            val row = NotificationProbeReport.Pipeline(
                                original, event.selection, event.demand, event.stage.name, event.permitState.name,
                                event.demand.goal.name, event.arm, ready, ready?.binding?.activationId, ready?.attempt,
                                ready?.target, carrier,
                                (ready == null || ready.handle === event.demand) && (carrier == null ||
                                    (carrier.selection === event.selection && carrier.selection.observation === original && carrier.ready === ready)),
                                outcome?.commitment?.name, outcome?.rejection?.name, outcome?.faultStage?.name,
                                outcome?.let { result != null }, result?.status?.name,
                                result?.let { ready != null && it.context == ready.target },
                                result?.let { it.requestedPackage == packageName }, result?.let { it.foregroundPackage == packageName },
                            )
                            appendInvoked = true
                            report.pipeline(row)
                            recordSafely { box?.let { it.helperAppended++ } }
                        }
                    } catch (primary: Throwable) {
                        recordSafely {
                            val family = notificationDiagnosticFault(primary)
                            val originalBox = box
                            if (originalBox == null) report.uncorrelatedHelperFault(family)
                            else {
                                originalBox.helperFaults++
                                originalBox.helperFault = family
                                originalBox.helperFaultPoint = if (appendInvoked) NotificationHelperFaultPoint.APPEND_UNCONFIRMED
                                    else NotificationHelperFaultPoint.BEFORE_APPEND
                            }
                        }
                        throw primary // Preserve the exact original Throwable for the producer's existing catch.
                    }
                    if (matchingPackage) signal.release()
                }
                report.put("pipelineObserver", "REGISTERED_CURRENT_PROCESS_NO_REPLAY")
                runtime!!.refresh()
            }
            describeStatus(status())
            val initialChoice = eligibility()
            val notifications = context.getSystemService(NotificationManager::class.java)
                ?: error("Notification manager unavailable")
            manager = notifications
            val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS)
            val granted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            report.put("targetPostingPermissionDeclared", declared.toString())
            report.put("targetPostingPermission", if (Build.VERSION.SDK_INT < 33) "NOT_REQUIRED_API32" else if (granted) "GRANTED" else "DENIED")
            report.put("targetNotificationsEnabled", notifications.areNotificationsEnabled().toString())
            report.put("postingPermissionScope", if (packageName == context.packageName) "ACTUAL_POSTING_APP" else "TARGET_APP_ONLY_EXTERNAL_APP_UNOBSERVED")
            report.put("permissionMutated", "false")
            report.put("canonicalChoicesMutated", "false")

            if (mode == "status") {
                report.put("outcome", "STATUS_ONLY")
                return
            }
            if (!initialChoice.first) {
                report.put("outcome", "CHOSEN_SETUP_REQUIRED")
                return
            }
            if (mode == "post-owned" && packageName != context.packageName) {
                report.put("outcome", "POSTING_PACKAGE_IS_NOT_TARGET_APP")
                return
            }
            if (mode == "post-owned" && (!granted || (Build.VERSION.SDK_INT >= 33 && !declared) || !notifications.areNotificationsEnabled())) {
                report.put("outcome", "ORDINARY_POST_PERMISSION_SETUP_REQUIRED")
                return
            }

            // One finite diagnostic observation window; no production expiry, rearm or retry policy.
            val deadline = SystemClock.elapsedRealtime() + timeout
            fun await(condition: () -> Boolean): Boolean {
                while (true) {
                    if (condition()) return true
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) return false
                    signal.tryAcquire(minOf(remaining, 100L), TimeUnit.MILLISECONDS)
                }
            }
            fun retainObservationWindow() {
                // Receipt alone must not immediately cancel accepted asynchronous launch work.
                // Keep the ordinary fixture/session alive for the rest of the SAME finite test
                // window while Root may separately observe Receiver/wake/foreground. This is not
                // an assertion that the unobservable pipeline has completed or failed.
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("notification.runId", runId)
                    putString("notification.stage", "RECEIPT_OBSERVED_AWAITING_ORIGINAL_PIPELINE")
                    putString("notification.remainingWindowMs", maxOf(0L, deadline - SystemClock.elapsedRealtime()).toString())
                })
                while (true) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    signal.tryAcquire(remaining, TimeUnit.MILLISECONDS)
                }
                report.put("postReceiptObservation", "REMAINDER_OF_DIAGNOSTIC_WINDOW")
            }
            val ready = await {
                val current = status()
                describeStatus(current)
                current.approval == NotificationAccessApproval.GRANTED && current.connected &&
                    current.chosenAvailable && current.baseline == NotificationListenerBaseline.OBSERVED
            }
            if (!ready) {
                report.put("outcome", "LISTENER_BASELINE_NOT_READY_WITHIN_WINDOW")
                return
            }
            if (!eligibility().first) {
                report.put("outcome", "CHOSEN_REMOVED_BEFORE_EVENT")
                return
            }

            if (mode == "observe-event") {
                main { report.beginEventWindow() }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("notification.runId", runId)
                    putString("notification.stage", "AWAITING_ORDINARY_EXTERNAL_APP_EVENT")
                    putString("notification.postingPackage", packageName)
                })
                val observed = await { main { report.hasPostedReceipt() } }
                if (observed) main { report.describePackageReceipt() }
                report.put("outcome", if (observed) "PACKAGE_CALLBACK_OBSERVED" else "NO_CALLBACK_WITHIN_WINDOW")
                if (observed) retainObservationWindow()
                return
            }

            // No title/body/image/contentIntent. Framework payload objects are never extracted.
            report.put("fixtureToken", invocation)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("notification.runId", runId)
                putString("notification.stage", "OWNED_FIXTURE_INTENT")
                putString("notification.fixtureToken", invocation) // Public synthetic identity, not a platform key.
                putString("notification.postingPackage", context.packageName)
            })
            check(notifications.getNotificationChannel(channelId) == null)
            channelOwned = true // Own exact new UUID even if create throws after a partial effect.
            notifications.createNotificationChannel(NotificationChannel(channelId, "EyeProjector notification probe", NotificationManager.IMPORTANCE_LOW))
            val channel = notifications.getNotificationChannel(channelId)
            report.put("ownedChannel", if (channel == null) "UNOBSERVED" else if (channel.importance == NotificationManager.IMPORTANCE_NONE) "BLOCKED" else "OBSERVED_ENABLED")
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                report.put("outcome", "OWNED_CHANNEL_UNAVAILABLE")
                return
            }
            val notification = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_more).build()
            postAttempted = true
            report.put("postCall", "ATTEMPTED")
            notifications.notify(tag, notificationId, notification)
            report.put("postCall", "RETURNED")

            var ownedKey: String? = null
            var ownedPostTime: Long? = null
            val observed = await {
                if (ownedKey == null) {
                    // Public app-owned API: extract metadata only AFTER exact owned tag/id match.
                    val owned = notifications.activeNotifications.firstOrNull {
                        it.id == notificationId && it.tag == tag && it.packageName == context.packageName
                    }
                    if (owned != null) { ownedKey = owned.key; ownedPostTime = owned.postTime }
                }
                val key = ownedKey
                val version = ownedPostTime
                key != null && version != null && main { report.hasOwnedReceipt(key, version) }
            }
            report.put("ownedPlatformRecord", if (ownedKey == null) "UNOBSERVED" else "OBSERVED")
            if (observed) main { report.describeOwnedReceipt(ownedKey!!, ownedPostTime!!) }
            report.put("outcome", if (observed) "OWNED_SYSTEM_RECEIPT_OBSERVED" else "OWNED_RECEIPT_NOT_CORRELATED_WITHIN_WINDOW")
            if (observed) retainObservationWindow()
        } catch (failure: Throwable) {
            diagnosticComplete = false
            report.put("outcome", "DIAGNOSTIC_FAULT")
            report.put("faultFamily", when (failure) {
                is SecurityException -> "SECURITY"
                is IllegalArgumentException -> "ARGUMENT"
                is IllegalStateException -> "STATE"
                is InterruptedException -> "INTERRUPTED"
                else -> "OTHER"
            })
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            // Detach observation before cancellation can produce a removal callback.
            report.put("primaryOutcome", report.fields()["outcome"] ?: "UNKNOWN")
            report.put("primaryDiagnosticResult", if (diagnosticComplete) "DIAGNOSTIC_COMPLETE" else "DIAGNOSTIC_ERROR")
            var cleanupFailed = false
            var observerCleanupFailed = false
            try { main { report.refreshPipeline() }; report.put("reportRefresh", "RETURNED") }
            catch (_: Throwable) { cleanupFailed = true; report.put("reportRefresh", "FAULT_UNKNOWN") }
            try { main { pipelineRegistration?.close() }; report.put("pipelineObserverCleanup", if (pipelineRegistration == null) "NOT_REGISTERED" else "CLOSE_RETURNED") }
            catch (_: Throwable) { cleanupFailed = true; observerCleanupFailed = true; report.put("pipelineObserverCleanup", "FAULT_UNKNOWN") }
            try { main { eventRegistration?.close() }; report.put("eventObserverCleanup", if (eventRegistration == null) "NOT_REGISTERED" else "CLOSE_RETURNED") }
            catch (_: Throwable) { cleanupFailed = true; observerCleanupFailed = true; report.put("eventObserverCleanup", "FAULT_UNKNOWN") }
            try { main { statusRegistration?.close() }; report.put("statusObserverCleanup", if (statusRegistration == null) "NOT_REGISTERED" else "CLOSE_RETURNED") }
            catch (_: Throwable) { cleanupFailed = true; observerCleanupFailed = true; report.put("statusObserverCleanup", "FAULT_UNKNOWN") }
            report.put("cleanupResourceSettlement", "UNKNOWN")
            if (!postAttempted) report.put("ownedNotificationCleanup", "NOT_ATTEMPTED_NO_OWNED_POST")
            if (!channelOwned) report.put("ownedChannelCleanup", "NOT_ATTEMPTED_NO_OWNED_CHANNEL")
            if (postAttempted) try {
                manager?.cancel(tag, notificationId)
                report.put("ownedNotificationCleanup", "CANCEL_RETURNED")
            } catch (_: Throwable) { cleanupFailed = true; report.put("ownedNotificationCleanup", "FAULT") }
            if (channelOwned) try {
                manager?.deleteNotificationChannel(channelId)
                report.put("ownedChannelCleanup", "DELETE_RETURNED")
            } catch (_: Throwable) { cleanupFailed = true; report.put("ownedChannelCleanup", "FAULT") }
            report.put("observerCleanup", if (observerCleanupFailed) "FAULT_OR_INCOMPLETE" else "CLOSE_RETURNED")
            report.put("cleanup", if (cleanupFailed) "FAULT_OR_INCOMPLETE" else "CALLS_RETURNED")
            if (cleanupFailed) diagnosticComplete = false
            write(output, report, diagnosticComplete)
        }
    }

    private inline fun recordSafely(work: () -> Unit) { try { work() } catch (_: Throwable) { } }

    private fun cleanupOwned(context: Context, arguments: Bundle, output: Bundle, report: NotificationProbeReport) {
        val token = arguments.getString("fixtureToken").orEmpty()
        if (report.packageName != context.packageName ||
            !token.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            report.put("outcome", "INVALID_OWNED_CLEANUP_ARGUMENTS")
            write(output, report, false)
            return
        }
        // Root supplies only the retained OWNED_FIXTURE_INTENT token after reconciling that
        // original invocation. No arbitrary key/channel/tag/id input, no broad cleanup/retry.
        report.put("fixtureToken", token)
        report.put("originalInvocationSettlement", "REQUIRES_ROOT_RECONCILIATION")
        report.put("cleanupResourceSettlement", "UNKNOWN")
        var complete = true
        try {
            val notifications = context.getSystemService(NotificationManager::class.java)
                ?: error("Notification manager unavailable")
            try { notifications.cancel("eyeprojector_probe_$token", 1); report.put("ownedNotificationCleanup", "CANCEL_RETURNED") }
            catch (_: Throwable) { complete = false; report.put("ownedNotificationCleanup", "FAULT") }
            try { notifications.deleteNotificationChannel("eyeprojector_probe_$token"); report.put("ownedChannelCleanup", "DELETE_RETURNED") }
            catch (_: Throwable) { complete = false; report.put("ownedChannelCleanup", "FAULT") }
        } catch (_: Throwable) { complete = false }
        report.put("outcome", if (complete) "CLEANUP_CALLS_RETURNED" else "CLEANUP_FAULT")
        write(output, report, complete)
    }

    private fun write(output: Bundle, report: NotificationProbeReport, complete: Boolean) {
        output.putString("result", if (complete) "DIAGNOSTIC_COMPLETE" else "DIAGNOSTIC_ERROR")
        report.fields().forEach { (key, value) -> output.putString("notification.$key", value) }
    }
}
