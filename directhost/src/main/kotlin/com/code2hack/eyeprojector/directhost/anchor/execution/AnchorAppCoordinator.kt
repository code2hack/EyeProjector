package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.AnchorIntent
import com.code2hack.eyeprojector.protocol.AnchorIntentKind
import com.code2hack.eyeprojector.protocol.AnchorMessage
import java.io.Closeable

/** Captured original shell operation, never a lookup of the latest projection or Binder binding. */
internal interface AnchorOriginalRoute {
    val transactionId: Long get() = 0
    val identity: Any get() = this
    val intent: AnchorIntent
    fun source(): ManagedUiSource?
    fun reconcile(setting: AnchorSettingContext): Boolean
    fun heldSetting(): AnchorSettingContext? = null
    fun releaseHeld(expected: AnchorSettingContext?, settingCurrent: Boolean): AnchorReleaseResult =
        if (expected == null) AnchorReleaseResult.NOT_HELD else AnchorReleaseResult.RETIRED
    fun consumeHeldControl(): AnchorControlDisposition = AnchorControlDisposition.NORMAL
    fun click(setting: AnchorSettingContext, target: ResolvedAnchorTarget): AnchorExecutionStatus
    fun hold(setting: AnchorSettingContext, target: ResolvedAnchorTarget): AnchorExecutionStatus = AnchorExecutionStatus.HOLD_MODE
    fun retire()
    fun feedback(status: AnchorExecutionStatus)
    fun send(message: AnchorMessage) = Unit
}

/** App-process work item also offered to the registration consumer; source is never persisted. */
internal class CapturedAnchorRequest(val original: AnchorOriginalRoute, val source: ManagedUiSource,
    val snapshot: UiTargetSnapshot)

/**
 * App-process preferences and public accessibility are composed here. All work and setting
 * observations use one FIFO lane; submit MUST enqueue, including when invoked by that lane.
 * No store, input or projection lock is held during a route call or traversal. A delayed preference
 * callback reconciles the latest snapshot, never its older event value. A fresh activation performs
 * the same reconciliation before use, even if that callback has not yet been delivered.
 */
