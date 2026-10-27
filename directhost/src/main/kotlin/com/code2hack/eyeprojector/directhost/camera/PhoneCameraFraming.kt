package com.code2hack.eyeprojector.directhost.camera

import android.media.MediaFormat
import com.code2hack.eyeprojector.protocol.CameraAccessUnit
import com.code2hack.eyeprojector.protocol.CameraFraming
import com.code2hack.eyeprojector.protocol.CameraStreamDescription
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Dimensions and inclusive crop actually returned by the local decoder, not the remote request. */
data class PhoneDecodedFormat(val width: Int, val height: Int, val cropLeft: Int = 0,
    val cropTop: Int = 0, val cropRight: Int = width - 1, val cropBottom: Int = height - 1) {
    init {
        require(width > 0 && height > 0)
        require(cropLeft >= 0 && cropTop >= 0 && cropRight in cropLeft until width && cropBottom in cropTop until height)
    }
    val cropWidth: Int get() = cropRight - cropLeft + 1
    val cropHeight: Int get() = cropBottom - cropTop + 1
}

/** A captured mapping. Callers cannot rewrite a later draw by mutating an earlier observation. */
class PhoneFrameMapping internal constructor(matrix: FloatArray, vertices: FloatArray, coordinates: FloatArray,
    val ownerCalibratedCcwQuarterTurns: Int = 0) {
    private val matrixStorage = matrix.copyOf()
    private val vertexStorage = vertices.copyOf()
    private val coordinateStorage = coordinates.copyOf()
    val textureMatrix: FloatArray get() = matrixStorage.copyOf()
    val vertexPositions: FloatArray get() = vertexStorage.copyOf()
    val textureCoordinates: FloatArray get() = coordinateStorage.copyOf()
    val policy: String = if (ownerCalibratedCcwQuarterTurns == 0) "RAW_DECODED_ORIENTATION_CENTER_CROP"
        else "OWNER_CALIBRATED_CCW_${ownerCalibratedCcwQuarterTurns * 90}_CENTER_CROP"
}

/** No sensor orientation, mirror flag, local preview alignment or clock relation is inferred here. */
class PhoneCameraFraming {
    fun decodeFormat(description: CameraStreamDescription): MediaFormat {
        // Absent observations leave explicit configuration hints, not invented actual dimensions.
        val width = description.observedWidth ?: description.request.requestedWidth
        val height = description.observedHeight ?: description.request.requestedHeight
        val format = MediaFormat.createVideoFormat(description.mime, width, height)
        val configuration = avcConfiguration(description)
        description.csd.forEach { data ->
            if (configuration == null || data.key != "csd-0") format.setByteBuffer(data.key, ByteBuffer.wrap(data.value))
        }
        if (configuration != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(configuration.sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(configuration.pps))
        }
        return format
    }

    fun fromOutputFormat(format: MediaFormat): PhoneDecodedFormat {
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        fun integer(key: String, fallback: Int) = if (format.containsKey(key)) format.getInteger(key) else fallback
        return PhoneDecodedFormat(width, height, integer("crop-left", 0), integer("crop-top", 0),
            integer("crop-right", width - 1), integer("crop-bottom", height - 1))
    }

