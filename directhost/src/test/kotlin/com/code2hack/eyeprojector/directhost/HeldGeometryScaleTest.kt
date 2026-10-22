package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real control/scale models and encodings; no Android Session, display or physical-input claim. */
class HeldGeometryScaleTest {
    @Test fun rotationCancelsHeldPointerBeforePublishingAndCausalScaleUsesNewOrientation() {
        val controlLock = Any()
        val publicationLock = Any()
        val controls = mutableListOf<ByteArray>()
        val wire = ByteArrayOutputStream()
        val output = DataOutputStream(wire)
        val touch = PhoneTouchDispatcher(controlLock) { controls.add(it) }
        val resizes = mutableListOf<Geometry>()
        val scale = LiveScale(
            resize = { size -> synchronized(controlLock) { touch.cancel(); resizes.add(size) } },
            publish = { state -> Wire.writeFrame(output, Frame(Wire.SCALE_STATE, state.requestId, state.bytes())) },
        )
        val portrait = Geometry(480, 640)
        scale.observe(ScrcpyRecord.Session(portrait, false), publicationLock) { touch.geometry(portrait) }
        wire.reset()
        assertTrue(touch.touch(Touch(Touch.Phase.DOWN, 1000, Tap(240, 320, portrait))))
        val landscape = Geometry(640, 480)
        scale.observe(ScrcpyRecord.Session(landscape, false), publicationLock) {
            touch.geometry(landscape)
            // Check the terminal control at the publication boundary, before sending any new geometry.
            assertEquals(2, controls.size)
            val cancel = DataInputStream(controls.last().inputStream())
            assertEquals(2, cancel.readUnsignedByte()); assertEquals(3, cancel.readUnsignedByte())
            assertEquals(-2L, cancel.readLong()); assertEquals(320, cancel.readInt()); assertEquals(240, cancel.readInt())
            assertEquals(640, cancel.readUnsignedShort()); assertEquals(480, cancel.readUnsignedShort())
            assertEquals(0, cancel.readUnsignedShort()); assertFalse(touch.holding)
            Wire.writeFrame(output, Frame(Wire.GEOMETRY, payload = landscape.bytes()))
        }
        val input = DataInputStream(wire.toByteArray().inputStream())
        assertEquals(Wire.GEOMETRY, Wire.readFrame(input).channel)
        val feedback = Wire.readFrame(input)
        assertEquals(Wire.SCALE_STATE, feedback.channel)
        assertEquals(landscape, ScaleState.from(feedback.payload).observed)
        assertEquals(0, input.available())
        scale.request(9, 1.25)
        assertEquals(listOf(Geometry(512, 384)), resizes)
        for (phase in listOf(Touch.Phase.MOVE, Touch.Phase.UP)) {
            assertFalse(touch.touch(Touch(phase, 1000, Tap(320, 240, landscape))))
        }
        assertEquals(2, controls.size)
        assertTrue(touch.touch(Touch(Touch.Phase.DOWN, 2000, Tap(320, 240, landscape))))
        touch.close()
    }
}
