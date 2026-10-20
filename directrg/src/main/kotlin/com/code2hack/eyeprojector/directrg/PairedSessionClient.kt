package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One resumed Receiver owns its admission, including a STATUS reply not yet delivered to the UI. */
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
    private data class Attempt(val id: String = UUID.randomUUID().toString(), var baseline: String = "", var owner: String? = null)
    // Allocate before even the automatic first STATUS can open a transport.
    private var admission: Attempt? = Attempt()
    private val canceling = linkedMapOf<String, Attempt>()
    private val canceledOwners = mutableSetOf<String>()
    private var observedActiveEpoch: String? = null
    private var connectWanted = false

    fun start() { executor.scheduleWithFixedDelay(::poll, 0, 1, TimeUnit.SECONDS) }

    fun connect() {
        synchronized(lock) {
            if (closed.get()) return
            commandGeneration++
            connectWanted = true
            if (admission == null) admission = Attempt()
            executor.execute(::poll)
        }
    }

    private fun poll() {
        val generation = synchronized(lock) { commandGeneration }
        reconcile()
        val attempt = synchronized(lock) {
            if (closed.get() || generation != commandGeneration) return
            (admission ?: Attempt().also { admission = it })
        }
        val state = exchange(SessionControl.Request.observeAttempt(attempt.id)) ?: return
        val receipt = state.receipt
        synchronized(lock) {
            if (closed.get() || generation != commandGeneration || admission !== attempt) return
            if (receipt?.id != attempt.id || receipt.disposition != AttemptDisposition.OBSERVED) {
                if (receipt?.disposition in setOf(AttemptDisposition.RETIRED, AttemptDisposition.CANCELED)) admission = null
                return
            }
            if (state.phase == SessionPhase.UNKNOWN || !connectWanted || canceling.values.any { it.owner == null }) return
            connectWanted = false
            attempt.baseline = state.epoch
        }
        exchange(SessionControl.Request(SessionControl.Operation.START, state.epoch, attempt.id))
    }

    private fun reconcile() {
        val attempts = synchronized(lock) { canceling.values.toList() }
        attempts.forEach { attempt -> exchange(SessionControl.Request.cancelStart(attempt.baseline, attempt.id), notify = false) }
    }

    fun disconnect(epoch: String?, complete: (() -> Unit)? = null): List<String> {
        val targets = synchronized(lock) {
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            admission?.let { canceling[it.id] = it }
            listOfNotNull(epoch, admission?.id, observedActiveEpoch).distinct().also {
                canceledOwners.addAll(listOfNotNull(epoch, observedActiveEpoch))
                admission = null
            }
        }
        synchronized(lock) {
            if (!closed.get()) executor.execute {
                try {
                    targets.filterNot { synchronized(lock) { it in canceling } }.forEach {
                        exchange(SessionControl.Request(SessionControl.Operation.STOP, it), notify = false)
                    }
                    reconcile()
                } finally { complete?.invoke() }
            } else complete?.invoke()
        }
        return targets
    }

    fun command(operation: SessionControl.Operation, epoch: String) {
        synchronized(lock) {
            if (!closed.get()) executor.execute {
                val id = synchronized(lock) { admission?.id.orEmpty() }
                exchange(SessionControl.Request(operation, epoch, id))
            }
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
                val receipt = state.receipt
                val canceled = canceling[request.startEpoch]
                if (receipt?.id == request.startEpoch) {
                    if (canceled != null) {
                        receipt.owner?.let { canceled.owner = it; canceledOwners.add(it) }
                        if (receipt.disposition == AttemptDisposition.CANCELED) canceling.remove(canceled.id)
                    } else admission?.takeIf { it.id == receipt.id }?.let { attempt ->
                        receipt.owner?.let { attempt.owner = it }
                        if (attempt.baseline.isEmpty() && state.phase != SessionPhase.UNKNOWN) attempt.baseline = receipt.owner ?: state.epoch
                    }
                }
                if (closed.get() || generation != commandGeneration) false
                else {
                    if (state.phase != SessionPhase.UNKNOWN) observedActiveEpoch = state.epoch.takeIf { state.phase.active }
                    true
                }
            }
            // A STOP reply describes reconciliation; it cannot grant attachment to any owner.
            if (current && (state.receipt?.disposition == AttemptDisposition.RETRYABLE || state.detail == "SESSION_CONTROL_STATE_UNAVAILABLE"))
                dispatch(generation, onUnavailable)
            else if (notify && current && request.operation != SessionControl.Operation.STOP) dispatch(generation) {
                val attachable = synchronized(lock) {
                    val receipt = state.receipt
                    !state.phase.active || (receipt != null && receipt.id == admission?.id &&
                        receipt.disposition == AttemptDisposition.OBSERVED && receipt.owner == state.epoch &&
                        canceling.values.none { it.owner == null } && state.epoch !in canceledOwners)
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
            admission?.let { canceling[it.id] = it }
            val known = listOfNotNull(epoch, observedActiveEpoch).distinct().filterNot { it in canceling }
                .map { SessionControl.Request(SessionControl.Operation.STOP, it) }
            (known + canceling.values.map { SessionControl.Request.cancelStart(it.baseline, it.id) }).also { admission = null }
        }
        // Each transport retains its existing bound. A failure cannot skip a distinct final target.
        Thread({ targets.forEach { target -> runCatching { transport(target) {} } } }, "eyeprojector-session-stop").start()
    }

    override fun close() = close(null)
}
