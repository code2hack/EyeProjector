package com.code2hack.eyeprojector.protocol
import java.io.File
import java.net.Socket
class PeerStore(val directory: File) {
    var paired = true
    var unreadable = false
    fun pairedPeer(): RememberedPeer? { check(!unreadable); return if (paired) RememberedPeer() else null }
}
enum class PeerRole { PHONE }
class LanPairing(val store: PeerStore, role: PeerRole, val changed: () -> Unit, control: (Socket) -> Unit) {
    fun start() {}
}
class PeerEndpoint(val host:String="unused",val pairingPort:Int=1234)
class RememberedPeer(val endpoint:PeerEndpoint=PeerEndpoint(),val tlsName:String="unused")
object Tls {
    fun context(directory:File):javax.net.ssl.SSLContext = error("Widget TLS is an explicit unused boundary")
    fun requireTrustedPeer(socket:javax.net.ssl.SSLSocket,directory:File)=Unit
}
