package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.AnchorIntent
import com.code2hack.eyeprojector.protocol.AnchorIntentKind
import com.code2hack.eyeprojector.protocol.AnchorMessage
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * retirements use one FIFO lane; submit MUST enqueue, including when invoked by that lane.
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
    private class Subscription(var route: AnchorOriginalRoute, val context: ProjectionContext, val displayId: Int,
        val packageName: String, val observer: Closeable)
    private val subscriptions = mutableListOf<Subscription>() // FIFO lane only.
    private var registrationRequest: CapturedAnchorRequest? = null
    private val accessRevision = AtomicLong()
    private val displayRevisions = ConcurrentHashMap<Int, AtomicLong>()
    private data class WindowOwner(val route: AnchorOriginalRoute, val source: ManagedUiSource,
        val window: UiWindowMetadata)
    private val windowOwners = mutableMapOf<ProjectionContext, WindowOwner>() // FIFO lane only.
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
            try { original.retire() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
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
            // Original metadata + in-memory revision check; no saved record/source/target lookup.
            val held = original.heldSetting()
            when (original.releaseHeld(held, held == null || store.isCurrent(held))) {
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
        val access = accessRevision.get()
        val display = displayRevisions.computeIfAbsent(source.displayId) { AtomicLong() }
        val displayRevision = display.get()
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
                accessRevision.get() != access -> "ACCESS_CHANGED"
                display.get() != displayRevision -> "DISPLAY_CHANGED"
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
        observeWindow(original, source, resolved.current.window)
        // A setting read/event alone is not retirement ACK: reconcile above completed on the owner.
        // A concurrent later commit invalidates this read; its queued callback retires original use.
        if (closed || accessRevision.get() != access || display.get() != displayRevision ||
            !store.isCurrent(setting.context) || original.source() != resolved.current.source)
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

    /** Captured selection identity only; target coordinates remain owned by the original contact. */
    fun observeWindow(route: AnchorOriginalRoute, source: ManagedUiSource, window: UiWindowMetadata) {
        windowOwners[source.context] = WindowOwner(route, source, window)
    }

    /** A state/bounds event can be the application's response to DOWN in the SAME window.
     * Reconcile actual source/window identity on the app FIFO before retiring that original. */
    fun windowChanged(displayId: Int, windowId: Int, currentChanged: Boolean) {
        submit {
            if (closed) return@submit
            val originals = windowOwners.values.filter { it.source.displayId == displayId &&
                (currentChanged || it.window.windowId == windowId) }
            originals.forEach { original -> protect(original.route) {
                if (windowOwners[original.source.context] !== original) return@protect
                val source = original.route.source()
                // The managed display can briefly be between unchanged layout observations.
                // A missing read is not source teardown; actual binding/service/Session closure
                // has its own unconditional retirement, and Choice still revalidates before save.
                if (source == null) {
                    AnchorAppRuntime.flow("window_observed transaction=${original.route.transactionId} display=$displayId window=$windowId capturedWindow=${original.window.windowId} sourceObserved=false")
                    return@protect
                }
                val snapshot = if (source == original.source) UiTargetSnapshotProvider(
                    ManagedUiSourceProvider { expected -> original.route.source()?.takeIf { it.context == expected } },
                    reader, uptime).read(original.source.context, original.source.foregroundPackage ?: return@protect,
                    original.window.windowId) else null
                // A held app may hide/change its target or focus a recording overlay. Neither
                // moves the original contact. Only actual source/window loss retires it;
                // a transient node traversal failure is not proof of window destruction.
                val current = snapshot?.windows?.singleOrNull { it.window.displayId == displayId &&
                    it.window.windowId == original.window.windowId && it.window.type == original.window.type &&
                    it.status != UiWindowReadStatus.OTHER_PACKAGE }
                val windowAbsent = when (snapshot?.status) {
                    UiTargetReadStatus.READ, UiTargetReadStatus.WINDOW_ABSENT,
                    UiTargetReadStatus.DISPLAY_ABSENT -> current == null
                    else -> false
                }
                val retained = source == original.source && !windowAbsent &&
                    original.route.source() == original.source
                AnchorAppRuntime.flow("window_observed transaction=${original.route.transactionId} display=$displayId window=$windowId capturedWindow=${original.window.windowId} retained=$retained read=${snapshot?.status} sourceCurrent=${source == original.source}")
                if (!retained && windowOwners[original.source.context] === original) {
                    windowOwners.remove(original.source.context)
                    displayRevisions.computeIfAbsent(displayId) { AtomicLong() }.incrementAndGet()
                    if (registrationRequest?.original?.identity == original.route.identity) cancelRegistration(displayId)
                    original.route.retire()
                }
            } }
        }
    }

    fun contextRetired(displayId: Int? = null) {
        if (displayId == null) accessRevision.incrementAndGet()
        else displayRevisions.computeIfAbsent(displayId) { AtomicLong() }.incrementAndGet()
        submit {
            cancelRegistration(displayId)
            val originals = windowOwners.values.filter { displayId == null || it.source.displayId == displayId }
            originals.forEach { windowOwners.remove(it.source.context) }
            (originals.map { it.route } + subscriptions.filter { displayId == null || it.displayId == displayId }.map { it.route }).distinct().forEach { route ->
                protect(route) { route.retire() }
            }
            if (displayId == null) { subscriptions.forEach { it.observer.close() }; subscriptions.clear() }
        }
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
            cancelRegistration(null)
            windowOwners.clear()
            subscriptions.forEach { subscription -> subscription.observer.close(); protect(subscription.route) { subscription.route.retire() } }
            subscriptions.clear()
        }
    }
}
