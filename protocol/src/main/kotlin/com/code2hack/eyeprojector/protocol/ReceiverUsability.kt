package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** Explicit presentation/input ownership, independent of pointer availability or packet silence. */
enum class ReceiverUsabilityMode { NONE, LOCAL_PANEL, PROJECTED }
data class ReceiverUsability(
    val ownerEpoch: String, val incarnation: ReceiverIncarnation, val revision: Long,
    val inputRevision: Long, val mode: ReceiverUsabilityMode,
) {
    fun bytes(): ByteArray = bytes {
        require(ownerEpoch.length <= 64 && incarnation.id.length <= 64)
        writeUTF(ownerEpoch); writeUTF(incarnation.id); writeLong(incarnation.sequence)
        writeLong(revision); writeLong(inputRevision); writeByte(mode.ordinal)
    }
    companion object {
        const val MIN_PAYLOAD_SIZE = 29
        const val MAX_PAYLOAD_SIZE = 2 * (2 + 3 * 64) + 3 * 8 + 1
        fun from(payload: ByteArray): ReceiverUsability = DataInputStream(payload.inputStream()).use {
            val owner = it.readUTF().also { require(it.length <= 64) }
            val incarnation = ReceiverIncarnation(it.readUTF().also { require(it.length <= 64) }, it.readLong())
            val value = ReceiverUsability(owner, incarnation, it.readLong(), it.readLong(), ReceiverUsabilityMode.entries[it.readUnsignedByte()])
            require(it.available() == 0)
            value
        }
    }
}
