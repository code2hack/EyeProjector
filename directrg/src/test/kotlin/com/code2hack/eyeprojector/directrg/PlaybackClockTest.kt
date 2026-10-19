package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class PlaybackClockTest {
    @Test fun mapsSourcePtsUsingOnlyRgTimestampAndSamplePosition() {
        val clock = PlaybackClock()
        clock.append(0, 9_000_000_000L)
        assertNull(clock.ptsAt(1_000_000_000L))
        assertTrue(clock.update(4800, 1_000_000_000L, 1_000_000_000L))
        assertEquals(9_000_150_000L, clock.ptsAt(1_050_000_000L))
        assertEquals(1_100_000_000L, clock.videoTimeNs(9_000_200_000L, 1_050_000_000L))
        assertNull(clock.ptsAt(1_600_000_000L))
    }
    @Test fun rejectsStaleFutureAndRegressingTimestamps() {
        val clock = PlaybackClock(); clock.append(0, 5_000_000)
        assertFalse(clock.update(0, 2_000_000_000L, 1_000_000_000L))
        assertFalse(clock.update(0, 1, 3_000_000_000L))
        assertFalse(clock.update(0, 1_000_000_000L, 1_600_000_000L))
        assertTrue(clock.update(100, 1_000_000_000L, 1_001_000_000L))
        assertFalse(clock.update(99, 1_001_000_000L, 1_002_000_000L))
    }
    @Test fun latestAnchorRetainsRealPtsDiscontinuityWithoutClockSubtraction() {
        val clock = PlaybackClock()
        clock.append(0, 0); clock.append(960, 9_000_000)
        clock.update(960, 1_000_000_000L, 1_000_000_000L)
        assertEquals(9_000_000L, clock.ptsAt(1_000_000_000L))
    }
}
