package com.code2hack.eyeprojector.directhost.notifications

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class NotificationIngressRuntimeTest {
    private val app = "example.chosen"
    private fun record(time: Long = 10, pkg: String = app) = NotificationRecord(pkg, "synthetic-key", time)
    private class Fixture {
        var chosen = setOf("example.chosen")
        var chosenFails = false
        var approval = NotificationAccessApproval.DENIED
        var approvalFails = false
        val choiceObservers = linkedSetOf<() -> Unit>()
        data class Query(val work: () -> NotificationSnapshot, val complete: (NotificationSnapshot) -> Unit)
        val queries = ArrayDeque<Query>()
        val runtime = PhoneNotificationRuntime(
            { if (chosenFails) error("synthetic unavailable") else chosen },
            { observer -> choiceObservers.add(observer); Closeable { choiceObservers.remove(observer) } },
            { if (approvalFails) error("synthetic unavailable") else approval },
            { work, complete -> queries.addLast(Query(work, complete)) },
            { it() },
        )
        fun connect(snapshot: (Set<String>) -> NotificationSnapshot = { NotificationSnapshot.Available(emptyList()) }): NotificationListenerConnection =
            NotificationListenerConnection().also { runtime.connected(it, snapshot) }
        fun finish() { queries.removeFirst().let { it.complete(it.work()) } }
        fun changed() { choiceObservers.toList().forEach { it() } }
    }

    @Test fun approvalConnectionAndReturnedBaselineAreSeparateFacts() {
        val f = Fixture()
        f.approval = NotificationAccessApproval.GRANTED
        f.runtime.refresh()
        assertFalse(f.runtime.status().connected)
        val connection = f.connect()
        assertTrue(f.runtime.status().connected)
        assertEquals(NotificationListenerBaseline.INITIALIZING, f.runtime.status().baseline)
        f.finish()
        // Public returned data can be empty even after a masked internal Android failure.
        assertEquals(NotificationListenerBaseline.OBSERVED, f.runtime.status().baseline)
        f.approval = NotificationAccessApproval.DENIED
        f.runtime.refresh()
        assertEquals(NotificationAccessApproval.DENIED, f.runtime.status().approval)
        assertTrue(f.runtime.status().connected) // Callback connection is not inferred from approval.
        f.runtime.disconnected(connection)
        assertFalse(f.runtime.status().connected)
        assertTrue(f.choiceObservers.isEmpty())
    }

    @Test fun unavailableDoesNotBecomeEmptyOrAnAutomaticRetryLoop() {
        val f = Fixture()
        val connection = f.connect { NotificationSnapshot.Unavailable }
        f.finish()
        assertEquals(NotificationListenerBaseline.UNAVAILABLE, f.runtime.status().baseline)
        assertTrue(f.queries.isEmpty())
        f.runtime.posted(connection, "example.chosen") { record() }
        assertTrue(f.queries.isEmpty())
        f.runtime.refresh() // Named external trigger only.
        assertEquals(1, f.queries.size)
    }

    @Test fun snapshotThrowIsObservableUnavailableAndRetiredQueuedReadIsSkipped() {
        val f = Fixture()
        f.connect { error("synthetic query failure") }
        f.finish()
        assertEquals(NotificationListenerBaseline.UNAVAILABLE, f.runtime.status().baseline)
        var called = false
        val old = f.connect { called = true; NotificationSnapshot.Available(emptyList()) }
        f.runtime.disconnected(old)
        f.finish()
        assertFalse(called)
        assertFalse(f.runtime.status().connected)
    }

    @Test fun callbackClassifiesSynchronouslyBeforeHeldAsyncBaselineOrLaterAppQueue() {
        val f = Fixture()
        val connection = f.connect()
        val observed = mutableListOf<NotificationIngressObservation>()
        f.runtime.observeEvents { observed.add(it) }
        val first = f.runtime.posted(connection, app) { record() }!!
        assertEquals(NotificationEventKind.OBSERVED_BEFORE_BASELINE, first.event.kind)
        assertSame(first, observed.single())
        assertEquals(1, f.queries.size)
        f.finish()
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, f.runtime.posted(connection, app) { record() }!!.event.kind)
    }

    @Test fun firstReceiptRefreshesMembershipEvenWhenChoiceObserverIsDelayed() {
        val f = Fixture()
        val connection = f.connect()
        f.finish()
        f.chosen = emptySet() // Do not deliver the delayed preference observer yet.
        var extracted = false
        assertNull(f.runtime.posted(connection, app) { extracted = true; record() })
        assertFalse(extracted)
        f.changed()
        assertEquals(NotificationListenerBaseline.NO_CHOSEN_APPS, f.runtime.status().baseline)
    }

    @Test fun removeReaddWhileBaselineRunsCannotSeedReplacementMembership() {
        val f = Fixture()
        val connection = f.connect { NotificationSnapshot.Available(listOf(record())) }
        f.chosen = emptySet(); f.changed()
        f.chosen = setOf(app); f.changed()
        val old = f.queries.removeFirst()
        old.complete(old.work())
        assertEquals(NotificationListenerBaseline.INITIALIZING, f.runtime.status().baseline)
        assertEquals(1, f.queries.size)
        assertEquals(NotificationEventKind.OBSERVED_BEFORE_BASELINE, f.runtime.posted(connection, app) { record() }!!.event.kind)
        f.finish()
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, f.runtime.posted(connection, app) { record() }!!.event.kind)
    }

    @Test fun additionDuringQueryRetainsItsExternalBaselineTrigger() {
        val f = Fixture()
        val queried = mutableListOf<Set<String>>()
        f.connect { packages -> queried.add(packages); NotificationSnapshot.Available(emptyList()) }
        f.chosen = setOf(app, "example.added"); f.changed()
        f.finish()
        assertEquals(setOf(app), queried[0])
        assertEquals(1, f.queries.size)
        f.finish()
        assertEquals(setOf("example.added"), queried[1])
        assertEquals(NotificationListenerBaseline.OBSERVED, f.runtime.status().baseline)
    }

    @Test fun lateOldBaselineTeardownAndEventsCannotMutateReplacementIngress() {
        val f = Fixture()
        val old = f.connect()
        val pendingOld = f.queries.removeFirst()
        val replacement = f.connect()
        assertFalse(old.isActive())
        f.finish()
        pendingOld.complete(NotificationSnapshot.Available(listOf(record())))
        f.runtime.disconnected(old)
        assertNull(f.runtime.posted(old, app) { record() })
        assertNull(f.runtime.removed(old, app) { record() })
        assertTrue(f.runtime.status().connected)
        assertEquals(NotificationEventKind.NEW_RECORD, f.runtime.posted(replacement, app) { record() }!!.event.kind)
        assertEquals(1, f.choiceObservers.size)
    }

    @Test fun metadataGetterReentryDoesNotReclassifyIntoReplacementMembership() {
        val f = Fixture()
        val connection = f.connect(); f.finish()
        val result = f.runtime.posted(connection, app) {
            f.chosen = emptySet(); f.changed()
            f.chosen = setOf(app); f.changed()
            record()
        }
        assertNull(result)
        assertEquals(NotificationListenerBaseline.INITIALIZING, f.runtime.status().baseline)
    }

    @Test fun eventObserverReentryPreservesOriginalReceiptAndDoesNotClearNewQuery() {
        val f = Fixture()
        val original = f.connect(); f.finish()
        var newer: NotificationListenerConnection? = null
        f.runtime.observeEvents { newer = f.connect() }
        val received = f.runtime.posted(original, app) { record() }!!
        assertSame(original, received.scope.connection)
        assertNotSame(newer, received.scope.connection)
        assertEquals(1, f.queries.size)
        assertEquals(NotificationListenerBaseline.INITIALIZING, f.runtime.status().baseline)
    }

    @Test fun statusObserverCanReplaceIngressWithoutLateTeardownClobber() {
        val f = Fixture()
        val original = f.connect(); f.finish()
        var replace = true
        val registration = f.runtime.observeStatus {
            if (replace) { replace = false; f.connect() }
        }
        f.runtime.refresh()
        f.runtime.disconnected(original)
        assertTrue(f.runtime.status().connected)
        assertEquals(1, f.choiceObservers.size)
        registration.close()
    }

    @Test fun chosenAndApprovalFailureAreNotFabricatedEmptyOrOff() {
        val f = Fixture()
        val connection = f.connect(); f.finish()
        f.chosenFails = true; f.approvalFails = true
        f.runtime.refresh()
        assertFalse(f.runtime.status().chosenAvailable)
        assertEquals(NotificationAccessApproval.UNKNOWN, f.runtime.status().approval)
        assertEquals(NotificationListenerBaseline.UNAVAILABLE, f.runtime.status().baseline)
        assertNull(f.runtime.posted(connection, app) { record() })
        f.chosenFails = false; f.approvalFails = false
        assertEquals(NotificationEventKind.NEW_RECORD, f.runtime.posted(connection, app) { record() }!!.event.kind)
    }

    @Test fun offIsNotAnIngressFilterAndNoActivationIsEmitted() {
        val f = Fixture()
        val connection = f.connect(); f.finish()
        // Runtime intentionally has no preference/activation dependency; all chosen records stay consumed.
        assertEquals(NotificationEventKind.NEW_RECORD, f.runtime.posted(connection, app) { record() }!!.event.kind)
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, f.runtime.posted(connection, app) { record() }!!.event.kind)
        assertEquals(NotificationEventKind.REMOVED_CURRENT, f.runtime.removed(connection, app) { record() }!!.event.kind)
    }

    @Test fun disconnectRestartBaselinesWithoutReconstructingPostedEvents() {
        val f = Fixture()
        val first = f.connect(); f.finish()
        f.runtime.posted(first, app) { record() }
        f.runtime.disconnected(first)
        val restarted = Fixture()
        val seen = mutableListOf<NotificationIngressObservation>()
        restarted.runtime.observeEvents { seen.add(it) }
        val fresh = restarted.connect { NotificationSnapshot.Available(listOf(record())) }
        restarted.finish()
        assertTrue(seen.isEmpty())
        assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, restarted.runtime.posted(fresh, app) { record() }!!.event.kind)
    }

    @Test fun panelObservationCloseDoesNotRetireIngressOrOtherObservers() {
        val f = Fixture()
        val connection = f.connect(); f.finish()
        var one = 0; var two = 0
        val first = f.runtime.observeStatus { one++ }
        f.runtime.observeStatus { two++ }
        first.close(); f.runtime.refresh()
        assertEquals(0, one); assertEquals(1, two)
        assertTrue(connection.isActive())
        assertTrue(f.runtime.status().connected)
    }

    @Test fun choiceRegistrationCallbackBeforeReturnClosesOnlyItsOriginalSubscription() {
        lateinit var owner: PhoneNotificationRuntime
        val old = NotificationListenerConnection()
        val replacement = NotificationListenerConnection()
        var registrations = 0
        val closed = mutableListOf<Int>()
        val pending = mutableListOf<() -> Unit>()
        owner = PhoneNotificationRuntime({ setOf(app) }, {
            val originalRegistration = ++registrations
            if (originalRegistration == 1) owner.connected(replacement) { NotificationSnapshot.Available(emptyList()) }
            Closeable { closed.add(originalRegistration) }
        }, { NotificationAccessApproval.GRANTED }, { work, complete -> pending.add { complete(work()) } }, { it() })
        owner.connected(old) { NotificationSnapshot.Available(emptyList()) }
        assertFalse(old.isActive())
        assertTrue(replacement.isActive())
        assertEquals(listOf(1), closed)
        assertEquals(1, pending.size)
        pending.single()()
        assertEquals(NotificationListenerBaseline.OBSERVED, owner.status().baseline)
        owner.disconnected(old)
        assertEquals(listOf(1), closed)
        owner.disconnected(replacement)
        assertEquals(listOf(1, 2), closed)
    }

    @Test fun inlineSnapshotCompletionAndSchedulingFailureLeaveNoPendingQueryLoop() {
        var runs = 0
        val owner = PhoneNotificationRuntime({ setOf(app) }, { Closeable {} },
            { NotificationAccessApproval.GRANTED }, { work, complete -> runs++; complete(work()) }, { it() })
        owner.connected(NotificationListenerConnection()) { NotificationSnapshot.Available(emptyList()) }
        assertEquals(1, runs)
        assertEquals(NotificationListenerBaseline.OBSERVED, owner.status().baseline)
        val failing = PhoneNotificationRuntime({ setOf(app) }, { Closeable {} },
            { NotificationAccessApproval.GRANTED }, { _, _ -> runs++; error("synthetic scheduling failure") }, { it() })
        failing.connected(NotificationListenerConnection()) { NotificationSnapshot.Available(emptyList()) }
        assertEquals(2, runs)
        assertEquals(NotificationListenerBaseline.UNAVAILABLE, failing.status().baseline)
        failing.refresh()
        assertEquals(3, runs)
    }
}
