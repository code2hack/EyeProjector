package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** One represented local operation, before its UI/power/navigation effects or input retirement. */
enum class ReceiverControlSource { LOCAL_UI, NATIVE_SWIPE, GLASSES_HID, PAD }
enum class ReceiverControlDisposition { NORMAL, CONSUMED, RETIRED }

data class ReceiverControlIntent(val id: Long, val source: ReceiverControlSource, val context: ReceiverUsability) {
    init { require(id >= 0) }
    fun bytes(): ByteArray = bytes { writeLong(id); writeByte(source.ordinal); write(context.bytes()) }
    companion object {
        fun from(payload: ByteArray): ReceiverControlIntent {
            val input = DataInputStream(payload.inputStream())
            val id = input.readLong(); val source = ReceiverControlSource.entries[input.readUnsignedByte()]
            return ReceiverControlIntent(id, source, ReceiverUsability.from(input.readBytes()))
        }
    }
}

data class ReceiverControlResult(val id: Long, val disposition: ReceiverControlDisposition) {
    init { require(id >= 0) }
    fun bytes(): ByteArray = bytes { writeLong(id); writeByte(disposition.ordinal) }
    companion object {
        fun from(payload: ByteArray): ReceiverControlResult {
            require(payload.size == 9)
            val input = DataInputStream(payload.inputStream())
            return ReceiverControlResult(input.readLong(), ReceiverControlDisposition.entries[input.readUnsignedByte()])
        }
    }
}
