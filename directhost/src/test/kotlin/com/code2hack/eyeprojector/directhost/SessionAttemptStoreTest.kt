package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.AttemptDisposition
import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionPhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SessionAttemptStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun unresolvedOldBaselineCannotBindAnUnrelatedOwnerDuringRestore() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        val session = SessionController({}, { fail("Old baseline must not bind fresh C") }, initialPhase = SessionPhase.UNKNOWN,
            retainCorrelation = store::write)
        val cancel = SessionControl.Request.cancelStart("old-inactive-baseline", "old-proposal")
        assertEquals(AttemptDisposition.CANCEL_PENDING, session.command(cancel).receipt!!.disposition)
        session.restore("fresh-C", "READY")
        assertEquals(SessionPhase.AVAILABLE, session.state().phase)
        assertEquals("fresh-C", session.state().epoch)
        assertEquals(AttemptDisposition.CANCELED, session.command(cancel).receipt!!.disposition)
        assertNull(store.read().attempts.getValue("old-proposal").owner)
    }

    @Test fun recreatedFailedOwnerRetainsCancellationUntilVerifiedRelease() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        val first = SessionController({}, {}, retainCorrelation = store::write)
        val owner = first.start().epoch
        first.backend(owner, "READY")
        first.command(SessionControl.Request.observeAttempt("observation"))
        first.command(SessionControl.Request.cancelStart(owner, "observation"))
        var stops = 0
        var starts = 0
        val replacement = SessionController({ starts++ }, { stops++ }, initialPhase = SessionPhase.UNKNOWN,
            initialCorrelation = store.read(), retainCorrelation = store::write)
        replacement.restore(owner, "FAILED")
        assertEquals(owner, store.read().owner)
        assertFalse(store.read().attempts.getValue("observation").retired)
        replacement.start()
        assertEquals(0, starts)
        assertEquals(1, stops)
        replacement.backend(owner, "STOPPED")
        assertNull(store.read().owner)
        assertTrue(store.read().attempts.getValue("observation").retired)
        replacement.start()
        assertEquals(1, starts)
    }

    @Test fun failedFailureRetentionCannotAdmitAnotherOwnerBeforeActualRelease() {
        var failWrites = false
        val starts = mutableListOf<String>()
        var stops = 0
        val session = SessionController({ starts.add(it) }, { stops++ }, retainCorrelation = {
            if (failWrites) throw IOException("Synthetic failure-intent retention error")
        })
        val owner = session.start().epoch
        session.backend(owner, "READY")
        failWrites = true
        session.backend(owner, "FAILED", "ACTUAL_BACKEND_FAILURE")
        assertEquals(0, stops)
        failWrites = false
        session.start()
        assertEquals(listOf(owner), starts)
        session.state()
        assertEquals(1, stops)
        session.backend(owner, "STOPPED")
        session.start()
        assertEquals(2, starts.size)
    }

    @Test fun stopAfterUnavailableSetupCompletesKnownNoOwnerWithoutWaitingForAnAbsentBackend() {
        val session = SessionController({ fail("Setup unavailable") }, { fail("No backend was created") })
        session.start(unavailable = "AUTHORIZATION_REQUIRED")
        assertEquals(SessionPhase.INACTIVE, session.stop().phase)
        assertEquals(SessionPhase.INACTIVE, session.stop().phase)
    }

    @Test fun cancellationIsCommittedBeforeBackendDispatchAndRetryableIfCommitFails() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        var failWrites = false
        var stops = 0
        val session = SessionController({}, { stops++ }, retainCorrelation = {
            if (failWrites) throw IOException("Synthetic retention failure")
            store.write(it)
        })
        val owner = session.start().epoch
        session.backend(owner, "READY")
        session.command(SessionControl.Request.observeAttempt("observation"))
        failWrites = true
        val failed = session.command(SessionControl.Request.cancelStart(owner, "observation"))
        assertEquals(AttemptDisposition.RETRYABLE, failed.receipt!!.disposition)
        assertEquals(SessionPhase.AVAILABLE, failed.phase)
        assertEquals(0, stops)
        assertFalse(store.read().attempts.getValue("observation").canceled)
        failWrites = false
        val accepted = session.command(SessionControl.Request.cancelStart(owner, "observation"))
        assertEquals(AttemptDisposition.CANCEL_PENDING, accepted.receipt!!.disposition)
        assertTrue(store.read().attempts.getValue("observation").canceled)
        assertEquals(1, stops)
        session.backend(owner, "STOPPED")
        assertEquals(AttemptDisposition.CANCELED,
            session.command(SessionControl.Request.cancelStart(owner, "observation")).receipt!!.disposition)
    }

    @Test fun unresolvedCancellationSurvivesAtomicStoreAndReissuesAfterOwnerRestoration() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        val first = SessionController({}, { fail("No confirmed owner yet") }, initialPhase = SessionPhase.UNKNOWN,
            retainCorrelation = store::write)
        val pending = first.command(SessionControl.Request.cancelStart("", "before-status"))
        assertEquals(SessionPhase.UNKNOWN, pending.phase)
        assertEquals(AttemptDisposition.CANCEL_PENDING, pending.receipt!!.disposition)
        assertTrue(store.read().attempts.getValue("before-status").unresolved)
        var stops = 0
        val replacement = SessionController({}, { stops++ }, initialPhase = SessionPhase.UNKNOWN,
            initialCorrelation = store.read(), retainCorrelation = store::write)
        replacement.restore("existing-owner", "READY")
        assertEquals(SessionPhase.STOPPING, replacement.state().phase)
        assertEquals(1, stops)
        replacement.restore("existing-owner", "READY")
        replacement.command(SessionControl.Request.observeAttempt("before-status"))
        assertEquals(1, stops)
    }

    @Test fun confirmedNoOwnerCancellationNeverBindsLaterPhoneOwnerAfterRestart() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        val first = SessionController({}, {}, retainCorrelation = store::write)
        assertEquals(AttemptDisposition.CANCELED,
            first.command(SessionControl.Request.cancelStart("", "before-status")).receipt!!.disposition)
        val fresh = first.start().epoch
        first.backend(fresh, "READY")
        val replacement = SessionController({}, { fail("Must not stop fresh owner") }, initialPhase = SessionPhase.UNKNOWN,
            initialCorrelation = store.read(), retainCorrelation = store::write)
        replacement.restore(fresh, "READY")
        val late = replacement.command(SessionControl.Request.observeAttempt("before-status"))
        assertEquals(fresh, late.epoch)
        assertEquals(AttemptDisposition.CANCELED, late.receipt!!.disposition)
        assertNull(late.receipt!!.owner)
    }

    @Test fun restoredDifferentOwnerRetiresPriorIntentWithoutChasingFreshOwner() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        val first = SessionController({}, {}, retainCorrelation = store::write)
        val old = first.start().epoch
        first.backend(old, "READY")
        first.command(SessionControl.Request.observeAttempt("observation"))
        first.command(SessionControl.Request.cancelStart(old, "observation"))
        val replacement = SessionController({}, { fail("Must not chase replacement") }, initialPhase = SessionPhase.UNKNOWN,
            initialCorrelation = store.read(), retainCorrelation = store::write)
        replacement.restore("fresh-owner", "READY")
        assertEquals(SessionPhase.AVAILABLE, replacement.state().phase)
        assertEquals(AttemptDisposition.CANCELED,
            replacement.command(SessionControl.Request.cancelStart(old, "observation")).receipt!!.disposition)
    }

    @Test fun failedRetentionDoesNotInventStartedResourcesOrStrandLaterStart() {
        var writes = 0
        var starts = 0
        val controller = SessionController({ starts++ }, {}, retainCorrelation = {
            if (writes++ == 0) throw IOException("Synthetic app-private write failure")
        })
        assertEquals(SessionPhase.FAILED, controller.start().phase)
        assertEquals("SESSION_CONTROL_STATE_UNAVAILABLE", controller.state().detail)
        assertEquals(0, starts)
        assertEquals(SessionPhase.STARTING, controller.start().phase)
        assertEquals(1, starts)
    }

    @Test fun correlationSurvivesPhoneProcessRestartUntilActualOwnerRelease() {
        restartCorrelation(false)
    }

    @Test fun activeOwnerBaselineRemainsCorrelatedWhileRestorationIsUnknown() {
        restartCorrelation(true)
    }

    private fun restartCorrelation(activeBaseline: Boolean) {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        var sequence = 0
        val first = SessionController({}, {}, { "epoch-${sequence++}" }, retainCorrelation = store::write)
        val initial = first.state().epoch
        val owner = first.start().epoch
        first.backend(owner, "READY")
        val baseline = if (activeBaseline) owner else initial
        first.command(SessionControl.Request(SessionControl.Operation.START, baseline, "proposal"))
        assertEquals(owner, store.read().attempts.getValue("proposal").owner)
        var stops = 0
        val replacement = SessionController({ fail("must reuse actual owner") }, { stops++ },
            initialPhase = SessionPhase.UNKNOWN, initialCorrelation = store.read(), retainCorrelation = store::write)
        assertEquals(SessionPhase.UNKNOWN, replacement.command(SessionControl.Request.cancelStart(baseline, "proposal")).phase)
        replacement.restore(owner, "READY")
        assertEquals(SessionPhase.STOPPING, replacement.command(SessionControl.Request.cancelStart(baseline, "proposal")).phase)
        assertEquals(1, stops)
        replacement.backend(owner, "STOPPED")
        assertTrue(store.read().attempts.values.all { it.retired && it.owner == null })
        assertNull(store.read().owner)
        assertNull(store.read().baseline)
    }

    @Test fun canceledBeforeAdmissionSurvivesStoreRoundTripAndCannotBindLaterPhoneStart() {
        val store = SessionAttemptStore(File(directory.root, "attempts.properties"))
        var sequence = 0
        val first = SessionController({}, {}, { "epoch-${sequence++}" }, retainCorrelation = store::write)
        val baseline = first.state().epoch
        first.command(SessionControl.Request.cancelStart(baseline, "proposal"))
        val fresh = first.start().epoch
        first.backend(fresh, "READY")
        assertNull(store.read().attempts.getValue("proposal").owner)
        val replacement = SessionController({ fail("must not start again") }, { fail("must not stop fresh owner") },
            initialPhase = SessionPhase.UNKNOWN, initialCorrelation = store.read(), retainCorrelation = store::write)
        replacement.restore(fresh, "READY")
        replacement.command(SessionControl.Request.cancelStart(baseline, "proposal"))
        replacement.command(SessionControl.Request(SessionControl.Operation.START, baseline, "proposal"))
        assertEquals(fresh, replacement.state().epoch)
        assertEquals(SessionPhase.AVAILABLE, replacement.state().phase)
    }
}
