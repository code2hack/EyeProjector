package com.code2hack.eyeprojector.directhost.notifications

import java.io.Closeable
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Process-local intent ordering and optional passive facts. No I/O/controller/callback under gate. */
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
    private class RevisionDiagnostics {
        val identity = Any() // Receipts retain this opaque identity, never this state or its maps.
        val observers = IdentityHashMap<Any, Unit>()
        val latest = mutableMapOf<String, NotificationPolicyAssignment>()
    }
    private var revisionDiagnostics: RevisionDiagnostics? = null
    val persistenceFailed: Boolean get() = synchronized(gate) { failedMutations.isNotEmpty() }

    class Snapshot internal constructor(val packageName: String, val revision: Long, val choiceRevision: Long,
        val accessRevision: Long, val chosen: Boolean, val enabled: Boolean,
        internal val diagnostic: NotificationObservationTrace? = null,
        internal val diagnosticWindowIdentity: Any? = null)
    class Receipt internal constructor(val packageName: String, val revision: Long, val choiceRevision: Long, val accessRevision: Long,
        internal var diagnostic: NotificationObservationTrace? = null,
        internal val diagnosticWindowIdentity: Any? = null)
    class Change internal constructor(val packageName: String, val isMembership: Boolean, val desired: Boolean, val revision: Long)

    /** Capture BEFORE blocking reads. An intervening intent invalidates this original receipt. */
    fun receipt(packageName: String, diagnostic: NotificationObservationTrace? = null): Receipt = synchronized(gate) {
        // Authority arguments are captured first. The final opaque field is diagnostic only.
        Receipt(packageName, packageRevisions[packageName] ?: 0, choiceRevision, accessRevision,
            diagnostic, revisionDiagnostics?.identity).also { original ->
            diagnostic?.originalPackageRevision = original.revision
            diagnostic?.originalPackageRevisionObserved = true
        }
    }
    fun prepare(original: Receipt, diagnostic: NotificationObservationTrace? = original.diagnostic,
        read: () -> Pair<Boolean, Boolean>): Snapshot? {
        diagnostic?.originalPackageRevision = original.revision
        diagnostic?.originalPackageRevisionObserved = true
        diagnostic?.prepareValuesObserved = false
        diagnostic?.comparedPackageRevisionObserved = false
        diagnostic?.latestAssignment = null
        val values = try { read() } catch (error: Exception) {
            diagnostic?.prepare = NotificationPrepareDisposition.READ_EXCEPTION
            diagnostic?.prepareFault = notificationDiagnosticFault(error)
            return null
        }
        diagnostic?.prepare = NotificationPrepareDisposition.RETURNED
        val chosen = values.first
        val on = values.second
        diagnostic?.preparedChosen = chosen
        diagnostic?.preparedEnabled = on
        diagnostic?.prepareValuesObserved = true
        return Snapshot(original.packageName, original.revision, original.choiceRevision, original.accessRevision,
            chosen, on, diagnostic, original.diagnosticWindowIdentity)
    }

    /** Lifetime bookkeeping for the EXISTING pipeline observer, not another subscription. */
    fun pipelineObserverAdded(token: Any) {
        synchronized(gate) {
            try {
                val active = revisionDiagnostics
                if (active == null) {
                    val fresh = RevisionDiagnostics()
                    fresh.observers[token] = Unit
                    revisionDiagnostics = fresh
                } else active.observers[token] = Unit
            } catch (_: Throwable) {
                // Diagnostic allocation failure cannot change observer delivery/product work.
                // A lost window is UNKNOWN; a later registration gets a fresh opaque identity.
                revisionDiagnostics = null
            }
        }
    }
    fun pipelineObserverRemoved(token: Any) {
        synchronized(gate) {
            val active = revisionDiagnostics ?: return@synchronized
            try {
                if (active.observers.remove(token) != null && active.observers.isEmpty())
                    revisionDiagnostics = null
            } catch (_: Throwable) { revisionDiagnostics = null }
        }
    }

    private fun recordAssignmentLocked(change: Change, assignedRevision: Long, failedFinish: Boolean) {
        val active = revisionDiagnostics ?: return // No diagnostic object/enum construction disabled.
        try {
            active.latest[change.packageName] = NotificationPolicyAssignment(assignedRevision,
                if (failedFinish) NotificationPolicyWriterKind.FAILED_CURRENT_FINISH else NotificationPolicyWriterKind.BEGIN,
                if (change.isMembership) NotificationPolicyChangeKind.MEMBERSHIP else NotificationPolicyChangeKind.ON,
                change.desired, change.revision)
        } catch (_: Throwable) { revisionDiagnostics = null }
    }
    private fun assignmentForComparisonLocked(snapshot: Snapshot, comparedRevision: Long): NotificationPolicyAssignment? {
        val active = revisionDiagnostics ?: return null
        if (snapshot.diagnosticWindowIdentity == null || snapshot.diagnosticWindowIdentity !== active.identity) return null
        return try {
            // ONE optional diagnostic-sidecar lookup, separate from the ONE authority read below.
            val recorded = active.latest[snapshot.packageName]
            if (recorded != null && recorded.assignedRevision == comparedRevision) recorded else null
        } catch (_: Throwable) { revisionDiagnostics = null; null }
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
                val assignedRevision = revision
                packageRevisions[packageName] = assignedRevision
                recordAssignmentLocked(it, assignedRevision, false)
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
                    val assignedRevision = revision
                    packageRevisions[change.packageName] = assignedRevision
                    recordAssignmentLocked(change, assignedRevision, true)
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
        // actually evaluated is copied; later checks remain NOT_EVALUATED. No extra authority or
        // canonical read occurs. An original active diagnostic window may read its sidecar once.
        val diagnostic = snapshot.diagnostic
        diagnostic?.comparedPackageRevisionObserved = false
        diagnostic?.latestAssignment = null
        val packageMatches = snapshot.packageName == selection.packageName
        diagnostic?.packageCheck = if (packageMatches) NotificationCheckResult.MATCHED else NotificationCheckResult.REJECTED
        if (!packageMatches) { diagnostic?.install = NotificationInstallDisposition.REJECTED; return false }
        val comparedRevision = packageRevisions[snapshot.packageName] ?: 0
        val packageRevisionMatches = snapshot.revision == comparedRevision
        if (diagnostic != null) {
            diagnostic.comparedPackageRevision = comparedRevision
            diagnostic.comparedPackageRevisionObserved = true
            diagnostic.latestAssignment = assignmentForComparisonLocked(snapshot, comparedRevision)
        }
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
