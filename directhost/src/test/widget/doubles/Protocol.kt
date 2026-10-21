package com.code2hack.eyeprojector.protocol
import java.io.File
import java.net.Socket
class PeerStore(val directory: File) {
    var paired = true
    var unreadable = false
    fun pairedPeer(): Any? { check(!unreadable); return if (paired) Any() else null }
}
enum class PeerRole { PHONE }
class LanPairing(val store: PeerStore, role: PeerRole, val changed: () -> Unit, control: (Socket) -> Unit) {
    fun start() {}
}
object SessionControl {
    enum class Operation { STATUS, START, STOP, CONNECTING, RENDERED, FAILED }
    data class Request(val operation: Operation, val epoch: String = "", val startEpoch: String = "")
    fun serve(directory: File, socket: Socket, command: (Request) -> SessionState) {}
}
