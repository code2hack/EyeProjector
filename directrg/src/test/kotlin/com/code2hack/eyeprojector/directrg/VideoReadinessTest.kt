package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class VideoReadinessTest {
    @Test fun existingFiveSecondFirstFrameBoundaryIsUnchanged() {
        val readiness = VideoReadiness()
        readiness.awaitInitialFrame(100)
        assertNull(readiness.expiry(5_000_000_100))
        val expiry = readiness.expiry(5_000_000_101)!!
        assertTrue(readiness.claim(expiry))
        assertFalse(readiness.claim(expiry))
        assertNull(readiness.beginReplacement())
    }

    @Test fun replacingRetiresOldDeadlineAndOldRenderEpoch() {
        val readiness = VideoReadiness()
        val old = readiness.beginReplacement()!!
        assertTrue(readiness.awaitFrame(old, 0))
        val expired = readiness.expiry(28_000_000_000)!!
        val next = readiness.beginReplacement()!!
        assertNull(readiness.expiry(28_000_000_000))
        assertFalse(readiness.claim(expired))
        assertFalse(readiness.rendered(old))
        assertTrue(readiness.awaitFrame(next, 28_000_000_000))
        assertNull(readiness.expiry(33_000_000_000))
        assertTrue(readiness.rendered(next))
        assertTrue(readiness.ready)
        assertNull(readiness.expiry(100_000_000_000))
    }
    @Test fun replacementRetiresOldDecoderFailureAndCurrentFailureRetiresReplacement() {
        val readiness = VideoReadiness()
        val old = readiness.beginReplacement()!!
        assertTrue(readiness.awaitFrame(old, 0))
        assertTrue(readiness.rendered(old))
        val next = readiness.beginReplacement()!!
        assertFalse(readiness.claimFailure(old))
        assertTrue(readiness.awaitFrame(next, 10))
        assertFalse(readiness.claimFailure(old))
        assertTrue(readiness.claimFailure(next))
        assertNull(readiness.beginReplacement())
        assertFalse(readiness.rendered(next))
    }
}
