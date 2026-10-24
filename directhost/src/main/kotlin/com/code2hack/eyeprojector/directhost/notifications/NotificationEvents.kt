package com.code2hack.eyeprojector.directhost.notifications

/** Only platform record metadata. Neither a key nor key/postTime is a semantic message ID. */
internal data class NotificationRecord(val packageName: String, val key: String, val postTime: Long) {
    // App-controlled tags can occur inside an opaque key. Do not expose them through diagnostics.
    override fun toString(): String = "NotificationRecord(metadata omitted)"
}

/** Identity tokens belong to this classifier instance, not to an activation or app selection. */
internal class NotificationIngressEpoch internal constructor()

internal class NotificationBaselineRequest internal constructor(
    internal val epoch: NotificationIngressEpoch,
    val packages: Set<String>,
)

internal enum class NotificationBaselineState { NOT_CHOSEN, DISCONNECTED, AWAITING, UNAVAILABLE, READY }
internal enum class NotificationEventOrigin { POSTED_CALLBACK, REMOVED_CALLBACK }
internal enum class NotificationEventKind {
    NEW_RECORD,
    UPDATED_OR_REPOSTED,
    DUPLICATE_OR_REPLAY,
    OBSERVED_BEFORE_BASELINE,
    REMOVED_CURRENT,
    REMOVED_NONCURRENT,
    REMOVAL_UNMATCHED,
    NOT_CHOSEN,
    RETIRED_INGRESS,
}

/** All post outcomes describe observed platform records; semantic message novelty remains UNKNOWN. */
internal data class NotificationEventResult(
    val epoch: NotificationIngressEpoch,
    val origin: NotificationEventOrigin,
    val record: NotificationRecord,
    val kind: NotificationEventKind,
    val baseline: NotificationBaselineState,
)

internal sealed interface NotificationSnapshot {
    data class Available(val records: List<NotificationRecord>) : NotificationSnapshot
    data object Unavailable : NotificationSnapshot
}

internal enum class NotificationBaselineOutcome { APPLIED, UNAVAILABLE, RETIRED_REQUEST }

/** Snapshot provenance is separate from posted-callback provenance; it never emits an arrival. */
internal data class NotificationBaselineResult(
    val request: NotificationBaselineRequest,
    val outcome: NotificationBaselineOutcome,
    val appliedPackages: Set<String>,
)

/**
 * Side-effect-free metadata state. Its caller serializes calls and owns current On/Off policy,
 * activation, selection G and permission/lifetime handling. Off records must still be observed.
 *
 * A baseline request must be captured BEFORE querying Android's snapshot. Completion applies
 * only to still-current ingress/membership/request identities. Unknown/failed is not empty.
 * Posts observed while awaiting a baseline are remembered, but are not reported as arrivals.
 * A snapshot supersedes pre-request callback versions, while callbacks received during that
 * query retain their observed version. Both versions join seen history. A matching removal
 * during that request prevents any known version of its retired lifecycle from resurrecting.
 * Snapshot absence is not synthesized into a received removal callback.
 *
 * There is no atomic snapshot/stream cursor. An unseen delayed version can be indistinguishable
 * from a repost; same-millisecond posts can look identical; same-time remove/repost can have ABA
 * ambiguity. Equality is duplicate evidence, never proof of semantic novelty or time ordering.
 * No content, notification flags/type filters, caps, timers, persistent state or output jobs.
 */
internal class NotificationEvents(chosenPackages: Set<String> = emptySet()) {
    private class ActiveRecord(postTime: Long) {
        var currentPostTime = postTime
        val seenPostTimes = mutableSetOf(postTime)
    }

    private class Membership {
        var baseline = NotificationBaselineState.AWAITING
        var request: NotificationBaselineRequest? = null
        val records = mutableMapOf<String, ActiveRecord>()
        val postedDuringSnapshot = mutableSetOf<String>()
        val removedDuringSnapshot = mutableMapOf<String, MutableSet<Long>>()
    }

    private val memberships = chosenPackages.associateWith { Membership() }.toMutableMap()
    private var ingress: NotificationIngressEpoch? = null

    /** Metadata only: retiring ingress must not be interpreted as canceling an accepted selection. */
    fun connected(): NotificationIngressEpoch {
        memberships.keys.toList().forEach { memberships[it] = Membership() }
        return NotificationIngressEpoch().also { ingress = it }
    }

    fun disconnected(epoch: NotificationIngressEpoch): Boolean {
        if (ingress !== epoch) return false
        ingress = null
        memberships.keys.toList().forEach { memberships[it] = Membership() }
        return true
    }

    /** A removed/re-added package has a new membership and requires its own no-catch-up baseline. */
    fun chosenPackages(packages: Set<String>) {
        memberships.keys.retainAll(packages)
        packages.forEach { memberships.getOrPut(it) { Membership() } }
    }

