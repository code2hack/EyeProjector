package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.Scrcpy
import com.code2hack.eyeprojector.protocol.bytes
import java.io.DataInputStream

enum class PanelSubmission { NOT_REQUESTED, PENDING, SUBMITTED, FAILED, UNAVAILABLE }
enum class PanelRestoration { NOT_NEEDED, PENDING, SUBMITTED, FAILED }
enum class PanelObservation { UNVERIFIED }

/** Local command evidence only. Stock display-power has no acknowledgment or per-panel snapshot. */
data class PanelPowerState(
    val requestId: Long = 0,
    val off: PanelSubmission = PanelSubmission.NOT_REQUESTED,
    val restoration: PanelRestoration = PanelRestoration.NOT_NEEDED,
    val observation: PanelObservation = PanelObservation.UNVERIFIED,
    val sessionActive: Boolean = false,
) {
    fun bytes(): ByteArray = bytes {
        writeLong(requestId); writeInt(off.ordinal); writeInt(restoration.ordinal)
        writeInt(observation.ordinal); writeInt(if (sessionActive) 1 else 0)
    }

    companion object {
        fun unavailable(requestId: Long = 0) = PanelPowerState(requestId, PanelSubmission.UNAVAILABLE)

        fun from(payload: ByteArray): PanelPowerState {
            require(payload.size == 24)
            val input = DataInputStream(payload.inputStream())
            val id = input.readLong()
            val off = PanelSubmission.entries[input.readInt()]
            val restoration = PanelRestoration.entries[input.readInt()]
            val observation = PanelObservation.entries[input.readInt()]
            val active = input.readInt().also { require(it in 0..1) }
            return PanelPowerState(id, off, restoration, observation, active == 1)
        }
    }
}

/** Shares the existing stock control writer's lock; it owns no shell, socket or keepalive. */
internal class SessionPanelPower(
    private val lock: Any,
    private val active: () -> Boolean,
    private val write: (ByteArray) -> Unit,
) {
    @Volatile private var value = PanelPowerState()
    @Volatile private var closed = false

    // Never wait behind a blocked control writer just to report pending/unverified evidence.
    fun state(): PanelPowerState = value.copy(sessionActive = !closed && active())

    fun requestOff(id: Long): PanelPowerState = synchronized(lock) {
        if (closed || !active()) return@synchronized state().copy(requestId = id, off = PanelSubmission.UNAVAILABLE)
        // A write can fail after some bytes reached stock. Restoration is owed from the attempt.
        value = value.copy(requestId = id, off = PanelSubmission.PENDING, restoration = PanelRestoration.PENDING)
        value = try {
            write(Scrcpy.displayPower(false))
            value.copy(off = PanelSubmission.SUBMITTED)
        } catch (_: Exception) { value.copy(off = PanelSubmission.FAILED) }
        state()
    }

    /** Follows held-input release; the bounded release turn may outlast transport close. */
    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            if (value.restoration == PanelRestoration.PENDING) {
                value = try {
                    write(Scrcpy.displayPower(true))
                    value.copy(restoration = PanelRestoration.SUBMITTED)
                } catch (_: Exception) { value.copy(restoration = PanelRestoration.FAILED) }
            }
        }
    }
}
