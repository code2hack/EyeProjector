package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

class TouchTest {
    private val target = Tap(123, 234, Geometry(480, 640))
    @Test fun allPhasesRoundTripWithoutCollapsingTheContact() {
        for (phase in Touch.Phase.entries) {
            val event = Touch(phase, 87654321L, target)
            assertEquals(25, event.bytes().size)
            assertEquals(event, Touch.from(event.bytes()))
            val input = DataInputStream(Scrcpy.touch(event).inputStream())
            assertEquals(2, input.readUnsignedByte()); assertEquals(phase.action, input.readUnsignedByte())
            assertEquals(-2L, input.readLong()); assertEquals(123, input.readInt()); assertEquals(234, input.readInt())
            assertEquals(480, input.readUnsignedShort()); assertEquals(640, input.readUnsignedShort())
            assertEquals(if (phase == Touch.Phase.DOWN || phase == Touch.Phase.MOVE) 65535 else 0, input.readUnsignedShort())
            assertEquals(0, input.readInt()); assertEquals(0, input.readInt()); assertEquals(0, input.available())
        }
    }
    @Test fun malformedTouchDoesNotDecodeAsAnotherPhaseOrTap() {
        val bytes = Touch(Touch.Phase.DOWN, 1, target).bytes()
        for (bad in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(0), bytes.clone().apply { this[0] = 4 },
            bytes.clone().apply { this[1] = -1 })) {
            assertThrows(RuntimeException::class.java) { Touch.from(bad) }
        }
    }
}
