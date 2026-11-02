package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real control/scale models and encodings; no Android Session, display or physical-input claim. */
class HeldGeometryScaleTest {
    @Test fun passiveRotationKeepsOriginalTerminalAndExplicitScaleUsesNewOrientation() {
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
            // Passive publication refreshes future mapping without ending the original.
            assertEquals(1, controls.size)
            assertTrue(touch.holding)
            Wire.writeFrame(output, Frame(Wire.GEOMETRY, payload = landscape.bytes()))
        }
        val input = DataInputStream(wire.toByteArray().inputStream())
        assertEquals(Wire.GEOMETRY, Wire.readFrame(input).channel)
        val feedback = Wire.readFrame(input)
        assertEquals(Wire.SCALE_STATE, feedback.channel)
        assertEquals(landscape, ScaleState.from(feedback.payload).observed)
        assertEquals(0, input.available())
        assertTrue(touch.touch(Touch(Touch.Phase.UP, 1000, Tap(240, 320, portrait))))
        assertFalse(touch.holding)
        val originalUp=DataInputStream(controls.last().inputStream())
        assertEquals(2,originalUp.readUnsignedByte());assertEquals(1,originalUp.readUnsignedByte())
        assertEquals(-2L,originalUp.readLong());assertEquals(240,originalUp.readInt());assertEquals(320,originalUp.readInt())
        assertEquals(480,originalUp.readUnsignedShort());assertEquals(640,originalUp.readUnsignedShort())
        // A later ordinary pointer is admitted against the rotated geometry.
        assertTrue(touch.touch(Touch(Touch.Phase.DOWN, 2000, Tap(320, 240, landscape))))
        scale.request(9, 1.25) // The explicit user scale callback still cancels ordinary input.
        assertEquals(listOf(Geometry(512, 384)), resizes)
        assertEquals(listOf(0,1,0,3),controls.map{it[1].toInt()})
        val canceled=DataInputStream(controls.last().inputStream()).apply{skipBytes(10)}
        assertEquals(320,canceled.readInt());assertEquals(240,canceled.readInt())
        assertEquals(640,canceled.readUnsignedShort());assertEquals(480,canceled.readUnsignedShort())
        for (phase in listOf(Touch.Phase.MOVE, Touch.Phase.UP))
            assertFalse(touch.touch(Touch(phase, 2000, Tap(320, 240, landscape))))
        assertFalse(touch.holding)
        assertTrue(touch.touch(Touch(Touch.Phase.DOWN, 3000, Tap(320, 240, landscape))))
        touch.close()
    }
}
