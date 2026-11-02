package com.code2hack.eyeprojector.directhost.anchor.position

import com.code2hack.eyeprojector.protocol.AnchorPosition
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** One complete per-package list. Write must throw on failure, preserving the previous record. */
interface AnchorPositionStorage {
    fun read(packageName: String): List<AnchorPosition>
    fun write(packageName: String, positions: List<AnchorPosition>)
}

/** Coordinates only: no mode, selection, accessibility identity or legacy descriptor conversion. */
data class AppAnchorPositions(val packageName: String, val positions: List<AnchorPosition>)

object AnchorPositionCodec {
    private const val MAGIC = 0x45504150 // EPAP, distinct from historical descriptor records.

    fun encode(value: AppAnchorPositions): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(1)
            output.writeUTF(value.packageName)
            output.writeInt(value.positions.size)
            value.positions.forEach { output.writeDouble(it.x); output.writeDouble(it.y) }
        }
    }.toByteArray()

    fun decode(payload: ByteArray): AppAnchorPositions = DataInputStream(payload.inputStream()).use { input ->
        require(input.readInt() == MAGIC && input.readInt() == 1) { "Unknown coordinate Anchor record" }
        val packageName = input.readUTF()
        val count = input.readInt()
        require(count >= 0 && count <= input.available() / 16)
        val positions = List(count) { AnchorPosition(input.readDouble(), input.readDouble()) }
        require(input.available() == 0)
        AppAnchorPositions(packageName, positions)
    }
}
