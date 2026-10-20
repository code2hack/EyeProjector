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
class LanDiscovery(
    private val identityId: String,
    private val role: PeerRole,
    private val pairingPort: Int,
    private val onPeer: (DiscoveredPeer) -> Unit,
    private val onFailure: () -> Unit = {},
) : Closeable {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true; broadcast = true
        bind(InetSocketAddress(DISCOVERY_PORT))
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false

    fun start() {
        Thread({
            val bytes = ByteArray(1024)
            while (!closed) {
                try {
                    val packet = DatagramPacket(bytes, bytes.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (text == "EPD1?") sendAnnouncement(packet.address)
                    else decode(text, packet.address.hostAddress!!)?.let {
                        if (it.identityId != identityId && it.role != role) onPeer(it)
                    }
                } catch (_: SocketException) { if (!closed) onFailure(); break }
                catch (_: Exception) { if (!closed) onFailure() }
            }
        }, "EyeProjector-discovery").start()
        ticker.scheduleWithFixedDelay({
            if (!closed) runCatching {
                for (address in broadcastAddresses()) {
                    send("EPD1?", address)
                    sendAnnouncement(address)
                }
            }.onFailure { if (!closed) onFailure() }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun sendAnnouncement(address: InetAddress) = send(
        "EPD1 ${role.name} $identityId $pairingPort ${PeerEndpoint.DEFAULT_PROJECTION_PORT}", address)
    private fun send(value: String, address: InetAddress) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size, address, DISCOVERY_PORT))
    }
    override fun close() { closed = true; ticker.shutdownNow(); socket.close() }

    companion object {
        const val DISCOVERY_PORT = 27184
        fun decode(value: String, sourceHost: String): DiscoveredPeer? = runCatching {
            val fields = value.split(' ')
            require(fields.size == 5 && fields[0] == "EPD1")
            require(fields[2].matches(Regex("[0-9a-f]{64}")))
            DiscoveredPeer(fields[2], PeerRole.valueOf(fields[1]), PeerEndpoint(sourceHost, fields[3].toInt(), fields[4].toInt()))
        }.getOrNull()

        private fun broadcastAddresses(): Set<InetAddress> {
            val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
            NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
                network.interfaceAddresses.filter { it.address is Inet4Address }.mapNotNullTo(result) { it.broadcast }
            }
            return result
        }
    }
}
