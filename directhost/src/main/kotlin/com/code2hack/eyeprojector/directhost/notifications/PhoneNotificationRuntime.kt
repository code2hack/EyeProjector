package com.code2hack.eyeprojector.directhost.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.apps.ChosenApps
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NotificationAccessApproval { GRANTED, DENIED, UNKNOWN }
/** OBSERVED means a public snapshot return, not proof of complete/successful underlying RPC. */
internal enum class NotificationListenerBaseline { DISCONNECTED, NO_CHOSEN_APPS, INITIALIZING, OBSERVED, UNAVAILABLE }
internal data class NotificationAccessStatus(val approval: NotificationAccessApproval, val connected: Boolean,
    val baseline: NotificationListenerBaseline, val chosenAvailable: Boolean, val launchFailed: Boolean = false)

/** Minted at actual connected receipt, never recovered from the latest listener singleton. */
internal class NotificationListenerConnection {
    private val active = AtomicBoolean(true)
    fun isActive(): Boolean = active.get()
    fun retire() { active.set(false) }
}

/** Local receipt scope, not proof of native incarnation or accepted app-selection continuity. */
internal class NotificationReceiptScope(val connection: NotificationListenerConnection, val membership: Any)
internal data class NotificationIngressObservation(val scope: NotificationReceiptScope, val event: NotificationEventResult,
    val receipt: Long = 0, val admission: NotificationPolicy.Receipt? = null)

/**
 * Main-thread metadata/status owner. NLS callbacks (minSdk32) classify synchronously before any
 * new queue. Only snapshot I/O goes to a worker; completion returns to the same main owner.
 * Production selection consumes the classified receipt inline; no backend or durable event queue.
 */
