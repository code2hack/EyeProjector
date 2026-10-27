package com.code2hack.eyeprojector.directhost.camera

import com.code2hack.eyeprojector.protocol.CameraEndReason
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prepared host controls for the actual target admission/fence state used by the EGL lane.
 * Any denotes a borrowed wrapper identity, never a replacement Android Surface or renderer.
 * These controls do not claim native EGL, decoder, SurfaceTexture or imagery results.
 */
class PhoneCameraSurfaceFanoutTest {
    private fun bound(): Pair<PhoneTargetUseState<Any>, Any> {
        val state = PhoneTargetUseState<Any>()
        val borrowed = Any()
        state.finishBinding(true)
        assertTrue(state.installBorrowed(borrowed))
        return state to borrowed
    }

    @Test fun stopBeforeBindOutcomeClosesAdmissionButCannotInventAnUnboundProducerFence() {
        val state = PhoneTargetUseState<Any>()
        state.closeAdmission(CameraEndReason.ORIGINAL_INPUT_CLOSED)
        assertFalse(state.claimFence())
        state.finishBinding(false)
        assertFalse(state.installBorrowed(Any()))
        assertNull(state.capture())
        assertFalse(state.claimFence())
        assertTrue(state.noBoundDuty())
        assertFalse(state.fenced)
        assertFalse(state.knownNoNativeUse)
    }

    @Test fun successfulBindAfterImmediateStopHasOneKnownNoNativeUseFence() {
        val state = PhoneTargetUseState<Any>()
        state.closeAdmission(CameraEndReason.PROJECTION_CLOSED)
        state.finishBinding(true)
        assertFalse(state.installBorrowed(Any()))
        assertNull(state.capture())
        assertTrue(state.claimFence())
        assertTrue(state.knownNoNativeUse)
        assertFalse(state.windowAttempted)
        assertFalse(state.finishReturned)
        assertFalse(state.pbufferRestored)
        assertFalse(state.destroyReturned)
        state.fenceOutcome(true, null)
        assertTrue(state.fenced)
        assertFalse(state.claimFence())
    }

    @Test fun closingCaptureRaceRetainsTheActualCapturedCallUntilItsReturn() {
        val (state, borrowed) = bound()
        val captured = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val nativeLane = Thread {
            try {
                assertSame(borrowed, state.capture())
                calls.incrementAndGet()
                captured.countDown()
                assertTrue(allowReturn.await(3, TimeUnit.SECONDS))
                state.returned()
            } finally { returned.countDown() }
        }
        nativeLane.start()
        try {
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            state.closeAdmission(CameraEndReason.ORIGINAL_INPUT_CLOSED)
            assertNull(state.capture())
            assertFalse(state.claimFence())
            assertEquals(1, synchronized(state.gate) { state.calls })
        } finally {
            allowReturn.countDown()
            try { assertTrue(returned.await(3, TimeUnit.SECONDS)) }
            finally { nativeLane.join(3000) }
        }
        assertFalse(nativeLane.isAlive)
        assertEquals(1, calls.get())
        assertTrue(state.claimFence()) // No native window call occurred in this control.
    }

    @Test fun originalWindowNeedsFinishThenPbufferThenSuccessfulDestructionBeforeFence() {
        val (state, _) = bound()
        assertNotNull(state.capture())
        synchronized(state.gate) { state.windowAttempted = true; state.windowCreated = true }
        state.closeAdmission(CameraEndReason.ORIGINAL_INPUT_CLOSED)
        state.returned()
        assertFalse(state.claimFence())
        synchronized(state.gate) { state.finishReturned = true }
        assertFalse(state.claimFence())
        synchronized(state.gate) { state.pbufferRestored = true }
        assertFalse(state.claimFence())
        synchronized(state.gate) { state.destroyReturned = true }
        assertTrue(state.claimFence())
        assertFalse(state.knownNoNativeUse)
        state.fenceOutcome(true, null)
        assertTrue(state.fenced)
        assertFalse(state.claimFence())
    }

    @Test fun attemptedWindowWithoutReturnedHandleRetainsItsUncertainNativeDuty() {
        val (state, _) = bound()
        assertNotNull(state.capture())
        synchronized(state.gate) { state.windowAttempted = true }
        val primary = UnsupportedOperationException("actual window creation did not return")
        state.failed(primary)
        state.closeAdmission(CameraEndReason.LOCAL_RELEASE_FAILURE)
        state.returned()
        assertFalse(state.claimFence())
        assertSame(primary, state.failure)
        assertFalse(state.knownNoNativeUse)
        assertFalse(state.fenced)
    }

    @Test fun failedNativeCleanupDoesNotBecomeClearBecauseLaterStepsReturned() {
        val (state, _) = bound()
        synchronized(state.gate) { state.windowAttempted = true; state.windowCreated = true }
        state.closeAdmission(CameraEndReason.ORIGINAL_INPUT_CLOSED)
        val finishFailure = IllegalStateException("glFinish failed")
        state.failed(finishFailure)
        synchronized(state.gate) { state.pbufferRestored = true; state.destroyReturned = true }
        assertFalse(state.claimFence())
        assertSame(finishFailure, state.failure)
        assertFalse(state.fenced)
    }

    @Test fun primaryDrawFailureIsRetainedWhenRealTeardownSuccessfullyEndsUse() {
        val (state, _) = bound()
        synchronized(state.gate) { state.windowAttempted = true; state.windowCreated = true }
        val primary = IllegalStateException("draw failed")
        state.failed(primary)
        state.closeAdmission(CameraEndReason.LOCAL_RELEASE_FAILURE)
        synchronized(state.gate) {
            state.finishReturned = true; state.pbufferRestored = true; state.destroyReturned = true
        }
        assertTrue(state.claimFence())
        state.fenceOutcome(true, null)
        assertTrue(state.fenced)
        assertSame(primary, state.failure)
    }

    @Test fun rejectedExactLeaseFenceCannotBeReportedAsProducerUseStoppedOrRepeated() {
        val (state, _) = bound()
        state.closeAdmission(CameraEndReason.ORIGINAL_RETIRED)
        assertTrue(state.claimFence())
        val rejected = IllegalStateException("original lease rejected the fact")
        state.fenceOutcome(false, rejected)
        assertFalse(state.fenced)
        assertSame(rejected, state.failure)
        assertFalse(state.claimFence())
    }

    @Test fun equalValuedWrapperCannotReplaceOriginalBorrowedIdentity() {
        data class Wrapper(val number: Int)
        val state = PhoneTargetUseState<Wrapper>()
        state.finishBinding(true)
        val original = Wrapper(4)
        assertTrue(state.installBorrowed(original))
        assertFalse(state.installBorrowed(Wrapper(4)))
        assertSame(original, state.capture())
        state.returned()
        state.closeAdmission(CameraEndReason.REPLACED)
        assertNull(state.capture())
    }

    @Test fun enrollmentWakeRunsOutsideAdmissionLockAndLateEnrollmentSeesClosedState() {
        val (state, _) = bound()
        state.closeAdmission(CameraEndReason.ORIGINAL_INPUT_CLOSED)
        val observed = AtomicInteger()
        synchronized(state.gate) {
            state.wake = {
                assertFalse(Thread.holdsLock(state.gate))
                assertTrue(synchronized(state.gate) { state.closed })
                observed.incrementAndGet()
            }
        }
        state.finishBinding(true)
        state.closeAdmission(CameraEndReason.PROJECTION_CLOSED)
        assertEquals(2, observed.get())
        assertEquals(CameraEndReason.ORIGINAL_INPUT_CLOSED, state.reason)
    }
}
