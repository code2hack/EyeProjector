package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream

/** Append roles: the first four IDs and persisted names belong to existing assignments. */
enum class PhoneHidAction { TAP, SCROLL_DOWN, SCROLL_UP, FUNCTION, BACK_HOME, LOCK }
enum class PhoneHidPhase { DOWN, UP, CANCEL }

/** Actual Phone-local facts. This record is Binder-local, never forwarded over LAN. */
data class PhoneHidEdge(
    val action: PhoneHidAction, val phase: PhoneHidPhase, val attachmentId: Int,
    val keyCode: Int, val scanCode: Int, val downTimeMs: Long, val eventTimeMs: Long,
    val repeatCount: Int = 0,
) {
    init { require(downTimeMs >= 0 && eventTimeMs >= downTimeMs && repeatCount >= 0) }
    fun samePress(other: PhoneHidEdge) = attachmentId == other.attachmentId && keyCode == other.keyCode &&
        scanCode == other.scanCode && downTimeMs == other.downTimeMs && action == other.action
    fun bytes(): ByteArray = bytes {
        writeByte(action.ordinal); writeByte(phase.ordinal); writeInt(attachmentId); writeInt(keyCode); writeInt(scanCode)
        writeLong(downTimeMs); writeLong(eventTimeMs); writeInt(repeatCount)
    }
    companion object {
        const val PAYLOAD_SIZE = 34
        fun from(payload: ByteArray): PhoneHidEdge {
            require(payload.size == PAYLOAD_SIZE)
            val input = DataInputStream(payload.inputStream())
            return PhoneHidEdge(PhoneHidAction.entries[input.readUnsignedByte()], PhoneHidPhase.entries[input.readUnsignedByte()],
                input.readInt(), input.readInt(), input.readInt(), input.readLong(), input.readLong(), input.readInt())
        }
    }
}

/** Source root coordinates and actual fit; no cross-device clock or HID facts. */
data class HeadPointerState(
    val revision: Long, val sequence: Long, val available: Boolean, val projected: Boolean,
    val x: Float, val y: Float, val fit: Fit?, val geometry: Geometry?,
) {
    init {
        require(revision >= 0 && sequence >= 0 && x.isFinite() && y.isFinite())
        require((fit == null) == (geometry == null))
        if (fit != null) require(fit.width > 0 && fit.height > 0)
        if (available && projected) require(fit != null)
    }
    fun target(held: Boolean = false): Tap? {
        if (!available || !projected) return null
        val f = fit ?: return null
        val g = geometry ?: return null
        return if (!held) f.touch(x, y, g) else f.touch(x.coerceIn(f.left.toFloat(), (f.left + f.width - 1).toFloat()),
            y.coerceIn(f.top.toFloat(), (f.top + f.height - 1).toFloat()), g)
    }
    fun bytes(): ByteArray = bytes {
        writeLong(revision); writeLong(sequence); writeBoolean(available); writeBoolean(projected); writeFloat(x); writeFloat(y)
        writeBoolean(fit != null)
        fit?.let { writeInt(it.left); writeInt(it.top); writeInt(it.width); writeInt(it.height); write(geometry!!.bytes()) }
    }
    companion object {
        const val MIN_PAYLOAD_SIZE = 27
        const val MAX_PAYLOAD_SIZE = 51
        fun from(payload: ByteArray): HeadPointerState {
            require(payload.size == MIN_PAYLOAD_SIZE || payload.size == MAX_PAYLOAD_SIZE)
            val i = DataInputStream(payload.inputStream())
            val revision = i.readLong(); val sequence = i.readLong(); val available = i.readBoolean(); val projected = i.readBoolean()
            val x = i.readFloat(); val y = i.readFloat(); val fitted = i.readBoolean()
            require(fitted == (payload.size == MAX_PAYLOAD_SIZE))
            val fit = if (fitted) Fit(i.readInt(), i.readInt(), i.readInt(), i.readInt()) else null
            val geometry = if (fitted) Geometry(i.readInt(), i.readInt()) else null
            return HeadPointerState(revision, sequence, available, projected, x, y, fit, geometry)
        }
    }
}

enum class ReceiverInputKind { DOWN, MOVE, UP, CANCEL, SCROLL, PANEL, BACK, EXIT, FUNCTION_DOUBLE, SLEEP }

/** Completed UI operation. Identity is opaque; elapsed duration is Phone-local, not a remote uptime. */
data class ReceiverInput(val kind: ReceiverInputKind, val revision: Long, val contactId: Long = 0,
    val x: Float = 0f, val y: Float = 0f, val pixels: Int = 0, val heldMs: Long = 0) {
    init { require(revision >= 0 && contactId >= 0 && x.isFinite() && y.isFinite() && heldMs >= 0)
        require(if (kind == ReceiverInputKind.SCROLL) pixels in -1200..1200 && pixels != 0 else pixels == 0) }
    fun bytes(): ByteArray = bytes { writeByte(kind.ordinal); writeLong(revision); writeLong(contactId)
        writeFloat(x); writeFloat(y); writeInt(pixels); writeLong(heldMs) }
    companion object {
        const val PAYLOAD_SIZE = 37
        fun from(payload: ByteArray): ReceiverInput {
            require(payload.size == PAYLOAD_SIZE)
            val i = DataInputStream(payload.inputStream())
            return ReceiverInput(ReceiverInputKind.entries[i.readUnsignedByte()], i.readLong(), i.readLong(),
                i.readFloat(), i.readFloat(), i.readInt(), i.readLong())
        }
    }
}
