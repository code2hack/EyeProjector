package com.code2hack.eyeprojector.protocol

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle owner used by either panel. Its callbacks run off the Android UI thread. */
class LanPairing(val store: PeerStore, val role: PeerRole, private val changed: () -> Unit) : Closeable {
    val codes = PairingCode()
    private val peers = ConcurrentHashMap<String, Pair<DiscoveredPeer, Long>>()
    private val closed = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor()
    private val lifecycle = Any()
    private var listener: ServerSocket? = null
    private var discovery: LanDiscovery? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile var status: String = "Looking for the paired device on Wi-Fi or the Phone hotspot"
        private set
    @Volatile var pairing = false
        private set
    @Volatile var identityId: String? = null
        private set

    fun start() {
        executor.execute {
            try {
                store.ensureIdentity()
                identityId = store.identityId()
                val server = synchronized(lifecycle) {
                    if (closed.get()) return@execute
                    val server = ServerSocket(0).also { listener = it }
                    discovery = LanDiscovery(store.identityId(), role, server.localPort, ::discovered) {
                        status = "LAN discovery unavailable. Check Wi-Fi or the Phone hotspot."; changed()
                    }.also { it.start() }
                    server
                }
                if (role == PeerRole.GLASSES) Thread({
                    while (!closed.get()) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        synchronized(lifecycle) {
                            if (closed.get()) { socket.close(); return@Thread }
                            activeSocket = socket
                        }
                        try {
                            socket.use { PairingExchange.pairGlasses(it, codes, store, server.localPort) }
                            status = "Paired with Phone"
                        } catch (_: Exception) { if (!closed.get()) status = "Pairing failed. Use the current code and try again." }
                        finally { activeSocket = null; changed() }
                    }
                }, "EyeProjector-pairing").start()
                status = if (store.pairedPeer() == null) "Ready to pair on Wi-Fi or the Phone hotspot" else "Paired; looking for peer"
                changed()
            } catch (_: Exception) {
                synchronized(lifecycle) { runCatching { listener?.close() }; discovery?.close() }
                if (!closed.get()) { status = "Pairing unavailable. Reopen EyeProjector and check the network."; changed() }
            }
        }
    }

    private fun discovered(peer: DiscoveredPeer) {
        peers.entries.removeIf { System.nanoTime() - it.value.second >= 8_000_000_000L }
        peers[peer.identityId] = peer to System.nanoTime()
        val localHost = if (role == PeerRole.PHONE) DatagramSocket().use {
                it.connect(InetAddress.getByName(peer.endpoint.host), peer.endpoint.pairingPort)
                it.localAddress.hostAddress
            } else null
        if (store.discoveredEndpoint(peer.identityId, peer.endpoint, role, localHost)) {
            status = "Paired device available on LAN"
        }
        changed()
    }

    fun availablePeers(): List<DiscoveredPeer> = peers.values.filter { System.nanoTime() - it.second < 8_000_000_000L }
        .map { it.first }.sortedBy { it.identityId }

    fun pair(code: String, candidate: DiscoveredPeer, finished: (Boolean) -> Unit) {
        if (pairing || closed.get()) return
        pairing = true; status = "Pairing…"; changed()
        executor.execute {
            val success = runCatching {
                val port = listener?.localPort ?: error("Pairing not ready")
                Socket().use { socket ->
                    synchronized(lifecycle) {
                        check(!closed.get())
                        activeSocket = socket
                    }
                    socket.connect(InetSocketAddress(candidate.endpoint.host, candidate.endpoint.pairingPort), 3000)
                    PairingExchange.pairPhone(socket, code, store, port, candidate.identityId)
                }
            }.isSuccess
            activeSocket = null; pairing = false
            status = if (success) "Paired with display device" else "Pairing failed. Check the selected display and its current code."
            if (!closed.get()) { changed(); finished(success) }
        }
    }

    fun forget() {
        runCatching { activeSocket?.close() }
        store.forget(); codes.rotate(); status = "Unpaired"; changed()
    }

    override fun close() {
        synchronized(lifecycle) {
            closed.set(true); store.cancelPendingPairing()
            runCatching { activeSocket?.close() }; runCatching { listener?.close() }
            discovery?.close(); executor.shutdownNow()
        }
    }
}
