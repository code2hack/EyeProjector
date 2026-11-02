package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** Semantic Anchor operations are independent of hardware recognition. Registration always asks
 * the user to choose Click or Hold; input capabilities never choose the mode. */
enum class AnchorIntentKind { ACTIVATE, REGISTER }
enum class AnchorSource {
    PHONE_HID,
    RESERVED_GLASSES_HID, // Keep former ordinal1 reserved; no producer or semantic dispatch.
    GLASSES_NATIVE,
}

/** Receiver-owned context captured at the original operation, including its original hover position. */
data class AnchorIntentContext(val usability: ReceiverUsability, val pointer: HeadPointerState)

/** Existing grammar identity captured before classification; this selects no Short mode/contact. */
data class AnchorOriginal(val source: AnchorSource, val gestureId: Long, val context: AnchorIntentContext,
    val parentOriginal: Long? = null, val retired: Boolean = false) {
    init { require(gestureId >= 0 && (parentOriginal == null || parentOriginal >= 0)) }
    fun bytes(): ByteArray = bytes {
        writeByte(source.ordinal);writeLong(gestureId);writeLong(parentOriginal ?: -1);writeBoolean(retired)
        val fact=context.usability.bytes();writeInt(fact.size);write(fact);write(context.pointer.bytes())
    }
    companion object {
        const val MIN_PAYLOAD_SIZE=22+ReceiverUsability.MIN_PAYLOAD_SIZE+HeadPointerState.MIN_PAYLOAD_SIZE
        const val MAX_PAYLOAD_SIZE=22+ReceiverUsability.MAX_PAYLOAD_SIZE+HeadPointerState.MAX_PAYLOAD_SIZE
        fun from(payload:ByteArray):AnchorOriginal = DataInputStream(payload.inputStream()).use { input ->
            val source=AnchorSource.entries[input.readUnsignedByte()];val id=input.readLong();val parent=input.readLong();val retired=input.readBoolean();val size=input.readInt()
            require(parent >= -1)
            require(size in ReceiverUsability.MIN_PAYLOAD_SIZE..ReceiverUsability.MAX_PAYLOAD_SIZE&&size<=input.available())
            val fact=ReceiverUsability.from(ByteArray(size).also(input::readFully))
            AnchorOriginal(source,id,AnchorIntentContext(fact,HeadPointerState.from(input.readBytes())),parent.takeIf{it>=0},retired)
        }
    }
}

/**
 * One classified original operation, independent of physical release or an Anchor output contact.
 * [gestureId] is opaque within [source] and the captured Receiver incarnation; duplicate firmware
 * summaries reuse it. [sequence] is delivery order within that source/incarnation, not a timestamp.
 * Consumers retain every intention in order, including two successive ACTIVATE intentions.
 * No raw HID identity or cross-device uptime is transported here.
 *
 * ANCHOR_INTENT retains the historical FUNCTION_INTENT channel and payload bytes. A receiver must not refresh [context]
 * at dequeue or use a replacement local Session to reconstruct its original route.
 */
data class AnchorIntent(
    val kind: AnchorIntentKind,
    val source: AnchorSource,
    val gestureId: Long,
    val sequence: Long,
    val context: AnchorIntentContext,
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
        fun from(payload: ByteArray): AnchorIntent = DataInputStream(payload.inputStream()).use { input ->
            val kind = AnchorIntentKind.entries[input.readUnsignedByte()]
            val source = AnchorSource.entries[input.readUnsignedByte()]
            val gestureId = input.readLong(); val sequence = input.readLong()
            val size = input.readInt()
            require(size in ReceiverUsability.MIN_PAYLOAD_SIZE..ReceiverUsability.MAX_PAYLOAD_SIZE && size <= input.available())
            val usability = ReceiverUsability.from(ByteArray(size).also(input::readFully))
            val pointer = HeadPointerState.from(input.readBytes())
            AnchorIntent(kind, source, gestureId, sequence, AnchorIntentContext(usability, pointer))
        }
    }
}

/**
 * Bind a decoded/local intention to the actual local Session/route token before deferring work.
 * This token stays local and is never reconstructed from a successor Session or the wire payload.
 * The grammar and work queue do not own the execution contact.
 */
class CapturedAnchorIntent<C : Any>(val intent: AnchorIntent, val original: C)

/** Per-Session feature entrypoints. Unwired means no Anchor action occurred; no contact is owned here. */
class AnchorIntentConsumers<C : Any> {
    @Volatile var register: ((CapturedAnchorIntent<C>) -> Unit)? = null
    @Volatile var activate: ((CapturedAnchorIntent<C>) -> Unit)? = null

    fun dispatch(captured: CapturedAnchorIntent<C>): Boolean {
        if (captured.intent.source == AnchorSource.RESERVED_GLASSES_HID) return false
        val consumer = when (captured.intent.kind) {
            AnchorIntentKind.REGISTER -> register
            AnchorIntentKind.ACTIVATE -> activate
        } ?: return false
        consumer(captured)
        return true
    }
}
