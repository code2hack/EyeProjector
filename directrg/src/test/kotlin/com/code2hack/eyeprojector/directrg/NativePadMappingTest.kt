package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class NativePadMappingTest {
    @Test fun onlyTheIdentifiedPadBackCanRepresentATapPair() {
        assertEquals(PadGestureRecognizer.Key.DOUBLE, nativePadKey("ROKID,PSOC-TP-R", 0x101, 4))
        assertNull(nativePadKey("Virtual", 0x101, 4))
        assertNull(nativePadKey("ROKID,PSOC-TP-R", 0, 4))
        assertEquals(PadGestureRecognizer.Key.FORWARD_TAIL, nativePadKey("ROKID,PSOC-TP-R", 0x101, 20))
        assertEquals(PadGestureRecognizer.Key.BACKWARD_TAIL, nativePadKey("ROKID,PSOC-TP-R", 0x101, 19))
        // The retained capture did not deliver faithful key186 held edges to the Activity.
        assertNull(nativePadKey("ROKID,PSOC-TP-R", 0x101, 186))
    }
}