internal class PhoneNotificationRuntime(
    private val readChosen: () -> Set<String>,
    private val observeChosen: (() -> Unit) -> Closeable,
    private val readApproval: () -> NotificationAccessApproval,
    private val runSnapshot: (() -> NotificationSnapshot, (NotificationSnapshot) -> Unit) -> Unit,
    private val onMain: (() -> Unit) -> Unit,
    private val selection: NotificationSelectionOwner? = null,
) {
    private class Session(val connection: NotificationListenerConnection, val epoch: NotificationIngressEpoch,
        val snapshot: (Set<String>) -> NotificationSnapshot) {
        var choices: Closeable? = null
        var inFlight: NotificationBaselineRequest? = null
        var snapshotWanted = true
    }
    private val events = NotificationEvents()
    private var session: Session? = null
    private var chosen = emptySet<String>()
    private var chosenAvailable = true
    private var membership = Any()
    private var approval = NotificationAccessApproval.UNKNOWN
    private val statusObservers = linkedSetOf<() -> Unit>()
    private val eventObservers = linkedSetOf<(NotificationIngressObservation) -> Unit>()
    private var receiptSequence = 0L
    private var preferenceObservation: Closeable? = null

    fun status(): NotificationAccessStatus {
        val baseline = when {
            session == null -> NotificationListenerBaseline.DISCONNECTED
            !chosenAvailable -> NotificationListenerBaseline.UNAVAILABLE
            chosen.isEmpty() -> NotificationListenerBaseline.NO_CHOSEN_APPS
            chosen.any { events.baselineState(it) == NotificationBaselineState.AWAITING } -> NotificationListenerBaseline.INITIALIZING
            chosen.any { events.baselineState(it) == NotificationBaselineState.UNAVAILABLE } -> NotificationListenerBaseline.UNAVAILABLE
            else -> NotificationListenerBaseline.OBSERVED
        }
        return NotificationAccessStatus(approval, session != null, baseline, chosenAvailable, selection?.failed == true)
    }

    // Registration itself invokes no callback. UI/event callbacks run outside classifier transitions.
    fun observeStatus(observer: () -> Unit): Closeable {
        statusObservers.add(observer)
        return Closeable { statusObservers.remove(observer) }
    }
    fun observeEvents(observer: (NotificationIngressObservation) -> Unit): Closeable {
        eventObservers.add(observer)
        return Closeable { eventObservers.remove(observer) }
    }

    fun connected(connection: NotificationListenerConnection, snapshot: (Set<String>) -> NotificationSnapshot) {
        val previous = session
        val original = Session(connection, events.connected(), snapshot)
        session = original
        membership = Any()
        previous?.let(::retire)
        if (session !== original) return
        updateApproval()
        refreshMembership(original)
        if (session !== original) return
        val registration = observeChosen {
            onMain {
                if (session === original) {
                    refreshMembership(original)
                    startSnapshot(original)
                    publishStatus()
                }
            }
        }
        if (session === original) original.choices = registration else registration.close()
        startSnapshot(original)
        publishStatus()
    }

    fun disconnected(connection: NotificationListenerConnection) {
        val original = session?.takeIf { it.connection === connection } ?: return
        session = null // Retire ownership before detach/observer callbacks can reenter.
        retire(original)
        updateApproval()
        publishStatus()
    }

    /** Explicit panel refresh/reconnect/membership addition drives queries; failure never loops. */
    fun refresh() {
        updateApproval()
        val original = session
        if (original != null) {
            refreshMembership(original)
            original.snapshotWanted = true
            startSnapshot(original)
        }
        publishStatus()
    }

    fun posted(connection: NotificationListenerConnection, packageName: String, extract: () -> NotificationRecord) =
        receive(connection, packageName, extract, false)
    fun removed(connection: NotificationListenerConnection, packageName: String, extract: () -> NotificationRecord) =
        receive(connection, packageName, extract, true)

    private fun receive(connection: NotificationListenerConnection, packageName: String,
        extract: () -> NotificationRecord, removed: Boolean): NotificationIngressObservation? {
        val original = session?.takeIf { it.connection === connection } ?: return null
        // Before ANY chosen read/reentry: Stop cannot let this old receipt adopt a fresh H.
        val admission = selection?.receipt(packageName)
        val receipt = ++receiptSequence
        refreshMembership(original) // Actual first receipt, not a potentially lagging preference observer.
        val scope = NotificationReceiptScope(connection, membership)
        val record = if (session === original && chosenAvailable && packageName in chosen) runCatching(extract).getOrNull() else null
        if (record == null || session !== original || membership !== scope.membership || record.packageName != packageName) {
            startSnapshot(original)
            publishStatus()
            return null
        }
        val result = if (removed) events.removed(original.epoch, record) else events.posted(original.epoch, record)
        val observation = NotificationIngressObservation(scope, result, receipt, admission)
        // No queue or external observer precedes classification of this captured receipt.
        updateApproval()
        if (session === original && membership === scope.membership) selection?.accept(observation)
        eventObservers.toList().forEach { observer -> runCatching { observer(observation) } }
        startSnapshot(original)
        publishStatus()
        return observation
    }

    private fun refreshMembership(original: Session) {
        val current = try { readChosen().toSet() } catch (_: Exception) {
            if (session === original) chosenAvailable = false
            return
        }
        if (session !== original) return
        chosenAvailable = true
        if (current != chosen) {
            val added = current - chosen
            chosen = current
            membership = Any()
            events.chosenPackages(current)
            if (added.isNotEmpty()) original.snapshotWanted = true
        }
    }

    private fun startSnapshot(original: Session) {
        if (session !== original || !chosenAvailable || original.inFlight != null || !original.snapshotWanted) return
        original.snapshotWanted = false
        val request = events.beginBaseline(original.epoch) ?: return
        original.inFlight = request
        val packages = request.packages.toSet()
        val complete: (NotificationSnapshot) -> Unit = { snapshot ->
            if (session === original && original.inFlight === request) {
                refreshMembership(original)
                if (session === original && original.inFlight === request) {
                    events.baseline(request, snapshot)
                    original.inFlight = null
                    // A membership addition during the query is a retained external trigger.
                    startSnapshot(original)
                    publishStatus()
                }
            }
        }
        try { runSnapshot({
            if (!original.connection.isActive()) NotificationSnapshot.Unavailable
            else try { original.snapshot(packages) } catch (_: Exception) { NotificationSnapshot.Unavailable }
        }, complete) } catch (_: Exception) { complete(NotificationSnapshot.Unavailable) }
    }

    private fun retire(original: Session) {
        original.connection.retire()
        events.disconnected(original.epoch)
        runCatching { original.choices?.close() }
        original.choices = null
    }
    private fun readApprovalSafely() = try { readApproval() } catch (_: Exception) { NotificationAccessApproval.UNKNOWN }
    private fun updateApproval() { approval = readApprovalSafely(); selection?.access(approval) }
    private fun publishStatus() { statusObservers.toList().forEach { runCatching(it) } }

    companion object {
        @SuppressLint("StaticFieldLeak") // Application context only; no Activity or View is retained.
        @Volatile private var instance: PhoneNotificationRuntime? = null
        fun get(context: Context): PhoneNotificationRuntime = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
        private fun create(app: Context): PhoneNotificationRuntime {
            val main = Handler(Looper.getMainLooper())
            val snapshots = Executors.newSingleThreadExecutor()
            lateinit var runtime: PhoneNotificationRuntime
            val owner = NotificationSelectionOwner(FacadeNotificationPort(PhoneSessionController.get(app)), NotificationPolicy.process,
                { packageName -> ChosenApps.open(app).read().any { it.packageName == packageName } to AppNotificationPreferences.open(app).isEnabled(packageName) },
                { work -> main.post { work() } }, { main.post { runtime.publishStatus() } })
            runtime = PhoneNotificationRuntime(
                { ChosenApps.open(app).read().map { it.packageName }.toSet() },
                { changed -> ChosenApps.observe(app, changed) },
                { NotificationAccess.approval(app) },
                { work, complete -> snapshots.execute { val result = work(); main.post { complete(result) } } },
                { work -> if (Looper.myLooper() == Looper.getMainLooper()) work() else main.post { work() } },
                owner,
            )
            runtime.preferenceObservation = AppNotificationPreferences.observe(app) { main.post { runtime.refresh() } }
            return runtime
        }
    }
}
