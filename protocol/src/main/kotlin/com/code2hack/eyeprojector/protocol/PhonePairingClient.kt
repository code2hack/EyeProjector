package com.code2hack.eyeprojector.protocol

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor

/** Opaque ownership of one queued or in-flight Phone request. */
class PairingAttempt internal constructor(internal val revision: Long) {
    internal var socket: Socket? = null
    internal var pending = true
}

/** Keeps cancellation, queue entry and stale completions tied to the same request. */
internal class PhonePairingClient(
    private val store: PeerStore,
    private val executor: Executor,
    private val changed: () -> Unit,
    private val socketFactory: () -> Socket = { Socket() },
    private val exchange: (Socket, String, Int, String, Long) -> Unit = { socket, code, port, identity, revision ->
        PairingExchange.pairPhone(socket, code, store, port, identity, revision)
    },
) : Closeable {
    private val lock = Any()
    private var active: PairingAttempt? = null
    private var closed = false
    val pairing: Boolean get() = synchronized(lock) { active?.pending == true }
    @Volatile var status: String = "Ready to pair"
        private set

    fun pair(code: String, peer: DiscoveredPeer, localPort: Int, finished: (Boolean) -> Unit): PairingAttempt? {
        val attempt = synchronized(lock) {
            if (closed || active?.pending == true) return null
            // Capture before enqueueing: canceled work cannot adopt a new revision later.
            PairingAttempt(store.pairingRevision()).also { active = it; status = "Pairing…" }
        }
        changed()
        try {
            executor.execute {
                val success = runCatching {
                    synchronized(lock) { check(!closed && active === attempt && attempt.pending) }
                    socketFactory().use { socket ->
                        synchronized(lock) {
                            check(!closed && active === attempt && attempt.pending)
                            attempt.socket = socket
                        }
                        socket.connect(InetSocketAddress(peer.endpoint.host, peer.endpoint.pairingPort), 3000)
                        exchange(socket, code, localPort, peer.identityId, attempt.revision)
                    }
                }.isSuccess
                finish(attempt, success, finished)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { finish(attempt, false, finished) }
        return attempt
    }

    private fun finish(attempt: PairingAttempt, success: Boolean, finished: (Boolean) -> Unit) {
        synchronized(lock) {
            attempt.socket = null
            if (closed || active !== attempt || !attempt.pending) return
            attempt.pending = false
            status = if (success) "Paired with display device" else "Pairing failed. Check the selected display and its current code."
        }
        changed()
        finished(success)
    }

    fun cancel(attempt: PairingAttempt?): Boolean {
        val socket = synchronized(lock) {
            if (attempt == null || active !== attempt || !attempt.pending) return false
            // Uses the same store lock as completePairing. A commit that already won
            // remains paired; cancellation that wins prevents a later local commit.
            store.cancelPendingPairing()
            attempt.pending = false
            active = null
            status = if (store.pairedPeer() != null) "Paired with display device" else "Pairing canceled"
            attempt.socket
        }
        runCatching { socket?.close() }
        changed()
        return true
    }

    override fun close() {
        val socket = synchronized(lock) {
            closed = true
            store.cancelPendingPairing()
            active?.also { it.pending = false }?.socket.also { active = null }
        }
        runCatching { socket?.close() }
    }
}
