package com.code2hack.eyeprojector.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Absolute VIDEO/canvas pixels, before the existing projection-to-input mapping. */
data class AnchorPosition(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

enum class AnchorInteractionMode { NORMAL, ANCHOR, ANCHOR_SETTINGS }

/** Phone-owned state for either input route. Null package means EyeProjector Home. */
data class CoordinateAnchorState(
    val packageName: String? = null,
    val mode: AnchorInteractionMode = AnchorInteractionMode.NORMAL,
    val positions: List<AnchorPosition> = emptyList(),
    val selectedIndex: Int? = null,
) {
    init {
        require(packageName != null || mode == AnchorInteractionMode.NORMAL && positions.isEmpty())
        require(if (mode == AnchorInteractionMode.ANCHOR) selectedIndex != null && selectedIndex in positions.indices
            else selectedIndex == null)
    }

    val selectedPosition: AnchorPosition? get() = selectedIndex?.let(positions::get)

    /** Standalone payload; the wiring issue allocates its transport channel and wire revision. */
    fun bytes(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(1)
            output.writeBoolean(packageName != null)
            packageName?.let(output::writeUTF)
            output.writeByte(mode.ordinal)
            output.writeInt(selectedIndex ?: -1)
            output.writeInt(positions.size)
            positions.forEach { output.writeDouble(it.x); output.writeDouble(it.y) }
        }
    }.toByteArray()

    companion object {
        fun from(payload: ByteArray): CoordinateAnchorState = DataInputStream(payload.inputStream()).use { input ->
            require(input.readInt() == 1) { "Unknown coordinate Anchor state version" }
            val present = input.readUnsignedByte().also { require(it in 0..1) }
            val packageName = if (present == 1) input.readUTF() else null
            val mode = AnchorInteractionMode.entries.getOrNull(input.readUnsignedByte())
                ?: throw IllegalArgumentException("Unknown coordinate Anchor mode")
            val selected = input.readInt().also { require(it >= -1) }
            val count = input.readInt()
            require(count >= 0 && count <= input.available() / 16)
            val positions = List(count) { AnchorPosition(input.readDouble(), input.readDouble()) }
            require(input.available() == 0)
            CoordinateAnchorState(packageName, mode, positions, selected.takeIf { it >= 0 })
        }
    }
}
