package com.code2hack.eyeprojector.protocol

import java.io.File
import java.net.Socket
import android.content.Context
import android.widget.LinearLayout

/** Lifecycle boundaries are inert; this suite never opens a socket or discovers peers. */
class PeerStore(val directory: File)
enum class PeerRole { PHONE, GLASSES }
class LanPairing(store: PeerStore, role: PeerRole, changed: () -> Unit, control: ((Socket) -> Unit)?) {
    fun start() {}
}

/** The actual HostActivity owns close/forget/start wiring; this boundary emits no events itself. */
class PairingPanel(context: Context, role: PeerRole, changed: (Boolean) -> Unit,
    beforeForget: (() -> Unit) -> Unit, pairing: LanPairing) : LinearLayout(context) {
    var forgetting = false
    fun isPaired() = true
    fun close() {}
}

object SessionControl {
    enum class Operation { STATUS, START, STOP, CONNECTING, RENDERED, FAILED }
    data class Request(val operation: Operation, val epoch: String = "", val startEpoch: String = "")
    fun serve(directory: File, socket: Socket, handle: (Request) -> SessionState) {}
}
