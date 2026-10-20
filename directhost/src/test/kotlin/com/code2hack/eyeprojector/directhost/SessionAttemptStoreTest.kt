package com.code2hack.eyeprojector.directhost

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
        assertTrue(store.read().attempts.isEmpty())
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
