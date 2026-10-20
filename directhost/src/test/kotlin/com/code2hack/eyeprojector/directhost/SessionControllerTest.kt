package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.SessionPhase
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionControllerTest {
    private var sequence = 0
    private val starts = mutableListOf<String>()
    private var stops = 0
    private val controller = SessionController({ starts.add(it) }, { stops++ }, { "epoch-${sequence++}" })

    @Test fun concurrentPhoneAndGlassesStartsCreateOneOwner() {
        val creates = AtomicInteger()
        val session = SessionController({ creates.incrementAndGet() }, {})
        val initial = session.state().epoch
        val go = CountDownLatch(1)
        val done = CountDownLatch(20)
        repeat(20) {
            Thread { go.await(); session.start(initial); done.countDown() }.start()
        }
        go.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, creates.get())
        assertEquals(SessionPhase.STARTING, session.state().phase)
    }

    @Test fun listenerReadinessNeverClaimsRenderedStreaming() {
        val epoch = controller.start().epoch
        controller.backend(epoch, "STARTING")
        assertEquals(SessionPhase.STARTING, controller.state().phase)
        controller.backend(epoch, "READY")
        assertEquals(SessionPhase.AVAILABLE, controller.state().phase)
        controller.receiverConnecting(epoch)
        assertEquals(SessionPhase.CONNECTING, controller.state().phase)
        controller.receiverRendered(epoch)
        assertEquals(SessionPhase.STREAMING, controller.state().phase)
    }

    @Test fun stopDuringStartupInvalidatesLateCommandsAndAwaitsRelease() {
        val epoch = controller.start().epoch
        controller.stop(epoch)
        controller.backend(epoch, "FAILED")
        controller.backend(epoch, "READY")
        controller.receiverRendered(epoch)
        controller.start()
        assertEquals(SessionPhase.STOPPING, controller.state().phase)
        assertEquals(1, starts.size)
        controller.backend(epoch, "STOPPED")
        assertEquals(SessionPhase.INACTIVE, controller.state().phase)
        controller.start(epoch)
        assertEquals(SessionPhase.INACTIVE, controller.state().phase)
        assertEquals(1, starts.size)
        assertEquals(1, stops)
    }

    @Test fun oldEpochCannotStopOrReportIntoFreshResources() {
        val old = controller.start().epoch
        controller.backend(old, "READY")
        controller.stop(old)
        controller.backend(old, "STOPPED")
        val fresh = controller.start().epoch
        assertNotEquals(old, fresh)
        controller.backend(fresh, "READY")
        controller.backend(old, "STOPPED")
        controller.backend(old, "FAILED")
        controller.stop(old)
        controller.receiverRendered(old)
        assertEquals(SessionPhase.AVAILABLE, controller.state().phase)
        assertEquals(fresh, controller.state().epoch)
        assertEquals(1, stops)
    }

    @Test fun naturalSessionEndAllowsAnExplicitFreshStart() {
        val old = controller.start().epoch
        controller.backend(old, "READY")
        controller.receiverRendered(old)
        controller.backend(old, "STOPPED")
        assertEquals(SessionPhase.INACTIVE, controller.state().phase)
        controller.start(old)
        assertEquals(1, starts.size)
        assertNotEquals(old, controller.start().epoch)
        assertEquals(2, starts.size)
    }

    @Test fun failureReleasesBeforeAllowingRestartAndRemainsVisible() {
        val epoch = controller.start().epoch
        controller.backend(epoch, "STARTING")
        controller.backend(epoch, "FAILED", "VIDEO_FAILED")
        assertEquals(SessionPhase.FAILED, controller.state().phase)
        controller.start()
        assertEquals(1, starts.size)
        controller.backend(epoch, "STOPPING")
        assertEquals(SessionPhase.FAILED, controller.state().phase)
        controller.backend(epoch, "STOPPED")
        assertEquals(SessionPhase.FAILED, controller.state().phase)
        assertEquals("VIDEO_FAILED", controller.state().detail)
        controller.start()
        assertEquals(2, starts.size)
    }

    @Test fun appProcessReattachmentAdoptsExistingBackendGeneration() {
        controller.restore("owned", "STREAMING")
        assertEquals(SessionPhase.CONNECTING, controller.state().phase)
        controller.receiverRendered("owned")
        assertEquals(SessionPhase.STREAMING, controller.state().phase)
        controller.start()
        assertTrue(starts.isEmpty())
        controller.stop("owned")
        controller.backend("owned", "STOPPED")
        assertEquals(SessionPhase.INACTIVE, controller.state().phase)
    }

    @Test fun unavailableSetupDoesNotDispatchAnOrphanStart() {
        controller.start(unavailable = "AUTHORIZATION_REQUIRED")
        assertEquals(SessionPhase.FAILED, controller.state().phase)
        assertTrue(starts.isEmpty())
    }

    @Test fun unknownStateWaitsForActualOwnerAndStopCanReconcileAnAbsentOwner() {
        val session = SessionController({ fail("must resolve actual ownership first") }, {}, initialPhase = SessionPhase.UNKNOWN)
        assertEquals(SessionPhase.UNKNOWN, session.start().phase)
        session.stop()
        session.restore("", "STOPPED")
        assertEquals(SessionPhase.INACTIVE, session.state().phase)
    }

    @Test fun queuedStartRejectionRetiresOnlyItsRequestedEpoch() {
        val epoch = controller.start().epoch
        controller.rejected("old", "START_SETUP_UNAVAILABLE")
        assertEquals(SessionPhase.STARTING, controller.state().phase)
        controller.rejected(epoch, "START_SETUP_UNAVAILABLE")
        assertEquals(SessionPhase.FAILED, controller.state().phase)
        controller.stop(); controller.restore("", "STOPPED")
        assertEquals(SessionPhase.INACTIVE, controller.state().phase)
    }

    @Test fun lostStatusStopsClaimingStreamingAndConfirmedDeathFailsAndReleases() {
        val epoch = controller.start().epoch
        controller.backend(epoch, "READY"); controller.receiverRendered(epoch)
        controller.backend(epoch, "UNKNOWN", "SERVICE_UNREACHABLE")
        assertEquals(SessionPhase.UNKNOWN, controller.state().phase)
        controller.backend(epoch, "STREAMING")
        controller.receiverRendered(epoch)
        assertEquals(SessionPhase.STREAMING, controller.state().phase)
        controller.backend(epoch, "UNKNOWN", "SERVICE_DIED")
        assertEquals(SessionPhase.FAILED, controller.state().phase)
        assertEquals(1, stops)
    }
}