    fun baselineState(packageName: String): NotificationBaselineState = when {
        packageName !in memberships -> NotificationBaselineState.NOT_CHOSEN
        ingress == null -> NotificationBaselineState.DISCONNECTED
        else -> memberships.getValue(packageName).baseline
    }

    /** Returns null when ingress is retired or all current memberships already have a baseline. */
    fun beginBaseline(epoch: NotificationIngressEpoch): NotificationBaselineRequest? {
        if (ingress !== epoch) return null
        val waiting = memberships.filterValues { it.baseline != NotificationBaselineState.READY }
        if (waiting.isEmpty()) return null
        val request = NotificationBaselineRequest(epoch, waiting.keys.toSet())
        waiting.values.forEach {
            it.request = request
            it.postedDuringSnapshot.clear()
            it.removedDuringSnapshot.clear()
        }
        return request
    }

    fun baseline(request: NotificationBaselineRequest, snapshot: NotificationSnapshot): NotificationBaselineResult {
        val current = if (ingress === request.epoch) memberships.filterValues { it.request === request } else emptyMap()
        if (current.isEmpty()) return NotificationBaselineResult(request, NotificationBaselineOutcome.RETIRED_REQUEST, emptySet())
        if (snapshot is NotificationSnapshot.Available) {
            snapshot.records.forEach { record ->
                val member = current[record.packageName] ?: return@forEach
                if (record.postTime !in member.removedDuringSnapshot[record.key].orEmpty()) {
                    val active = member.records.getOrPut(record.key) { ActiveRecord(record.postTime) }
                    active.seenPostTimes.add(record.postTime)
                    if (record.key !in member.postedDuringSnapshot) active.currentPostTime = record.postTime
                }
            }
        }
        current.values.forEach {
            it.baseline = if (snapshot is NotificationSnapshot.Available) NotificationBaselineState.READY else NotificationBaselineState.UNAVAILABLE
            it.request = null
            it.postedDuringSnapshot.clear()
            it.removedDuringSnapshot.clear()
        }
        return NotificationBaselineResult(request,
            if (snapshot is NotificationSnapshot.Available) NotificationBaselineOutcome.APPLIED else NotificationBaselineOutcome.UNAVAILABLE,
            current.keys.toSet())
    }

    fun posted(epoch: NotificationIngressEpoch, record: NotificationRecord): NotificationEventResult =
        observe(epoch, record, NotificationEventOrigin.POSTED_CALLBACK) { member ->
            val active = member.records[record.key]
            if (active != null && !active.seenPostTimes.add(record.postTime)) NotificationEventKind.DUPLICATE_OR_REPLAY
            else {
                if (member.request != null) member.postedDuringSnapshot.add(record.key)
                if (active == null) member.records[record.key] = ActiveRecord(record.postTime)
                else active.currentPostTime = record.postTime
                when {
                    member.baseline != NotificationBaselineState.READY -> NotificationEventKind.OBSERVED_BEFORE_BASELINE
                    active == null -> NotificationEventKind.NEW_RECORD
                    else -> NotificationEventKind.UPDATED_OR_REPOSTED
                }
            }
        }

    fun removed(epoch: NotificationIngressEpoch, record: NotificationRecord): NotificationEventResult =
        observe(epoch, record, NotificationEventOrigin.REMOVED_CALLBACK) { member ->
            if (member.request != null) member.removedDuringSnapshot.getOrPut(record.key) { mutableSetOf() }.add(record.postTime)
            val active = member.records[record.key]
            when {
                active == null -> NotificationEventKind.REMOVAL_UNMATCHED
                active.currentPostTime == record.postTime -> {
                    if (member.request != null) member.removedDuringSnapshot.getValue(record.key).addAll(active.seenPostTimes)
                    member.records.remove(record.key)
                    NotificationEventKind.REMOVED_CURRENT
                }
                record.postTime in active.seenPostTimes -> NotificationEventKind.REMOVED_NONCURRENT
                else -> NotificationEventKind.REMOVAL_UNMATCHED
            }
        }

    private inline fun observe(epoch: NotificationIngressEpoch, record: NotificationRecord, origin: NotificationEventOrigin,
        classify: (Membership) -> NotificationEventKind): NotificationEventResult {
        if (ingress !== epoch) return NotificationEventResult(epoch, origin, record,
            NotificationEventKind.RETIRED_INGRESS, NotificationBaselineState.DISCONNECTED)
        val member = memberships[record.packageName] ?: return NotificationEventResult(epoch, origin, record,
            NotificationEventKind.NOT_CHOSEN, NotificationBaselineState.NOT_CHOSEN)
        return NotificationEventResult(epoch, origin, record, classify(member), member.baseline)
    }
}
