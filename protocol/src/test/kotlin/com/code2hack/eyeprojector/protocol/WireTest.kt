package com.code2hack.eyeprojector.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class WireTest {
    private fun input(block: DataOutputStream.() -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test fun scrcpy501SessionAndFlagsAreDecodedExactly() {
        val stream = input {
            writeInt(Int.MIN_VALUE or 1); writeInt(480); writeInt(640)
            writeLong(1L shl 62); writeInt(3); write(byteArrayOf(1, 2, 3))
            writeLong((1L shl 61) or 1234L); writeInt(1); writeByte(5)
        }
        assertEquals(ScrcpyRecord.Session(Geometry(480, 640), true), Scrcpy.readRecord(stream))
        val config = Scrcpy.readRecord(stream) as ScrcpyRecord.Packet
        assertTrue(config.config); assertEquals(0L, config.ptsUs)
        val frame = Scrcpy.readRecord(stream) as ScrcpyRecord.Packet
        assertTrue(frame.keyFrame); assertFalse(frame.config); assertEquals(1234L, frame.ptsUs)
    }

    @Test fun badLengthsAndTruncatedPayloadFailClosed() {
        for (length in listOf(-1, 0, Wire.MAX_PAYLOAD + 1)) {
            assertThrows(Exception::class.java) { Scrcpy.readRecord(input { writeLong(0); writeInt(length) }) }
        }
        assertThrows(Exception::class.java) { Scrcpy.readRecord(input { writeLong(0); writeInt(3); writeByte(1) }) }
        assertThrows(Exception::class.java) { Scrcpy.readRecord(input { writeInt(Int.MIN_VALUE or 2); writeInt(480); writeInt(640) }) }
    }

    @Test fun transportPreservesVideoAndAudioPts() {
        val original = Frame(Wire.VIDEO, (1L shl 61) or 100L, byteArrayOf(9, 8, 7))
        val result = Wire.readFrame(input { Wire.writeFrame(this, original) })
        assertEquals(original.channel, result.channel); assertEquals(original.stamp, result.stamp)
        assertArrayEquals(original.payload, result.payload)
        assertEquals(2, Wire.AUDIO)
        val audioMeta = Wire.readFrame(input { Wire.writeFrame(this, Frame(Wire.AUDIO_CODEC, payload = byteArrayOf(0x6f, 0x70, 0x75, 0x73))) })
        assertEquals(7, audioMeta.channel)
        assertThrows(Exception::class.java) { Wire.readWelcome(input { writeInt(Wire.MAGIC); writeInt(999) }) }
    }

    @Test fun audioIsMandatoryAndPreservesPinnedConfigAndDisabledIds() {
        Wire.readWelcome(input { Wire.writeWelcome(this) })
        assertThrows(Exception::class.java) { Wire.readWelcome(input {
            writeInt(Wire.MAGIC); writeInt(Wire.VERSION); writeUTF(Wire.SCRCPY_VERSION); writeInt(Wire.H264); writeBoolean(false)
        }) }
        for (stamp in listOf(Scrcpy.CONFIG, 9_000_123_456L)) {
            val audio = Wire.readFrame(input { Wire.writeFrame(this, Frame(Wire.AUDIO, stamp, byteArrayOf(1, 2))) })
            assertEquals(stamp, audio.stamp); assertArrayEquals(byteArrayOf(1, 2), audio.payload)
        }
        for (id in listOf(0, 1, Wire.OPUS)) {
            val meta = Wire.readFrame(input { Wire.writeFrame(this, Frame(Wire.AUDIO_CODEC, payload = bytes { writeInt(id) })) })
            assertEquals(id, DataInputStream(meta.payload.inputStream()).readInt())
        }
    }

    @Test fun versionTwoKeepsRequiredAudioAndNativeInputOnTheSameStream() {
        assertEquals(2, Wire.VERSION)
        assertThrows(Exception::class.java) { Wire.readRequest(input {
            writeInt(Wire.MAGIC); writeInt(1); writeByte(Wire.STREAM)
        }) }
        assertThrows(Exception::class.java) { Wire.readWelcome(input {
            writeInt(Wire.MAGIC); writeInt(1); writeUTF(Wire.SCRCPY_VERSION)
            writeInt(Wire.H264); writeBoolean(false)
        }) }
        val size = Geometry(480, 640)
        val frames = listOf(
            Frame(Wire.AUDIO_CODEC, payload = bytes { writeInt(Wire.OPUS) }),
            Frame(Wire.AUDIO, Scrcpy.CONFIG, byteArrayOf(1, 2)),
            Frame(Wire.TAP, payload = Tap(25, 40, size).bytes()),
            Frame(Wire.SCROLL, payload = Scroll(Tap(25, 40, size), 160).bytes()),
            Frame(Wire.NAVIGATE, payload = Navigate(Navigation.BACK, size).bytes()),
            Frame(Wire.AUDIO, 9_000_123_456L, byteArrayOf(3, 4)),
        )
        val stream = input { Wire.writeWelcome(this); frames.forEach { Wire.writeFrame(this, it) } }
        Wire.readWelcome(stream)
        for (expected in frames) {
            val actual = Wire.readFrame(stream)
            assertEquals(expected.channel, actual.channel)
            assertEquals(expected.stamp, actual.stamp)
            assertArrayEquals(expected.payload, actual.payload)
        }
        assertEquals(-1, stream.read())
    }

    @Test fun coordinateMappingRejectsLetterboxAndUsesCurrentGeometry() {
        val fit = Fit.inside(480, 640, Geometry(640, 480))
        assertEquals(140, fit.top); assertEquals(360, fit.height)
        assertNull(fit.touch(240f, 100f, Geometry(640, 480)))
        assertEquals(Tap(320, 240, Geometry(640, 480)), fit.touch(240f, 320f, Geometry(640, 480)))
        assertNull(fit.touch(Float.NaN, 320f, Geometry(640, 480)))
        assertNull(fit.touch(480f, 320f, Geometry(640, 480)))
    }

    @Test fun touchEncodingIsExactlyPinnedScrcpyTouchDownAndUp() {
        val bytes = Scrcpy.tap(Tap(10, 20, Geometry(480, 640)))
        assertEquals(64, bytes.size)
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        for (action in listOf(0, 1)) {
            assertEquals(2, stream.readUnsignedByte()); assertEquals(action, stream.readUnsignedByte())
            assertEquals(-2L, stream.readLong()); assertEquals(10, stream.readInt()); assertEquals(20, stream.readInt())
            assertEquals(480, stream.readUnsignedShort()); assertEquals(640, stream.readUnsignedShort())
            assertEquals(if (action == 0) 65535 else 0, stream.readUnsignedShort())
            assertEquals(0, stream.readInt()); assertEquals(0, stream.readInt())
        }
        assertThrows(Exception::class.java) { Tap(-1, 20, Geometry(480, 640)) }
    }

    @Test fun scrollAndNavigationAreBoundedAndUsePinnedNativeFormats() {
        val target = Tap(25, 40, Geometry(640, 480))
        val scroll = Scroll(target, 160)
        assertEquals(scroll, Scroll.from(scroll.bytes()))
        val frame = Wire.readFrame(input { Wire.writeFrame(this, Frame(Wire.SCROLL, payload = scroll.bytes())) })
        assertEquals(scroll, Scroll.from(frame.payload))
        val wheel = DataInputStream(Scrcpy.scroll(scroll).inputStream())
        assertEquals(3, wheel.readUnsignedByte())
        assertEquals(25, wheel.readInt()); assertEquals(40, wheel.readInt())
        assertEquals(640, wheel.readUnsignedShort()); assertEquals(480, wheel.readUnsignedShort())
        assertEquals(0, wheel.readShort().toInt()); assertEquals(-5120, wheel.readShort().toInt())
        assertEquals(0, wheel.readInt()); assertEquals(-1, wheel.read())
        for (action in Navigation.entries) {
            val navigation = Navigate(action, target.geometry)
            assertEquals(navigation, Navigate.from(navigation.bytes()))
            val keys = DataInputStream(Scrcpy.navigate(navigation).inputStream())
            for (phase in 0..1) {
                assertEquals(0, keys.readUnsignedByte()); assertEquals(phase, keys.readUnsignedByte())
                assertEquals(action.keycode, keys.readInt()); assertEquals(0, keys.readInt()); assertEquals(0, keys.readInt())
            }
            assertEquals(-1, keys.read())
        }
        for (pixels in listOf(0, -1201, 1201)) assertThrows(Exception::class.java) { Scroll(target, pixels) }
        assertThrows(Exception::class.java) { Scroll.from(scroll.bytes().copyOf(19)) }
        assertThrows(Exception::class.java) { Navigate.from(target.geometry.bytes() + bytes { writeInt(26) }) }
        assertThrows(Exception::class.java) { Wire.writeFrame(DataOutputStream(ByteArrayOutputStream()), Frame(Wire.NAVIGATE, payload = byteArrayOf(4))) }
    }

    @Test fun resizedRotatedStreamAndKeyboardLayoutKeepMappingInCurrentBounds() {
        for (size in listOf(Geometry(480, 640), Geometry(640, 480), Geometry(1080, 2160), Geometry(640, 640))) {
            val fit = Fit.inside(480, 640, size)
            assertEquals(Tap(size.width / 2, size.height / 2, size), fit.touch(fit.left + fit.width / 2f, fit.top + fit.height / 2f, size))
            assertNull(fit.touch(fit.left - 1f, fit.top + 1f, size))
            assertNull(fit.touch(fit.left + 1f, (fit.top + fit.height).toFloat(), size))
            assertNull(fit.touch(Float.POSITIVE_INFINITY, 50f, size))
        }
    }
}
