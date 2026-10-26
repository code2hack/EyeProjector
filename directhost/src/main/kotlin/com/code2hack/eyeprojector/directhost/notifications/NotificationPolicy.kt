package com.code2hack.eyeprojector.directhost.notifications

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** Process-local intent ordering only. No storage, controller construction or callbacks under gate. */
internal class NotificationPolicy {
    val gate = Any()
    private var revision = 0L
    private var choiceRevision = 0L
    private var accessRevision = 0L
    private val packageRevisions = mutableMapOf<String, Long>()
    private var access = NotificationAccessApproval.UNKNOWN
    private val membership = mutableMapOf<String, Boolean>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val mutations = mutableMapOf<Pair<String, Boolean>, Change>()
    private val failedMutations = mutableSetOf<Pair<String, Boolean>>()
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private var selected: NotificationSelection? = null
    val persistenceFailed: Boolean get() = synchronized(gate) { failedMutations.isNotEmpty() }

    class Snapshot internal constructor(val packageName: String, val revision: Long, val choiceRevision: Long,
        val accessRevision: Long, val chosen: Boolean, val enabled: Boolean,
        internal val diagnostic: NotificationObservationTrace? = null)
    class Receipt internal constructor(val packageName: String, val revision: Long, val choiceRevision: Long, val accessRevision: Long,
        internal var diagnostic: NotificationObservationTrace? = null)
    class Change internal constructor(val packageName: String, val isMembership: Boolean, val desired: Boolean, val revision: Long)

    /** Capture BEFORE blocking reads. An intervening intent invalidates this original receipt. */
    fun receipt(packageName: String, diagnostic: NotificationObservationTrace? = null): Receipt = synchronized(gate) {
        Receipt(packageName, packageRevisions[packageName] ?: 0, choiceRevision, accessRevision, diagnostic)
    }
    fun prepare(original: Receipt, diagnostic: NotificationObservationTrace? = original.diagnostic,
        read: () -> Pair<Boolean, Boolean>): Snapshot? {
        val values = try { read() } catch (error: Exception) {
            diagnostic?.prepare = NotificationPrepareDisposition.READ_EXCEPTION
            diagnostic?.prepareFault = notificationDiagnosticFault(error)
            return null
        }
        diagnostic?.prepare = NotificationPrepareDisposition.RETURNED
        return Snapshot(original.packageName, original.revision, original.choiceRevision, original.accessRevision,
            values.first, values.second, diagnostic)
    }

    fun access(value: NotificationAccessApproval) {
        synchronized(gate) {
            if (access != value) {
                access = value; revision++; accessRevision++
                if (value == NotificationAccessApproval.DENIED) selected?.permit?.retire()
            }
        }
        publish()
    }

    fun begin(packageName: String, isMembership: Boolean, desired: Boolean): Change {
        val change = synchronized(gate) {
            Change(packageName, isMembership, desired, ++revision).also {
                (if (isMembership) membership else enabled)[packageName] = desired
                mutations[packageName to isMembership] = it
                packageRevisions[packageName] = revision
                if (!desired) selected?.takeIf { g -> g.packageName == packageName }?.permit?.retire()
            }
        }
        publish()
        return change
    }

    /** Called while the canonical writer owns its I/O serialization, never under policy gate. */
    fun isCurrent(change: Change): Boolean = synchronized(gate) { mutations[change.packageName to change.isMembership] === change }

    fun finish(change: Change, success: Boolean) {
        synchronized(gate) {
            if (mutations[change.packageName to change.isMembership] === change) {
                mutations.remove(change.packageName to change.isMembership)
                if (success) failedMutations.remove(change.packageName to change.isMembership)
                if (!success) {
                    failedMutations.add(change.packageName to change.isMembership); revision++
                    packageRevisions[change.packageName] = revision
                    // Reconcile NEW eligibility from the next actual canonical read, including
                    // default/saved On and redundant add. Never resurrect the old retired G.
                    (if (change.isMembership) membership else enabled).remove(change.packageName)
                    selected?.takeIf { it.packageName == change.packageName }?.permit?.retire()
                }
            }
        }
        publish()
    }

    /** Caller holds gate and then acquires the facade fastLock, never the reverse. */
    fun installLocked(selection: NotificationSelection, snapshot: Snapshot): Boolean {
        // The original predicate's order and short circuit are unchanged. Only the comparison
        // actually evaluated is copied; later checks remain NOT_EVALUATED. No callbacks or reads
        // beyond those in that predicate occur under gate, including when a diagnostic is active.
        val diagnostic = snapshot.diagnostic
        val packageMatches = snapshot.packageName == selection.packageName
        diagnostic?.packageCheck = if (packageMatches) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!packageMatches) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val packageRevisionMatches = snapshot.revision == (packageRevisions[snapshot.packageName] ?: 0)
        diagnostic?.packageRevision = if (packageRevisionMatches) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!packageRevisionMatches) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val choiceMatches = snapshot.choiceRevision == choiceRevision
        diagnostic?.choiceRevision = if (choiceMatches) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!choiceMatches) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val accessMatches = snapshot.accessRevision == accessRevision
        diagnostic?.accessRevision = if (accessMatches) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!accessMatches) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val approved = access == NotificationAccessApproval.GRANTED
        diagnostic?.approval = if (approved) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!approved) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val chosen = membership[snapshot.packageName] ?: snapshot.chosen
        diagnostic?.chosen = if (chosen) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!chosen) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val on = enabled[snapshot.packageName] ?: snapshot.enabled
        diagnostic?.enabled = if (on) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!on) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        selected?.permit?.retire()
        selected = selection; revision++; choiceRevision++
        diagnostic?.installedSelection = selection
        diagnostic?.install = NotificationInstallDisposition.INSTALLED
        return true
    }
    fun retireLocked() { revision++; choiceRevision++; selected?.permit?.retire() }
    fun explicitChoice() { synchronized(gate) { retireLocked() }; publish() }
    fun revoke(selection: NotificationSelection) { synchronized(gate) { selection.permit.retire() }; publish() }
    fun release(selection: NotificationSelection) { synchronized(gate) { if (selected === selection) selected = null } }
    fun observe(observer: () -> Unit): Closeable { observers.add(observer); return Closeable { observers.remove(observer) } }
    fun publish() { observers.forEach { runCatching(it) } }

    companion object { val process = NotificationPolicy() }
}
