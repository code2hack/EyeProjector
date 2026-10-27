package com.code2hack.eyeprojector.directhost.activation.snapshot

import org.junit.Assert.*
import org.junit.Test

class SnapshotWordTest {
    @Test fun readyNoneReadyNeverReusesPublicationBits() {
        assertEquals(6L, SnapshotWord.pack(1, SnapshotWord.PROJECTED))
        assertEquals(8L, SnapshotWord.pack(2, SnapshotWord.NONE))
        assertEquals(14L, SnapshotWord.pack(3, SnapshotWord.PROJECTED))
    }
    @Test fun fullUnsignedBitsArePreservedAcrossSignedJniLong() {
        val stamp = 1L shl 61
        val word = SnapshotWord.pack(stamp, SnapshotWord.LOCAL_PANEL)
        assertTrue(word < 0)
        assertEquals(stamp, SnapshotWord.stamp(word)); assertEquals(SnapshotWord.LOCAL_PANEL, SnapshotWord.mode(word))
        assertEquals(-1L, SnapshotWord.pack(SnapshotWord.MAX_STAMP, SnapshotWord.RETIRED))
        assertEquals(SnapshotWord.MAX_STAMP, SnapshotWord.stamp(-1L))
    }
}
