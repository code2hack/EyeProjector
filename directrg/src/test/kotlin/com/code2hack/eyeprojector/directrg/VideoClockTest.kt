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
        // Network jitter on later packets must not move the established media origin.
        clock.start(50_033_333, 8_100_000_000)
        assertEquals(8_033_333_000L, clock.timeNs(50_033_333))
        // A static UI can resume minutes later without requiring periodic audio timestamps.
        assertEquals(128_000_000_000L, clock.timeNs(170_000_000))
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
}
