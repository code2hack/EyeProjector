package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.Scrcpy
import com.code2hack.eyeprojector.protocol.bytes
import java.io.DataInputStream

/** Both identities belong to one actual media Session, independently of service attachment lifetime. */
data class PanelContext(val ownerEpoch: String = "", val projectionToken: String = "") {
    val resolved: Boolean get() = ownerEpoch.isNotEmpty() && projectionToken.isNotEmpty()
}

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
    val context: PanelContext = PanelContext(),
) {
    fun bytes(): ByteArray = bytes {
        writeLong(requestId); writeInt(off.ordinal); writeInt(restoration.ordinal)
        writeInt(observation.ordinal); writeInt(if (sessionActive) 1 else 0)
        writeUTF(context.ownerEpoch); writeUTF(context.projectionToken)
    }

    companion object {
        fun unavailable(requestId: Long = 0, context: PanelContext = PanelContext()) =
            PanelPowerState(requestId, PanelSubmission.UNAVAILABLE, context = context)

        fun from(payload: ByteArray): PanelPowerState {
            require(payload.size >= 28)
            val input = DataInputStream(payload.inputStream())
            val id = input.readLong()
            val off = PanelSubmission.entries[input.readInt()]
            val restoration = PanelRestoration.entries[input.readInt()]
            val observation = PanelObservation.entries[input.readInt()]
            val active = input.readInt().also { require(it in 0..1) }
            val context = PanelContext(input.readUTF(), input.readUTF())
            require(input.available() == 0)
            return PanelPowerState(id, off, restoration, observation, active == 1, context)
        }
    }
}

/** Shares the existing stock control writer's lock; it owns no shell, socket or keepalive. */
internal class SessionPanelPower(
    private val lock: Any,
    private val active: () -> Boolean,
    context: PanelContext = PanelContext(),
    private val write: (ByteArray) -> Unit,
) {
    @Volatile private var value = PanelPowerState(context = context)
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

/** Owner/target publication is atomic; capture never resolves a replacement after queueing. */
internal class PanelSessionRoutes<T : Any>(private val invalidate: (T) -> Unit) {
    class Owner internal constructor(val epoch: String)
    data class Route<T>(val owner: Owner, val target: T)
    private val lock = Any()
    private var owner: Owner? = null
    private var accepting = false
    private var route: Route<T>? = null
    private var last = PanelPowerState.unavailable()

    fun begin(epoch: String): Owner {
        val next = Owner(epoch)
        val previous = synchronized(lock) {
            val prior = route
            owner = next; accepting = true; route = null; last = PanelPowerState.unavailable()
            prior
        }
        previous?.let { invalidate(it.target) }
        return next
    }

    fun publish(expected: Owner, target: T) {
        val accepted = synchronized(lock) {
            if (owner === expected && accepting) { route = Route(expected, target); true } else false
        }
        if (!accepted) invalidate(target)
    }

    fun capture(expectedEpoch: String): Route<T>? = synchronized(lock) {
        route?.takeIf { accepting && it.owner === owner && it.owner.epoch == expectedEpoch }
    }

    fun current(): Route<T>? = synchronized(lock) { route }
    fun lastState(): PanelPowerState = synchronized(lock) { last }

    /** Invalidate admission before the caller enters any potentially blocking teardown. */
    fun stop() {
        val previous = synchronized(lock) { accepting = false; route.also { route = null } }
        previous?.let { invalidate(it.target) }
    }

    fun complete(expected: Owner, target: T, state: PanelPowerState) = synchronized(lock) {
        if (owner === expected) {
            if (route?.target === target) route = null
            last = state
        }
    }
}
