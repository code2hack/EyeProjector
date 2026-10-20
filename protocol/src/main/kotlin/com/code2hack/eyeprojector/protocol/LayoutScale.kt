package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** A requested layout scale; its semantic validity is decided by the active session. */
data class ScaleRequest(val scale: Double) {
    fun bytes(): ByteArray = bytes { writeDouble(scale) }

    companion object {
        fun from(payload: ByteArray): ScaleRequest {
            require(payload.size == 8)
            return ScaleRequest(DataInputStream(payload.inputStream()).readDouble())
        }
    }
}

/** Inverse display dimensions change the app's available dp bounds at a fixed density. */
object LayoutScale {
    val BASELINE = Geometry(480, 640)
    const val DPI = 160

    fun target(scale: Double, landscape: Boolean = false): Geometry? {
        if (!scale.isFinite() || scale <= 0) return null
        val width = (if (landscape) BASELINE.height else BASELINE.width) / scale
        val height = (if (landscape) BASELINE.width else BASELINE.height) / scale
        if (!width.isFinite() || !height.isFinite()) return null
        val roundedWidth = width.roundToInt()
        val roundedHeight = height.roundToInt()
        if (roundedWidth !in 1..4096 || roundedHeight !in 1..4096) return null
        return Geometry(roundedWidth, roundedHeight)
    }

    fun appliedX(geometry: Geometry): Double =
        (if (geometry.width > geometry.height) BASELINE.height else BASELINE.width).toDouble() / geometry.width

    fun appliedY(geometry: Geometry): Double =
        (if (geometry.width > geometry.height) BASELINE.width else BASELINE.height).toDouble() / geometry.height

    fun applied(geometry: Geometry): Double = min(appliedX(geometry), appliedY(geometry))
}

enum class ScaleStatus { UNAVAILABLE, READY, PENDING, OBSERVED, UNSUPPORTED, REJECTED, INTERRUPTED }

/** Backend geometry and client-resize event cause are observations, distinct from the request. */
data class ScaleState(
    val requestId: Long,
    val requested: Double,
    val observed: Geometry?,
    val target: Geometry?,
    val status: ScaleStatus,
    val clientResize: Boolean,
) {
    fun bytes(): ByteArray = bytes {
        writeLong(requestId)
        writeDouble(requested)
        writeInt(observed?.width ?: 0)
        writeInt(observed?.height ?: 0)
        writeInt(target?.width ?: 0)
        writeInt(target?.height ?: 0)
        writeInt(status.ordinal)
        writeInt(if (clientResize) 1 else 0)
    }

    companion object {
        fun from(payload: ByteArray): ScaleState {
            require(payload.size == 40)
            val input = DataInputStream(payload.inputStream())
            val requestId = input.readLong()
            val requested = input.readDouble()
            val observed = input.readGeometry()
            val target = input.readGeometry()
            val ordinal = input.readInt()
            require(ordinal in ScaleStatus.entries.indices)
            val clientResize = input.readInt()
            require(clientResize in 0..1)
            return ScaleState(requestId, requested, observed, target, ScaleStatus.entries[ordinal], clientResize == 1)
        }

        private fun DataInputStream.readGeometry(): Geometry? {
            val width = readInt()
            val height = readInt()
            return if (width == 0 && height == 0) null else Geometry(width, height)
        }
    }
}