internal class AnchorAppCoordinator(
    private val store: AppAnchorStore,
    private val reader: () -> UiTargetReader?,
    private val uptime: () -> Long,
    private val submit: (() -> Unit) -> Unit,
) : Closeable {
    var registration: ((CapturedAnchorRequest) -> Unit)? = null
    var registrationMessage: ((AnchorMessage, AnchorOriginalRoute) -> Unit)? = null
    var registrationLost: ((AnchorOriginalRoute) -> Unit)? = null
    private class Subscription(var route: AnchorOriginalRoute, val context: ProjectionContext, val displayId: Int,
        val packageName: String, val observer: Closeable)
    private val subscriptions = mutableListOf<Subscription>() // FIFO lane only.
    private var registrationRequest: CapturedAnchorRequest? = null
    @Volatile private var closed = false
    @Volatile var lastFailure: Exception? = null
        private set
    @Volatile var lastRegistrationDiagnostic: AnchorRegistrationDiagnostic? = null
        private set
    var registrationDiagnostic: ((AnchorRegistrationDiagnostic) -> Unit)? = null

    /** Called on this FIFO with captured values only; observer failure cannot change input results. */
    fun reportRegistration(original: AnchorOriginalRoute, stage: AnchorRegistrationDiagnostic.Stage,
        source: ManagedUiSource? = null, snapshot: UiTargetSnapshot? = null,
        inspection: com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationInspection? = null,
        detail: String? = null, failure: Exception? = null) {
        if (original.intent.kind != AnchorIntentKind.REGISTER) return
        val copied = snapshot?.copy(windows = snapshot.windows.map { window ->
            window.copy(targets = window.targets.map { it.copy(actionIds = it.actionIds.toList()) }) })
        val observation = AnchorRegistrationDiagnostic(original.transactionId, original.intent, stage,
            source, copied, inspection, detail, failure)
        lastRegistrationDiagnostic = observation
        try { registrationDiagnostic?.invoke(observation) } catch (_: Exception) { /* Diagnostic only. */ }
    }

    fun accept(original: AnchorOriginalRoute) = submit {
        if (closed) return@submit
        protect(original) { execute(original) }
    }

    fun acceptRegistration(message: AnchorMessage, original: AnchorOriginalRoute) = submit {
        if (!closed) protect(original) {
            val pending = registrationRequest
            if (pending != null && pending.original.identity == original.identity &&
                pending.original.transactionId == message.transactionId && pending.original.intent == message.intent) {
                if (message is AnchorMessage.Choice) registrationRequest = null
                registrationMessage?.invoke(message, original)
            }
        }
    }

    private fun protect(original: AnchorOriginalRoute, action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            lastFailure = error
            val pending = registrationRequest?.takeIf { it.original.identity == original.identity }
            reportRegistration(original, AnchorRegistrationDiagnostic.Stage.EXCEPTION,
                pending?.source, pending?.snapshot, failure = error)
            if (registrationRequest?.original?.identity == original.identity) registrationRequest = null
            try { registrationLost?.invoke(original) }
            catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            if (original.intent.kind == AnchorIntentKind.REGISTER) {
                try { original.send(AnchorMessage.Result(original.transactionId, original.intent,
                    com.code2hack.eyeprojector.protocol.AnchorRegistrationResult.UNAVAILABLE)) }
                catch (feedback: Exception) { error.addSuppressed(feedback) }
            }
            try { original.feedback(AnchorExecutionStatus.UNAVAILABLE) }
            catch (feedback: Exception) { error.addSuppressed(feedback) }
        }
    }

    private fun execute(original: AnchorOriginalRoute) {
        if (original.intent.kind == AnchorIntentKind.REGISTER) {
            val disposition = original.consumeHeldControl()
            if (disposition != AnchorControlDisposition.NORMAL) {
                reportRegistration(original, AnchorRegistrationDiagnostic.Stage.CONTROL_DISPOSITION, detail = disposition.name)
                return
            }
        }
        if (original.intent.kind == AnchorIntentKind.ACTIVATE) {
            // A second short completes the original contact, independent of later UI/preferences.
            val held = original.heldSetting()
            when (original.releaseHeld(held, true)) {
                AnchorReleaseResult.RELEASE_SUBMITTED -> return original.feedback(AnchorExecutionStatus.RELEASE_SUBMITTED)
                AnchorReleaseResult.RETIRED -> return original.feedback(AnchorExecutionStatus.RETIRED)
                AnchorReleaseResult.INPUT_REJECTED -> return original.feedback(AnchorExecutionStatus.INPUT_REJECTED)
                AnchorReleaseResult.NOT_HELD -> Unit
            }
        }
        val source = original.source() ?: run {
            reportRegistration(original, AnchorRegistrationDiagnostic.Stage.SOURCE_RETIRED)
            return original.feedback(AnchorExecutionStatus.RETIRED)
        }
        val packageName = source.foregroundPackage ?: run {
            reportRegistration(original, AnchorRegistrationDiagnostic.Stage.FOREGROUND_UNAVAILABLE, source)
            return original.feedback(AnchorExecutionStatus.UNAVAILABLE)
        }
        // Keep one route per original Session, as supplied by the Binder adapter's stable route key.
        watch(original, source.context, source.displayId, packageName)
        val setting = store.snapshot(packageName)
        if (!original.reconcile(setting.context)) {
            reportRegistration(original, AnchorRegistrationDiagnostic.Stage.SETTING_RETIRED, source)
            return original.feedback(AnchorExecutionStatus.RETIRED)
        }
        val provider = UiTargetSnapshotProvider(ManagedUiSourceProvider { expected ->
            original.source()?.takeIf { it.context == expected }
        }, reader, uptime)
        if (original.intent.kind == AnchorIntentKind.REGISTER) {
            val beforeRead = original.consumeHeldControl()
            if (beforeRead != AnchorControlDisposition.NORMAL) {
                reportRegistration(original, AnchorRegistrationDiagnostic.Stage.CONTROL_DISPOSITION, source, detail = beforeRead.name)
                return
            }
            val snapshot = provider.read(source.context, packageName)
            val retired = when {
                closed -> "CLOSED"
                original.source() != source -> "SOURCE_CHANGED"
                else -> null
            }
            if (retired != null) {
                reportRegistration(original, AnchorRegistrationDiagnostic.Stage.SNAPSHOT_RETIRED, source, snapshot, detail = retired)
                return original.feedback(AnchorExecutionStatus.RETIRED)
            }
            val handler = registration
            if (handler == null) {
                reportRegistration(original, AnchorRegistrationDiagnostic.Stage.PROVIDER_UNAVAILABLE, source, snapshot)
                original.feedback(AnchorExecutionStatus.UNAVAILABLE)
            }
            else {
                val beforeCapture = original.consumeHeldControl()
                if (beforeCapture != AnchorControlDisposition.NORMAL) {
                    reportRegistration(original, AnchorRegistrationDiagnostic.Stage.CONTROL_DISPOSITION, source, snapshot, detail = beforeCapture.name)
                    return
                }
                cancelRegistration(null)
                val request = CapturedAnchorRequest(original, source, snapshot)
                registrationRequest = request
                handler(request)
            }
            return
        }
        val anchor = setting.anchor ?: return original.feedback(AnchorExecutionStatus.NO_ANCHOR)
        val snapshot = provider.read(source.context, packageName)
        val resolved = AnchorTargetResolver.resolve(anchor.descriptor, snapshot)
        if (resolved !is AnchorTargetResolution.Unique) return original.feedback(AnchorExecutionStatus.UNAVAILABLE)
        // Before first submission, use the selected setting and source from this read.
        // Observer delivery updates future reads without canceling a submitted original contact.
        if (closed || !store.isCurrent(setting.context) || original.source() != resolved.current.source)
            return original.feedback(AnchorExecutionStatus.RETIRED)
        original.feedback(if (anchor.mode == AnchorMode.HOLD) original.hold(setting.context, resolved.current)
            else original.click(setting.context, resolved.current))
    }

    private fun watch(route: AnchorOriginalRoute, context: ProjectionContext, displayId: Int, packageName: String) {
        // A Binder adapter can share a stable Session route while retaining per-press admission.
        subscriptions.firstOrNull { it.context == context && it.packageName == packageName }?.let {
            // Observe the current original operation, never a completed older chooser/contact.
            it.route = route
            return
        }
        lateinit var subscription: Subscription
        val observer = store.observe(packageName) {
            submit {
                if (!closed) protect(subscription.route) {
                    subscription.route.reconcile(store.snapshot(packageName).context)
                }
            }
        }
        subscription = Subscription(route, context, displayId, packageName, observer)
        subscriptions.add(subscription)
    }

    /** Presentation/accessibility observations do not own semantic Anchor cancellation. */
    fun windowChanged(displayId: Int, windowId: Int, currentChanged: Boolean) = Unit

    /** An app Binder replacement loses its original callbacks; it never sends input CANCEL. */
    fun contextRetired(displayId: Int? = null) = submit {
        val pending = registrationRequest
        if (pending != null && (displayId == null || pending.source.displayId == displayId)) {
            registrationRequest = null
            registrationLost?.invoke(pending.original)
        }
        val removed = subscriptions.filter { displayId == null || it.displayId == displayId }
        removed.forEach { it.observer.close() }
        subscriptions.removeAll(removed.toSet())
    }

    private fun cancelRegistration(displayId: Int?) {
        val pending = registrationRequest ?: return
        if (displayId != null && pending.source.displayId != displayId) return
        registrationRequest = null
        val original = pending.original
        protect(original) { registrationMessage?.invoke(AnchorMessage.Choice(original.transactionId, original.intent,
            com.code2hack.eyeprojector.protocol.AnchorChoice.CANCEL), original) }
    }

    override fun close() {
        closed = true
        submit {
            registrationRequest?.let { registrationLost?.invoke(it.original) }
            registrationRequest = null
            subscriptions.forEach { subscription -> subscription.observer.close() }
            subscriptions.clear()
        }
    }
}
