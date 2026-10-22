package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream

class PhoneInputTest {
    @Test fun typedPointerPreservesActualFitAndFreshVersusHeldMapping() {
        val state = HeadPointerState(8, 20, true, true, 120f, 240f, Fit(20, 40, 240, 320), Geometry(480, 640))
        assertEquals(state, HeadPointerState.from(state.bytes()))
        assertEquals(Tap(200, 400, Geometry(480, 640)), state.target())
        val outside = state.copy(x = -50f, y = 900f)
        assertNull(outside.target())
        assertEquals(Tap(0, 638, Geometry(480, 640)), outside.target(held = true))
        val local = HeadPointerState(9, 21, true, false, 25f, 50f, null, null)
        assertEquals(local, HeadPointerState.from(local.bytes())); assertNull(local.target())
    }

    @Test fun phoneActualEdgesAreTypedLocalFactsAndCompletedUiHasNoHidFields() {
        val edge = PhoneHidEdge(PhoneHidAction.TAP, PhoneHidPhase.DOWN, 7, 66, 23, 1234, 1237)
        assertEquals(edge, PhoneHidEdge.from(edge.bytes()))
        assertTrue(edge.samePress(edge.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1299)))
        assertFalse(edge.samePress(edge.copy(attachmentId = 8)))
        val ui = ReceiverInput(ReceiverInputKind.MOVE, 4, 99, 7f, 10f, heldMs = 23)
        assertEquals(ui, ReceiverInput.from(ui.bytes()))
        assertThrows(IllegalArgumentException::class.java) { HeadPointerState.from(stateBytesWithTrailingData()) }
        assertThrows(IllegalArgumentException::class.java) { PhoneHidEdge.from(edge.bytes() + 0.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { ReceiverInput.from(ui.bytes() + 0.toByte()) }
    }

    private fun stateBytesWithTrailingData() = HeadPointerState(0, 0, false, false, 0f, 0f, null, null).bytes() + 0.toByte()

    @Test fun wireFiveMatchesBothNewDirectionsAndRejectsThePriorHandshake() {
        assertEquals(5, Wire.VERSION); assertEquals(15, Wire.HEAD_POINTER); assertEquals(16, Wire.RECEIVER_INPUT)
        assertEquals(14, Wire.TOUCH); assertEquals(3, Wire.TAP); assertEquals(8, Wire.SCROLL)
        val frames = listOf(Frame(Wire.HEAD_POINTER, payload = HeadPointerState(1, 2, false, false, 0f, 0f, null, null).bytes()),
            Frame(Wire.RECEIVER_INPUT, payload = ReceiverInput(ReceiverInputKind.PANEL, 1).bytes()))
        for (frame in frames) {
            val b = ByteArrayOutputStream(); Wire.writeFrame(DataOutputStream(b), frame)
            val decoded = Wire.readFrame(DataInputStream(b.toByteArray().inputStream()))
            assertEquals(frame.channel, decoded.channel); assertArrayEquals(frame.payload, decoded.payload)
        }
        val old = bytes { writeInt(Wire.MAGIC); writeInt(4); writeByte(Wire.STREAM) }
        assertThrows(IllegalArgumentException::class.java) { Wire.readRequest(DataInputStream(old.inputStream())) }
    }
}
