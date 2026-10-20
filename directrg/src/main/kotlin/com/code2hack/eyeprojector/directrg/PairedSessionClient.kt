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

/** One resumed Receiver owns one control poll; every exchange is bounded and closes its socket. */
internal class PairedSessionClient(
    private val directory: File,
    private val onState: (SessionState) -> Unit,
    private val onUnavailable: () -> Unit,
    private val transport: (SessionControl.Request, (Socket) -> Unit) -> SessionState? = { request, opened ->
        PeerStore(directory).pairedPeer()?.let { SessionControl.exchange(directory, it, request, opened) }
    },
) : Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var active: Socket? = null
    private var commandGeneration = 0L
    private var pendingStart: String? = null
    private var connectWanted = false

    fun start() {
        executor.scheduleWithFixedDelay(::poll, 0, 1, TimeUnit.SECONDS)
    }

    fun connect() {
        synchronized(lock) {
            if (closed.get()) return
            connectWanted = true
            executor.execute(::poll)
        }
    }

    private fun poll() {
        val generation = synchronized(lock) { commandGeneration }
        val state = exchange(SessionControl.Request(SessionControl.Operation.STATUS)) ?: return
        if (state.phase != SessionPhase.UNKNOWN) {
            val epoch = synchronized(lock) {
                if (closed.get() || generation != commandGeneration || !connectWanted) return
                connectWanted = false
                UUID.randomUUID().toString().also { pendingStart = it }
            }
            exchange(SessionControl.Request(SessionControl.Operation.START, state.epoch, epoch))
        }
    }

    /** Cancel a Start even when its reply has not arrived; the proposed epoch identifies only that Start. */
    fun disconnect(epoch: String?, complete: (() -> Unit)? = null): List<String> {
        val targets = synchronized(lock) {
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            listOfNotNull(epoch, pendingStart).distinct().also { pendingStart = null }
        }
        synchronized(lock) {
            if (!closed.get()) executor.execute {
                try { targets.forEach { exchange(SessionControl.Request(SessionControl.Operation.STOP, it)) } }
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
            if (notify && !closed.get() && generation == synchronized(lock) { commandGeneration }) onState(state)
            state
        } catch (_: Exception) {
            if (!closed.get() && generation == synchronized(lock) { commandGeneration }) onUnavailable()
            null
        } finally { synchronized(lock) { active = null } }
    }

    fun close(epoch: String?) {
        val targets = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            active = null
            executor.shutdownNow()
            listOfNotNull(epoch, pendingStart).distinct().also { pendingStart = null }
        }
        // The stream socket closes immediately. This final bounded command also cancels pre-stream startup.
        if (targets.isNotEmpty()) Thread({ runCatching {
            targets.forEach { transport(SessionControl.Request(SessionControl.Operation.STOP, it)) {} }
        } }, "eyeprojector-session-stop").start()
    }

    override fun close() = close(null)
}
