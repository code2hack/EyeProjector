package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.PeerStore
import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionState
import com.code2hack.eyeprojector.protocol.SessionPhase
import java.io.Closeable
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** One resumed Receiver owns one control poll. Delivery shares the UI queue with local control actions. */
internal class PairedSessionClient(
    private val directory: File,
    private val onState: (SessionState) -> Unit,
    private val onUnavailable: () -> Unit,
    private val transport: (SessionControl.Request, (Socket) -> Unit) -> SessionState? = { request, opened ->
        PeerStore(directory).pairedPeer()?.let { SessionControl.exchange(directory, it, request, opened) }
    },
    private val deliver: (() -> Unit) -> Unit = { it() },
) : Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var active: Socket? = null
    private var commandGeneration = 0L
    private data class StartAttempt(val baseline: String, val proposal: String, val knownOwner: String?)
    private var pendingStart: StartAttempt? = null
    private val cancelingStarts = linkedMapOf<String, StartAttempt>()
    private val canceledOwners = mutableSetOf<String>()
    // A reply may be authenticated before its queued UI callback updates the Activity's peer/session fields.
    private var observedActiveEpoch: String? = null
    private var connectWanted = false

    fun start() {
        executor.scheduleWithFixedDelay(::poll, 0, 1, TimeUnit.SECONDS)
    }

    fun connect() {
        synchronized(lock) {
            if (closed.get()) return
            commandGeneration++
            connectWanted = true
            executor.execute(::poll)
        }
    }

    private fun poll() {
        val generation = synchronized(lock) { commandGeneration }
        reconcileStarts()
        val state = exchange(SessionControl.Request(SessionControl.Operation.STATUS)) ?: return
        if (state.phase != SessionPhase.UNKNOWN) {
            val epoch = synchronized(lock) {
                if (closed.get() || generation != commandGeneration || !connectWanted || cancelingStarts.values.any { it.knownOwner == null }) return
                connectWanted = false
                StartAttempt(state.epoch, UUID.randomUUID().toString(), state.epoch.takeIf { state.phase.active }).also { pendingStart = it }
            }
            exchange(SessionControl.Request(SessionControl.Operation.START, epoch.baseline, epoch.proposal))
        }
    }

    private fun reconcileStarts() {
        val attempts = synchronized(lock) { cancelingStarts.values.toList() }
        attempts.forEach { attempt ->
            val state = exchange(SessionControl.Request.cancelStart(attempt.baseline, attempt.proposal), notify = false)
            if (state != null && state.phase != SessionPhase.UNKNOWN)
                synchronized(lock) { cancelingStarts.remove(attempt.proposal, attempt) }
        }
    }

    /** Cancel a Start even when its reply has not arrived; the proposed epoch identifies only that Start. */
    fun disconnect(epoch: String?, complete: (() -> Unit)? = null): List<String> {
        val targets = synchronized(lock) {
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            pendingStart?.let { cancelingStarts[it.proposal] = it }
            listOfNotNull(epoch, pendingStart?.proposal, observedActiveEpoch).distinct().also {
                canceledOwners.addAll(listOfNotNull(epoch, observedActiveEpoch))
                pendingStart = null
            }
        }
        synchronized(lock) {
            if (!closed.get()) executor.execute {
                try {
                    targets.filterNot { synchronized(lock) { it in cancelingStarts } }.forEach {
                        exchange(SessionControl.Request(SessionControl.Operation.STOP, it), notify = false)
                    }
                    reconcileStarts()
                }
                finally { complete?.invoke() }
            } else complete?.invoke()
        }
        return targets
    }

    fun command(operation: SessionControl.Operation, epoch: String) {
        synchronized(lock) {
            if (!closed.get()) executor.execute { exchange(SessionControl.Request(operation, epoch)) }
        }
    }

    private fun exchange(request: SessionControl.Request, notify: Boolean = true): SessionState? {
        if (closed.get()) return null
        val generation = synchronized(lock) { commandGeneration }
        return try {
            val state = transport(request) { socket ->
                synchronized(lock) {
                    if (closed.get()) { socket.close(); error("Receiver paused") }
                    active = socket
                }
            } ?: return null
            val current = synchronized(lock) {
                if (closed.get() || generation != commandGeneration) false
                else {
                    if (state.phase != SessionPhase.UNKNOWN) observedActiveEpoch = state.epoch.takeIf { state.phase.active }
                    true
                }
            }
            // STOP is reconciliation, never a new projection request. Unresolved canceled attempts
            // also fence availability until the Phone has processed their correlated cancellation.
            if (notify && current && request.operation != SessionControl.Operation.STOP) dispatch(generation) {
                val attachable = synchronized(lock) {
                    !state.phase.active || (cancelingStarts.values.none { it.knownOwner == null } && state.epoch !in canceledOwners)
                }
                if (attachable) onState(state)
            }
            state
        } catch (_: Exception) {
            dispatch(generation, onUnavailable)
            null
        } finally { synchronized(lock) { active = null } }
    }

    private fun dispatch(generation: Long, action: () -> Unit) {
        deliver {
            // Validate after asynchronous UI delivery, including errors; posting alone does not complete delivery.
            val current = synchronized(lock) { !closed.get() && generation == commandGeneration }
            if (current) action()
        }
    }

    fun close(epoch: String?) {
        val targets = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            active = null
            executor.shutdownNow()
            pendingStart?.let { cancelingStarts[it.proposal] = it }
            val proposals = cancelingStarts.keys.toSet()
            val known = listOfNotNull(epoch, observedActiveEpoch).distinct().filterNot { it in proposals }
                .map { SessionControl.Request(SessionControl.Operation.STOP, it) }
            (known + cancelingStarts.values.map { SessionControl.Request.cancelStart(it.baseline, it.proposal) })
                .also { pendingStart = null }
        }
        // The stream socket closes immediately. This final bounded command also cancels pre-stream startup.
        if (targets.isNotEmpty()) Thread({
            targets.forEach { target -> runCatching {
                transport(target) {}
            } }
        }, "eyeprojector-session-stop").start()
    }

    override fun close() = close(null)
}
