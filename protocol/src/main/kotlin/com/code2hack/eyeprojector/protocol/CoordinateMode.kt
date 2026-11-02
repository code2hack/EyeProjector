package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** Semantic operation on the Phone owner. Positions are absolute video/canvas pixels. */
enum class CoordinateOperation { SNAPSHOT, CYCLE, TAP, BACK, SCROLL_UP, SCROLL_DOWN }

data class CoordinateModeResult(val state: CoordinateAnchorState, val consumed: Boolean = false,
    val failure: String? = null) {
    fun bytes(): ByteArray = bytes {
        writeBoolean(consumed); writeUTF(failure.orEmpty()); write(state.bytes())
    }
    companion object {
        fun from(payload: ByteArray): CoordinateModeResult = DataInputStream(payload.inputStream()).use {
            val consumed = it.readBoolean(); val failure = it.readUTF().ifEmpty { null }
            CoordinateModeResult(CoordinateAnchorState.from(it.readBytes()), consumed, failure)
        }
    }
}
