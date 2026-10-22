package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class VideoClockTest {
    @Test fun sourcePtsIsRelativeToFirstVideoReceiptAndNeedsNoAudio() {
        val clock = VideoClock()
        assertNull(clock.timeNs(50_000_000))
        clock.start(50_000_000, 8_000_000_000)
        assertEquals(8_000_000_000L, clock.timeNs(50_000_000))
        assertEquals(8_033_333_000L, clock.timeNs(50_033_333))
        // Later arrival jitter must not move the established media origin forward.
        clock.start(50_033_333, 8_100_000_000)
        assertEquals(8_033_333_000L, clock.timeNs(50_033_333))
        // A static UI can resume minutes later without requiring periodic audio timestamps.
        clock.start(170_000_000, 128_000_000_000)
        assertEquals(128_000_000_000L, clock.timeNs(170_000_000))
    }

    @Test fun recoveredReceiptRemovesInitialDelayWithoutFollowingLaterJitter() {
        val source = 1_968_658_357_745L
        val clock = VideoClock().apply { start(source, 10_000_000_000) }
        // Synthetic delayed-first / faster-next receipt, not the uncaptured run181 origin.
        clock.start(source + 1_000_000, 10_300_000_000)
        assertEquals(10_300_000_000L, clock.timeNs(source + 1_000_000))
        clock.start(source + 1_033_333, 10_800_000_000)
        assertEquals(10_333_333_000L, clock.timeNs(source + 1_033_333))
        // Keep source durations and the same stream reference across a static gap.
        clock.start(source + 121_000_000, 130_300_000_000)
        assertEquals(130_300_000_000L, clock.timeNs(source + 121_000_000))
    }

    @Test fun reconnectAndGeometryRestartHaveIndependentOrigins() {
        val first = VideoClock().apply { start(9_000_123_456, 1_000_000_000) }
        val replacement = VideoClock().apply { start(20_000, 20_000_000_000) }
        assertEquals(1_000_000_000L, first.timeNs(9_000_123_456))
        assertEquals(20_000_000_000L, replacement.timeNs(20_000))
    }

    @Test fun unrepresentableDurationDoesNotWrapIntoAValidPresentationTime() {
        val clock = VideoClock().apply { start(0, 10) }
        assertThrows(ArithmeticException::class.java) { clock.timeNs(Long.MAX_VALUE) }
    }

    private fun invalidSamplePreservesOrigin(origin: Long, pts: Long, receipt: Long) {
        val clock = VideoClock().apply { start(origin, 10) }
        assertThrows(ArithmeticException::class.java) { clock.start(pts, receipt) }
        assertEquals(10L, clock.timeNs(origin))
    }

    @Test fun receiptSourceSubtractionOverflowPreservesThePreviousOrigin() =
        invalidSamplePreservesOrigin(Long.MIN_VALUE, Long.MAX_VALUE, 20)

    @Test fun receiptDurationMultiplicationOverflowPreservesThePreviousOrigin() =
        invalidSamplePreservesOrigin(0, Long.MAX_VALUE, 20)

    @Test fun receiptOriginSubtractionOverflowPreservesThePreviousOrigin() =
        invalidSamplePreservesOrigin(0, 1, Long.MIN_VALUE)
}
