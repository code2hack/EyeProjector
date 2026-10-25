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
        val accessRevision: Long, val chosen: Boolean, val enabled: Boolean)
    class Receipt internal constructor(val packageName: String, val revision: Long, val choiceRevision: Long, val accessRevision: Long)
    class Change internal constructor(val packageName: String, val isMembership: Boolean, val desired: Boolean, val revision: Long)

    /** Capture BEFORE blocking reads. An intervening intent invalidates this original receipt. */
    fun receipt(packageName: String): Receipt = synchronized(gate) {
        Receipt(packageName, packageRevisions[packageName] ?: 0, choiceRevision, accessRevision)
    }
    fun prepare(original: Receipt, read: () -> Pair<Boolean, Boolean>): Snapshot? {
        val values = try { read() } catch (_: Exception) { return null }
        return Snapshot(original.packageName, original.revision, original.choiceRevision, original.accessRevision, values.first, values.second)
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
        if (snapshot.packageName != selection.packageName || snapshot.revision != (packageRevisions[snapshot.packageName] ?: 0) ||
            snapshot.choiceRevision != choiceRevision || snapshot.accessRevision != accessRevision || access != NotificationAccessApproval.GRANTED ||
            !(membership[snapshot.packageName] ?: snapshot.chosen) || !(enabled[snapshot.packageName] ?: snapshot.enabled)) return false
        selected?.permit?.retire()
        selected = selection; revision++; choiceRevision++
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
