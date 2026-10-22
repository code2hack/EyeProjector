package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** One continuous pointer. The Android down-time identifies the contact within a connection. */
data class Touch(val phase: Phase, val contactId: Long, val target: Tap) {
    enum class Phase(val action: Int) { DOWN(0), UP(1), MOVE(2), CANCEL(3) }
    init { require(contactId >= 0) }
    fun bytes(): ByteArray = bytes { writeByte(phase.action); writeLong(contactId); write(target.bytes()) }
    companion object {
        const val PAYLOAD_SIZE = 25
        fun from(payload: ByteArray): Touch {
            require(payload.size == PAYLOAD_SIZE)
            val input = DataInputStream(payload.inputStream())
            val action = input.readUnsignedByte()
            return Touch(Phase.entries.single { it.action == action }, input.readLong(),
                Tap.from(payload.copyOfRange(9, PAYLOAD_SIZE)))
        }
    }
}

/** Upstream 5.0.1 INJECT_TOUCH_EVENT (32 bytes), using scrcpy's existing generic finger id. */
fun Scrcpy.touch(touch: Touch): ByteArray = bytes {
    writeByte(2); writeByte(touch.phase.action); writeLong(-2)
    writeInt(touch.target.x); writeInt(touch.target.y)
    writeShort(touch.target.geometry.width); writeShort(touch.target.geometry.height)
    writeShort(if (touch.phase == Touch.Phase.DOWN || touch.phase == Touch.Phase.MOVE) 0xffff else 0)
    writeInt(0); writeInt(0)
}
