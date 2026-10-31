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
 * Wire v10 channel FUNCTION_INTENT carries this payload. A receiver must not refresh [context]
 * at dequeue or use a replacement local Session to reconstruct its original route.
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
        const val MIN_PAYLOAD_SIZE = 22 + ReceiverUsability.MIN_PAYLOAD_SIZE + HeadPointerState.MIN_PAYLOAD_SIZE
        const val MAX_PAYLOAD_SIZE = 22 + ReceiverUsability.MAX_PAYLOAD_SIZE + HeadPointerState.MAX_PAYLOAD_SIZE
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

/** Per-Session feature entrypoints. Unwired means no Anchor action occurred; no contact is owned here. */
class FunctionIntentConsumers<C : Any> {
    @Volatile var register: ((CapturedFunctionIntent<C>) -> Unit)? = null
    @Volatile var activate: ((CapturedFunctionIntent<C>) -> Unit)? = null

    fun dispatch(captured: CapturedFunctionIntent<C>): Boolean {
        val consumer = when (captured.intent.kind) {
            FunctionIntentKind.REGISTER -> register
            FunctionIntentKind.ACTIVATE -> activate
        } ?: return false
        consumer(captured)
        return true
    }
}