    fun observe(actualFormat: PhoneDecodedFormat, actualSurfaceTextureMatrix: FloatArray,
        inputWidth: Int, inputHeight: Int, ownerCalibratedCcwQuarterTurns: Int = 0): PhoneFrameMapping {
        require(actualSurfaceTextureMatrix.size == 16)
        require(inputWidth > 0 && inputHeight > 0)
        val turns = Math.floorMod(ownerCalibratedCcwQuarterTurns, 4)
        val orientedWidth = if (turns % 2 == 0) actualFormat.cropWidth else actualFormat.cropHeight
        val orientedHeight = if (turns % 2 == 0) actualFormat.cropHeight else actualFormat.cropWidth
        val sourceAspect = orientedWidth.toDouble() / orientedHeight
        val targetAspect = inputWidth.toDouble() / inputHeight
        val sampleWidth = if (sourceAspect > targetAspect) (targetAspect / sourceAspect).toFloat() else 1f
        val sampleHeight = if (sourceAspect < targetAspect) (sourceAspect / targetAspect).toFloat() else 1f
        val left = (1f - sampleWidth) / 2f
        val bottom = (1f - sampleHeight) / 2f
        val coordinates = floatArrayOf(left, bottom, left + sampleWidth, bottom, left, bottom + sampleHeight,
            left + sampleWidth, bottom + sampleHeight)
        // Inverse sampling (u,v)->(v,1-u) turns the visible image CCW. Apply it before
        // SurfaceTexture's observed matrix, which already carries the actual buffer crop.
        // This is Owner calibration of the current feed, not inferred sensor orientation.
        for (index in coordinates.indices step 2) repeat(turns) {
            val u = coordinates[index]
            coordinates[index] = coordinates[index + 1]
            coordinates[index + 1] = 1f - u
        }
        return PhoneFrameMapping(actualSurfaceTextureMatrix, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            coordinates, turns)
    }

    fun inputBytes(description: CameraStreamDescription, unit: CameraAccessUnit): ByteArray {
        val bytes = unit.data
        val configuration = avcConfiguration(description)
        if (description.framing != CameraFraming.LENGTH_PREFIXED_OBSERVED || configuration == null) return bytes
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < bytes.size) {
            require(bytes.size - offset >= configuration.lengthBytes) { "Incomplete AVC NAL length" }
            var size = 0L
            repeat(configuration.lengthBytes) { size = (size shl 8) or (bytes[offset++].toLong() and 255L) }
            require(size > 0 && size <= bytes.size - offset) { "Incomplete AVC NAL body" }
            out.write(START_CODE)
            out.write(bytes, offset, size.toInt())
            offset += size.toInt()
        }
        return out.toByteArray()
    }

    private class AvcConfiguration(val lengthBytes: Int, val sps: ByteArray, val pps: ByteArray)

    /** Only the complete, self-identifying avcC form without unparsed extensions is converted. */
    private fun avcConfiguration(description: CameraStreamDescription): AvcConfiguration? {
        if (description.mime != "video/avc" || description.csd.any { it.key == "csd-1" }) return null
        val bytes = description.csd.singleOrNull { it.key == "csd-0" }?.value ?: return null
        if (bytes.size < 7 || bytes[0].toInt() != 1 || bytes[4].toInt() and 0xfc != 0xfc ||
            bytes[5].toInt() and 0xe0 != 0xe0) return null
        val lengthBytes = (bytes[4].toInt() and 3) + 1
        // avcC reserves lengthSizeMinusOne=2; it is not evidence of a three-byte length.
        if (lengthBytes == 3) return null
        var offset = 6
        fun nals(count: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            repeat(count) {
                if (bytes.size - offset < 2) return null
                val size = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
                offset += 2
                if (size == 0 || size > bytes.size - offset) return null
                out.write(START_CODE); out.write(bytes, offset, size); offset += size
            }
            return out.toByteArray()
        }
        val spsCount = bytes[5].toInt() and 31
        if (spsCount == 0) return null
        val sps = nals(spsCount) ?: return null
        if (offset >= bytes.size) return null
        val ppsCount = bytes[offset++].toInt() and 255
        if (ppsCount == 0) return null
        val pps = nals(ppsCount) ?: return null
        if (offset != bytes.size) return null
        return AvcConfiguration(lengthBytes, sps, pps)
    }

    private companion object { val START_CODE = byteArrayOf(0, 0, 0, 1) }
}

/** The same full-buffer operation used by the real codec pump. No partial prefix is submitted. */
internal fun writePhoneDecoderInput(destination: ByteBuffer, bytes: ByteArray): Int {
    require(destination.capacity() >= bytes.size) {
        "Decoder input capacity ${destination.capacity()} cannot hold the complete ${bytes.size}-byte access unit"
    }
    destination.clear()
    destination.put(bytes)
    return bytes.size
}

internal fun phoneDecoderIdentityMatches(original: Any, candidate: Any, expectedFormat: Long, actualFormat: Long): Boolean =
    original === candidate && expectedFormat == actualFormat
