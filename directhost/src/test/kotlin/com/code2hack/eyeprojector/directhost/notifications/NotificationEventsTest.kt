package com.code2hack.eyeprojector.directhost.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationEventsTest {
    private val app = "example.chosen"
    private val other = "example.other"
    private fun record(time: Long, key: String = "opaque-record", packageName: String = app) =
        NotificationRecord(packageName, key, time)
    private fun NotificationEvents.snapshot(epoch: NotificationIngressEpoch, vararg records: NotificationRecord) =
        baseline(beginBaseline(epoch)!!, NotificationSnapshot.Available(records.toList()))
    private fun ready(): Pair<NotificationEvents, NotificationIngressEpoch> {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        events.snapshot(epoch)
        return events to epoch
    }

    @Test fun existingSnapshotIsNotAnArrivalAndCallbackRetainsItsProvenance() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val existing = record(100)
        val snapshot = events.snapshot(epoch, existing)
        assertEquals(NotificationBaselineOutcome.APPLIED, snapshot.outcome)
        assertEquals(setOf(app), snapshot.appliedPackages)
        val replay = events.posted(epoch, existing)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, replay.kind)
        assertEquals(NotificationEventOrigin.POSTED_CALLBACK, replay.origin)
        assertSame(existing, replay.record)
        assertSame(epoch, replay.epoch)
        assertEquals(NotificationBaselineState.READY, replay.baseline)
    }

    @Test fun previouslyAbsentRecordAfterBaselineIsANewObservedRecord() {
        val (events, epoch) = ready()
        val posted = record(1)
        val result = events.posted(epoch, posted)
        assertEquals(NotificationEventKind.NEW_RECORD, result.kind)
        assertSame(posted, result.record)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, posted.copy()).kind)
    }

    @Test fun sameKeyDistinctVersionIsRepostWithoutASemanticMessageClaim() {
        val (events, epoch) = ready()
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(100)).kind)
        assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(101)).kind)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(100)).kind)
    }

    @Test fun wallClockReversalAndDelayedSeenVersionDoNotChangeCurrentVersion() {
        val (events, epoch) = ready()
        events.posted(epoch, record(100))
        assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(90)).kind)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(100)).kind)
        assertEquals(NotificationEventKind.REMOVED_NONCURRENT, events.removed(epoch, record(100)).kind)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(90)).kind)
        assertEquals(NotificationEventKind.REMOVED_CURRENT, events.removed(epoch, record(90)).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(90)).kind)
    }

    @Test fun removalOfUnseenVersionDoesNotEraseTheKnownActiveRecord() {
        val (events, epoch) = ready()
        events.posted(epoch, record(100))
        val removal = events.removed(epoch, record(999))
        assertEquals(NotificationEventKind.REMOVAL_UNMATCHED, removal.kind)
        assertEquals(NotificationEventOrigin.REMOVED_CALLBACK, removal.origin)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(100)).kind)
    }

    @Test fun removalWithoutObservedRecordRemainsUnmatched() {
        val (events, epoch) = ready()
        assertEquals(NotificationEventKind.REMOVAL_UNMATCHED, events.removed(epoch, record(100)).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(100)).kind)
    }

    @Test fun equalTimestampCannotDistinguishTwoActualPostsButObservedRemovalStartsANewLifecycle() {
        val (events, epoch) = ready()
        val firstPost = record(100)
        val genuinelySeparateSameMillisecondPost = firstPost.copy()
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, firstPost).kind)
        // This limitation is intentional: the permitted metadata contains no distinct post ID.
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, genuinelySeparateSameMillisecondPost).kind)
        assertEquals(NotificationEventKind.REMOVED_CURRENT, events.removed(epoch, firstPost).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, firstPost.copy()).kind)
    }

    @Test fun sameTimeOldRemovalAfterRepostHasExplicitUnresolvableAbaLimit() {
        val (events, epoch) = ready()
        val original = record(100)
        events.posted(epoch, original)
        events.removed(epoch, original)
        events.posted(epoch, original.copy())
        // An identical late old removal and a current removal are indistinguishable metadata.
        assertEquals(NotificationEventKind.REMOVED_CURRENT, events.removed(epoch, original).kind)
    }

    @Test fun failedBaselineIsNotAnEmptySuccessfulSnapshot() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val unavailable = events.baseline(events.beginBaseline(epoch)!!, NotificationSnapshot.Unavailable)
        assertEquals(NotificationBaselineOutcome.UNAVAILABLE, unavailable.outcome)
        assertEquals(NotificationBaselineState.UNAVAILABLE, events.baselineState(app))
        val observed = events.posted(epoch, record(10))
        assertEquals(NotificationEventKind.OBSERVED_BEFORE_BASELINE, observed.kind)
        assertEquals(NotificationBaselineState.UNAVAILABLE, observed.baseline)
        assertEquals(NotificationBaselineOutcome.APPLIED, events.snapshot(epoch).outcome)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(20, "another")).kind)
    }

    @Test fun callbackBeforeSnapshotRequestIsConsumedWithoutCatchUp() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        assertEquals(NotificationEventKind.OBSERVED_BEFORE_BASELINE, events.posted(epoch, record(10)).kind)
        events.snapshot(epoch, record(10))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
    }

    @Test fun preRequestCallbackDoesNotOutvoteALaterSnapshotWithoutAnInterveningPost() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        events.posted(epoch, record(10))
        val request = events.beginBaseline(epoch)!!
        events.baseline(request, NotificationSnapshot.Available(listOf(record(20))))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.REMOVED_CURRENT, events.removed(epoch, record(20)).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(20)).kind)
    }

    @Test fun duplicateDuringSnapshotDoesNotMakeAPreRequestVersionCurrentAgain() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        events.posted(epoch, record(10))
        val request = events.beginBaseline(epoch)!!
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
        events.baseline(request, NotificationSnapshot.Available(listOf(record(20))))
        assertEquals(NotificationEventKind.REMOVED_CURRENT, events.removed(epoch, record(20)).kind)
    }

    @Test fun callbackDuringSnapshotKeepsItsVersionAndMergesSnapshotHistory() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val request = events.beginBaseline(epoch)!!
        assertEquals(NotificationEventKind.OBSERVED_BEFORE_BASELINE, events.posted(epoch, record(20)).kind)
        events.baseline(request, NotificationSnapshot.Available(listOf(record(10))))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.REMOVED_NONCURRENT, events.removed(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(20)).kind)
        assertEquals(NotificationEventKind.REMOVED_CURRENT, events.removed(epoch, record(20)).kind)
    }

    @Test fun removalDuringSnapshotPreventsTheMatchingSnapshotVersionFromResurrecting() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val request = events.beginBaseline(epoch)!!
        events.posted(epoch, record(10))
        events.removed(epoch, record(10))
        events.baseline(request, NotificationSnapshot.Available(listOf(record(10))))
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10)).kind)
    }

    @Test fun currentRemovalDuringSnapshotRetiresAllKnownVersionsOfThatLifecycle() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val request = events.beginBaseline(epoch)!!
        events.posted(epoch, record(10))
        events.posted(epoch, record(20))
        events.removed(epoch, record(20))
        events.baseline(request, NotificationSnapshot.Available(listOf(record(10))))
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10)).kind)
    }

    @Test fun unknownRemovalDuringSnapshotDoesNotEraseADifferentSnapshotVersion() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val request = events.beginBaseline(epoch)!!
        assertEquals(NotificationEventKind.REMOVAL_UNMATCHED, events.removed(epoch, record(10)).kind)
        events.baseline(request, NotificationSnapshot.Available(listOf(record(20))))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(20)).kind)
    }

    @Test fun snapshotCanAbsorbARealArrivalWhoseCallbackWasQueued() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        // A real post during the snapshot window is already present in this synthetic snapshot.
        events.snapshot(epoch, record(20))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(20)).kind)
    }

    @Test fun unseenDelayedVersionCannotBeProvenOldFromWallClockMetadata() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        events.snapshot(epoch, record(20))
        // This could be an older queued post or a new post after a clock reversal.
        assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(10)).kind)
    }

    @Test fun reconnectBaselinesAgainAndOldIngressCannotMutateOrDisconnectIt() {
        val (events, oldEpoch) = ready()
        events.posted(oldEpoch, record(10))
        assertTrue(events.disconnected(oldEpoch))
        assertEquals(NotificationBaselineState.DISCONNECTED, events.baselineState(app))
        val replacement = events.connected()
        events.snapshot(replacement, record(20))
        assertEquals(NotificationEventKind.RETIRED_INGRESS, events.posted(oldEpoch, record(30)).kind)
        assertEquals(NotificationEventKind.RETIRED_INGRESS, events.removed(oldEpoch, record(20)).kind)
        assertFalse(events.disconnected(oldEpoch))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(replacement, record(20)).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(replacement, record(30, "fresh")).kind)
    }

    @Test fun lateSnapshotOfOldIngressCannotSeedReplacementIngress() {
        val events = NotificationEvents(setOf(app))
        val old = events.connected()
        val oldSnapshot = events.beginBaseline(old)!!
        val replacement = events.connected()
        assertEquals(NotificationBaselineOutcome.RETIRED_REQUEST,
            events.baseline(oldSnapshot, NotificationSnapshot.Available(listOf(record(10)))).outcome)
        assertEquals(NotificationBaselineState.AWAITING, events.baselineState(app))
        events.snapshot(replacement)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(replacement, record(10)).kind)
    }

    @Test fun newerBaselineRequestRetiresOlderCompletion() {
        val events = NotificationEvents(setOf(app))
        val epoch = events.connected()
        val old = events.beginBaseline(epoch)!!
        val current = events.beginBaseline(epoch)!!
        assertEquals(NotificationBaselineOutcome.RETIRED_REQUEST,
            events.baseline(old, NotificationSnapshot.Available(listOf(record(10)))).outcome)
        assertEquals(NotificationBaselineOutcome.APPLIED,
            events.baseline(current, NotificationSnapshot.Available(emptyList())).outcome)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationBaselineOutcome.RETIRED_REQUEST,
            events.baseline(current, NotificationSnapshot.Available(listOf(record(20)))).outcome)
    }

    @Test fun addingMembershipDoesNotResetOtherReadyMembershipHistory() {
        val (events, epoch) = ready()
        events.posted(epoch, record(10))
        events.chosenPackages(setOf(app, other))
        assertEquals(NotificationBaselineState.READY, events.baselineState(app))
        assertEquals(NotificationBaselineState.AWAITING, events.baselineState(other))
        val request = events.beginBaseline(epoch)!!
        assertEquals(setOf(other), request.packages)
        val otherRecord = record(20, packageName = other)
        events.baseline(request, NotificationSnapshot.Available(listOf(record(99), otherRecord)))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(99)).kind)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, otherRecord).kind)
    }

    @Test fun removeReaddCannotUseItsOldMembershipSnapshot() {
        val events = NotificationEvents(setOf(app, other))
        val epoch = events.connected()
        val originalRequest = events.beginBaseline(epoch)!!
        events.chosenPackages(setOf(other))
        events.chosenPackages(setOf(app, other))
        val partiallyApplied = events.baseline(originalRequest, NotificationSnapshot.Available(listOf(record(10), record(20, packageName = other))))
        assertEquals(setOf(other), partiallyApplied.appliedPackages)
        assertEquals(NotificationBaselineState.AWAITING, events.baselineState(app))
        assertEquals(NotificationEventKind.OBSERVED_BEFORE_BASELINE, events.posted(epoch, record(10)).kind)
        events.snapshot(epoch, record(10))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10)).kind)
    }

    @Test fun nonchosenRecordsDoNotLeaveHistoryAndReaddNeedsBaseline() {
        val (events, epoch) = ready()
        events.chosenPackages(emptySet())
        assertEquals(NotificationBaselineState.NOT_CHOSEN, events.baselineState(app))
        assertEquals(NotificationEventKind.NOT_CHOSEN, events.posted(epoch, record(10)).kind)
        events.chosenPackages(setOf(app))
        assertEquals(NotificationBaselineState.AWAITING, events.baselineState(app))
        events.snapshot(epoch)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10)).kind)
    }

    @Test fun offEventsStayConsumedAndOnAloneDoesNotTurnARepeatIntoANewPost() {
        val (events, epoch) = ready()
        val saved = mutableMapOf<String, Boolean>()
        val preferences = AppNotificationStore(object : AppNotificationStorage {
            override fun read(packageName: String) = saved[packageName]
            override fun write(packageName: String, enabled: Boolean) { saved[packageName] = enabled }
        })
        preferences.setEnabled(app, false)
        assertFalse(preferences.isEnabled(app))
        // Classification continues while Off; this test emits no activation or launch.
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(20)).kind)
        preferences.setEnabled(app, true)
        assertTrue(preferences.isEnabled(app))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(20)).kind)
        assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(30)).kind)
    }

    @Test fun opaqueMetadataHasNoOngoingSilentOrGroupTypeExclusion() {
        val (events, epoch) = ready()
        // These are synthetic opaque keys, not actual Android flag/adapter or device coverage.
        listOf("ongoing", "silent", "group-summary", "group-child").forEachIndexed { index, key ->
            assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(index.toLong(), key)).kind)
            assertEquals(NotificationEventKind.UPDATED_OR_REPOSTED, events.posted(epoch, record(index + 10L, key)).kind)
        }
    }

    @Test fun identicalOpaqueKeyInDifferentChosenPackagesHasSeparateState() {
        val events = NotificationEvents(setOf(app, other))
        val epoch = events.connected()
        events.snapshot(epoch)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10)).kind)
        assertEquals(NotificationEventKind.NEW_RECORD, events.posted(epoch, record(10, packageName = other)).kind)
        events.removed(epoch, record(10))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, events.posted(epoch, record(10, packageName = other)).kind)
    }

    @Test fun newClassifierBaselinesExistingRecordsWithoutDurableHistory() {
        val (events, epoch) = ready()
        events.posted(epoch, record(10))
        events.disconnected(epoch)
        assertNull(events.beginBaseline(epoch))
        val restarted = NotificationEvents(setOf(app))
        val fresh = restarted.connected()
        restarted.snapshot(fresh, record(10))
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, restarted.posted(fresh, record(10)).kind)
    }

    @Test fun ordinaryDiagnosticStringsOmitRawKeysAndTags() {
        val (events, epoch) = ready()
        val sensitiveKey = "synthetic-private-tag"
        val input = record(10, sensitiveKey)
        assertFalse(input.toString().contains(sensitiveKey))
        assertFalse(events.posted(epoch, input).toString().contains(sensitiveKey))
        assertFalse(NotificationSnapshot.Available(listOf(input)).toString().contains(sensitiveKey))
    }
}
