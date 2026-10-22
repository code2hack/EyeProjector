package com.code2hack.eyeprojector.protocol

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DiscoveredPeer(val identityId: String, val role: PeerRole, val endpoint: PeerEndpoint)

/** IPv4 directed-broadcast discovery works on shared Wi-Fi and tethered LANs.
 * Announcements carry no code/certificate and are never a trust decision. */
class LanDiscovery private constructor(
    private val identityId: String,
    private val role: PeerRole,
    private val pairingPort: Int,
    private val onPeer: (DiscoveredPeer) -> Unit,
    private val onFailure: () -> Unit,
    private val diagnostics: LanDiscoveryDiagnostics?,
) : Closeable {
    constructor(identityId: String, role: PeerRole, pairingPort: Int,
                onPeer: (DiscoveredPeer) -> Unit, onFailure: () -> Unit = {}) :
        this(identityId, role, pairingPort, onPeer, onFailure, null)

    internal constructor(identityId: String, role: PeerRole, pairingPort: Int,
                         onPeer: (DiscoveredPeer) -> Unit, diagnostics: LanDiscoveryDiagnostics,
                         onFailure: () -> Unit = {}) :
        this(identityId, role, pairingPort, onPeer, onFailure, diagnostics)

    init { diagnostics?.localRole(role) }
    private val socket = observed("SOCKET_CREATE") { DatagramSocket(null) }.apply {
        observed("SOCKET_OPTIONS") { reuseAddress = true; broadcast = true }
        diagnostics?.count("bindAttempts")
        observed("SOCKET_BIND") { bind(InetSocketAddress(DISCOVERY_PORT)) }
        if (diagnostics != null) try {
            diagnostics.bound(LanDiscoveryDiagnostics.SocketFacts(localAddress.hostAddress.orEmpty(),
                localAddress.javaClass.simpleName, localPort, isBound, reuseAddress, broadcast))
        } catch (error: Exception) { diagnostics.error("SOCKET_FACTS", error) }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false

    fun start() {
        Thread({
            val bytes = ByteArray(1024)
            diagnostics?.count("receiveLoopStarted")
            try {
                while (!closed) {
                    try {
                        val packet = DatagramPacket(bytes, bytes.size)
                        observed("RECEIVE") { socket.receive(packet) }
                        diagnostics?.count("receivedPackets")
                        val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                        if (text == "EPD1?") {
                            diagnostics?.count("receivedQueries")
                            try {
                                sendAnnouncement(packet.address, "QUERY_REPLY")
                                diagnostics?.count("queryRepliesSucceeded")
                            } catch (_: Exception) {
                                diagnostics?.count("queryRepliesFailed")
                                if (!closed) onFailure()
                            }
                        } else decode(text, packet.address.hostAddress!!, diagnostics)?.let {
                            diagnostics?.count("parsedAnnouncements")
                            if (it.identityId == identityId) diagnostics?.count("selfAnnouncements")
                            else if (it.role == role) diagnostics?.count("sameRoleAnnouncements")
                            else {
                                diagnostics?.count("eligibleAnnouncements")
                                observed("PEER_CALLBACK") { onPeer(it) }
                                diagnostics?.count("peerCallbacksReturned")
                            }
                        }
                    } catch (_: SocketException) {
                        if (!closed) { diagnostics?.count("receiveFatalStops"); onFailure() }
                        break
                    } catch (error: Exception) {
                        if (!closed) { diagnostics?.error("RECEIVE_LOOP", error); onFailure() }
                    }
                }
            } finally { diagnostics?.count("receiveLoopStopped") }
        }, "EyeProjector-discovery").start()
        ticker.scheduleWithFixedDelay({
            if (!closed) runCatching {
                diagnostics?.count("broadcastSweeps")
                sendToEachAddress(broadcastAddresses(diagnostics), { address ->
                    send("EPD1?", address, "QUERY")
                    sendAnnouncement(address)
                }, { if (!closed) onFailure() })
            }.onFailure { if (!closed) onFailure() }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun sendAnnouncement(address: InetAddress, kind: String = "ANNOUNCEMENT") = send(
        "EPD1 ${role.name} $identityId $pairingPort ${PeerEndpoint.DEFAULT_PROJECTION_PORT}", address, kind)
    private fun send(value: String, address: InetAddress, kind: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val packet = DatagramPacket(bytes, bytes.size, address, DISCOVERY_PORT)
        val host = address.hostAddress.orEmpty()
        diagnostics?.sendAttempt(host, packet.port, kind)
        try {
            socket.send(packet)
            diagnostics?.sendSucceeded(host, packet.port, kind)
        } catch (failure: Exception) {
            diagnostics?.sendFailed(host, packet.port, kind, failure)
            throw failure
        }
    }
    private inline fun <T> observed(site: String, action: () -> T): T = try { action() } catch (error: Exception) {
        diagnostics?.error(if (site == "RECEIVE" && closed) "RECEIVE_CLOSED" else site, error)
        throw error
    }
    override fun close() { closed = true; diagnostics?.count("closeRequested"); ticker.shutdownNow(); socket.close() }

    companion object {
        const val DISCOVERY_PORT = 27184
        fun decode(value: String, sourceHost: String): DiscoveredPeer? = decode(value, sourceHost, null)

        internal fun decode(value: String, sourceHost: String, diagnostics: LanDiscoveryDiagnostics?): DiscoveredPeer? = runCatching {
            val fields = value.split(' ')
            require(fields.size == 5 && fields[0] == "EPD1")
            require(fields[2].matches(Regex("[0-9a-f]{64}")))
            DiscoveredPeer(fields[2], PeerRole.valueOf(fields[1]), PeerEndpoint(sourceHost, fields[3].toInt(), fields[4].toInt()))
        }.onFailure {
            if (it is Exception) { diagnostics?.count("rejectedAnnouncements"); diagnostics?.error("PARSE", it) }
        }.getOrNull()

        /** A route failure for one destination must not suppress the other LANs. */
        internal fun sendToEachAddress(addresses: Iterable<InetAddress>, send: (InetAddress) -> Unit, failed: () -> Unit) {
            for (address in addresses) runCatching { send(address) }.onFailure { failed() }
        }

        private fun broadcastAddresses(diagnostics: LanDiscoveryDiagnostics?): Set<InetAddress> {
            diagnostics?.enumerationStarted()
            try {
                val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
                    val up = network.isUp
                    val loopback = if (up) network.isLoopback else null
                    val eligible = up && loopback == false
                    val addresses = if (eligible) network.interfaceAddresses else emptyList()
                    diagnostics?.interfaceObserved(LanDiscoveryDiagnostics.InterfaceFacts(network.name, up, loopback,
                        eligible, addresses.map { LanDiscoveryDiagnostics.AddressFacts(it.address.hostAddress.orEmpty(),
                            it.address.javaClass.simpleName, it.networkPrefixLength.toInt(), it.broadcast?.hostAddress) }))
                    addresses.filter { it.address is Inet4Address }.mapNotNullTo(result) { it.broadcast }
                }
                diagnostics?.enumerationFinished()
                return result
            } catch (failure: Exception) {
                diagnostics?.error("INTERFACES", failure)
                throw failure
            }
        }
    }
}
