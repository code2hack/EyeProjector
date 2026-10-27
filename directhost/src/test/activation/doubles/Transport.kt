package com.code2hack.eyeprojector.protocol
import java.io.File
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
class PeerEndpoint(val host: String = "unused", val pairingPort: Int = 1234)
class RememberedPeer(val endpoint: PeerEndpoint = PeerEndpoint(), val tlsName: String = "unused")
class PeerStore(val directory: File) { fun pairedPeer(): RememberedPeer? = null }
object Tls {
    fun context(directory: File): SSLContext = error("TLS boundary is not exercised by this source fixture")
    fun requireTrustedPeer(socket: SSLSocket, directory: File) = Unit
}
