package com.code2hack.eyeprojector.directhost.camera

import com.code2hack.eyeprojector.protocol.CameraAccessUnit
import com.code2hack.eyeprojector.protocol.CameraCodecData
import com.code2hack.eyeprojector.protocol.CameraFraming
import com.code2hack.eyeprojector.protocol.CameraStreamDescription
import com.code2hack.eyeprojector.protocol.CameraStreamRequest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Pure production framing/input operations. These do not pretend to exercise Android decoding. */
class PhoneCameraDecoderTest {
    private val framing = PhoneCameraFraming()
    private val request = CameraStreamRequest(640, 480, 35, "video/avc")
    private fun description(kind: CameraFraming, csd: ByteArray? = null) = CameraStreamDescription(request,
        "video/avc", "remote-encoder", 640, 480, 7, kind,
        if (csd == null) emptyList() else listOf(CameraCodecData("csd-0", csd)))
    private fun unit(bytes: ByteArray) = CameraAccessUnit(7, 0, -123, 0x40000000, bytes)
    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    // Complete AVCDecoderConfigurationRecord: four-byte lengths, one SPS and one PPS.
    private fun avcC() = byteArrayOf(1, 66, 0, 30, -1, -31, 0, 2, 103, 66, 1, 0, 2, 104, -50)

    @Test fun unclassifiedAndUnprovedBytesArePreservedWithoutGuessingStartCodes() {
        val bytes = byteArrayOf(0, 0, 0, 2, 101, -1)
        assertArrayEquals(bytes, framing.inputBytes(description(CameraFraming.SOURCE_BYTES_UNCLASSIFIED, avcC()), unit(bytes)))
        assertArrayEquals(bytes, framing.inputBytes(description(CameraFraming.LENGTH_PREFIXED_OBSERVED), unit(bytes)))
        val malformedRecord = avcC().copyOf(10)
        assertArrayEquals(bytes, framing.inputBytes(description(CameraFraming.LENGTH_PREFIXED_OBSERVED, malformedRecord), unit(bytes)))
        assertArrayEquals(bytes, framing.inputBytes(description(CameraFraming.ANNEX_B_OBSERVED, avcC()), unit(bytes)))
    }

    @Test fun provedLengthWidthConvertsEveryNalWithoutMutatingOriginalUnit() {
        val bytes = byteArrayOf(0, 0, 0, 2, 101, -1, 0, 0, 0, 1, 6)
        val original = unit(bytes)
        val converted = framing.inputBytes(description(CameraFraming.LENGTH_PREFIXED_OBSERVED, avcC()), original)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 101, -1, 0, 0, 0, 1, 6), converted)
        converted.fill(0)
        assertArrayEquals(bytes, original.data)
        assertEquals(-123L, original.ptsUs)
        assertEquals(0x40000000, original.codecFlags)
    }

    @Test fun provedButIncompleteNalFailsBeforeAnyCodecSubmission() {
        try {
            framing.inputBytes(description(CameraFraming.LENGTH_PREFIXED_OBSERVED, avcC()), unit(byteArrayOf(0, 0, 0, 9, 101)))
            fail("Expected incomplete NAL failure")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("NAL body")) }
    }

    @Test fun completeLargeInputIsWrittenAndInsufficientBufferGetsNoPartialPrefix() {
        val bytes = ByteArray(200_003) { (it * 13).toByte() }
        val exact = ByteBuffer.allocate(bytes.size)
        assertEquals(bytes.size, writePhoneDecoderInput(exact, bytes))
        assertArrayEquals(bytes, exact.array())
        val short = ByteBuffer.allocate(bytes.size - 1)
        short.array().fill(99)
        val before = short.array().copyOf()
        try { writePhoneDecoderInput(short, bytes); fail("Expected native capacity failure") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("complete")) }
        assertArrayEquals(before, short.array())
        assertEquals(0, short.position())
    }

    @Test fun equalSuccessorIdentityAndDifferentFormatCannotEnterOriginalDecoder() {
        data class EqualValue(val id: Int)
        val original = EqualValue(4)
        assertTrue(phoneDecoderIdentityMatches(original, original, 7, 7))
        assertFalse(phoneDecoderIdentityMatches(original, EqualValue(4), 7, 7))
        assertFalse(phoneDecoderIdentityMatches(original, original, 7, 8))
    }

    @Test fun actualCropAspectAndCapturedSurfaceTransformDriveDefensiveMapping() {
        val matrix = identity().also { it[12] = 0.125f; it[5] = -1f }
        val format = PhoneDecodedFormat(1920, 1088, 0, 0, 1919, 1079)
        val result = framing.observe(format, matrix, 640, 480)
        // 16:9 cropped to 4:3 samples the central 75%; decoder crop is not applied twice.
        assertArrayEquals(floatArrayOf(0.125f, 0f, 0.875f, 0f, 0.125f, 1f, 0.875f, 1f), result.textureCoordinates, 0.00001f)
        assertArrayEquals(matrix, result.textureMatrix, 0f)
        val first = result.textureMatrix
        first.fill(0f); matrix.fill(0f)
        assertEquals(0.125f, result.textureMatrix[12], 0f)
        assertEquals(-1f, result.textureMatrix[5], 0f)
        val coordinates = result.textureCoordinates
        coordinates.fill(0f)
        assertEquals(0.875f, result.textureCoordinates[2], 0f)
        assertEquals("RAW_DECODED_ORIENTATION_CENTER_CROP", result.policy)
    }

    @Test fun matchedActualAspectRetainsEntireSampleRectangle() {
        val result = framing.observe(PhoneDecodedFormat(640, 480), identity(), 1280, 960)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), result.textureCoordinates, 0f)
    }
}
