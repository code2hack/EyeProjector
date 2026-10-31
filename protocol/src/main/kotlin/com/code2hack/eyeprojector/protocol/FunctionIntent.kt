package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** Grammar emits intentions; registration always asks the user to choose Click or Hold. */
enum class FunctionIntentKind { ACTIVATE, REGISTER }
enum class FunctionSource { PHONE_HID, GLASSES_HID, GLASSES_NATIVE }

/** Receiver-owned context captured at the original gesture, including its original hover position. */
data class FunctionIntentContext(val usability: ReceiverUsability, val pointer: HeadPointerState)

/**
 * One classified original gesture, independent of physical release or an Anchor output contact.
 * [gestureId] is opaque within [source] and the captured Receiver incarnation; duplicate firmware
 * summaries reuse it. [sequence] is delivery order within that source/incarnation, not a timestamp.
 * Consumers retain every intention in order, including two successive ACTIVATE intentions.
 * No raw HID identity or cross-device uptime is transported here.
 *
 * This payload contract does not allocate a LAN channel or enable a new grammar. Both endpoints
 * must adopt the eventual transport together. A receiver must not refresh [context] at dequeue.
 */
data class FunctionIntent(
    val kind: FunctionIntentKind,
    val source: FunctionSource,
    val gestureId: Long,
    val sequence: Long,
    val context: FunctionIntentContext,
) {
    init { require(gestureId >= 0 && sequence >= 0) }

    fun bytes(): ByteArray = bytes {
        writeByte(kind.ordinal); writeByte(source.ordinal); writeLong(gestureId); writeLong(sequence)
        val usability = context.usability.bytes()
        writeInt(usability.size); write(usability); write(context.pointer.bytes())
    }

    companion object {
        fun from(payload: ByteArray): FunctionIntent = DataInputStream(payload.inputStream()).use { input ->
            val kind = FunctionIntentKind.entries[input.readUnsignedByte()]
            val source = FunctionSource.entries[input.readUnsignedByte()]
            val gestureId = input.readLong(); val sequence = input.readLong()
            val size = input.readInt()
            require(size in ReceiverUsability.MIN_PAYLOAD_SIZE..ReceiverUsability.MAX_PAYLOAD_SIZE && size <= input.available())
            val usability = ReceiverUsability.from(ByteArray(size).also(input::readFully))
            val pointer = HeadPointerState.from(input.readBytes())
            FunctionIntent(kind, source, gestureId, sequence, FunctionIntentContext(usability, pointer))
        }
    }
}

/**
 * Bind a decoded/local intention to the actual local Session/route token before deferring work.
 * This token stays local and is never reconstructed from a successor Session or the wire payload.
 * The grammar and work queue do not own the execution contact.
 */
class CapturedFunctionIntent<C : Any>(val intent: FunctionIntent, val original: C)
