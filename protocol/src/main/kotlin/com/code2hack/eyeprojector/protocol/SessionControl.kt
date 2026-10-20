package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/** Session commands use the existing paired certificate pins; discovery only supplies an address. */
object SessionControl {
    private const val MAGIC = 0x45505331 // EPS1; separate from pairing and media framing.
    private const val VERSION = 1
    enum class Operation { STATUS, START, STOP, CONNECTING, RENDERED, FAILED }
    // STOP with startEpoch cancels that specific START proposal from baseline epoch; empty retains ordinary owner STOP.
    data class Request(val operation: Operation, val epoch: String = "", val startEpoch: String = "") {
        companion object {
            fun cancelStart(baseline: String, proposal: String) = Request(Operation.STOP, baseline, proposal)
        }
    }

    fun exchange(directory: File, peer: RememberedPeer, request: Request, opened: (Socket) -> Unit = {}): SessionState {
        Socket().use { raw ->
            opened(raw)
            raw.connect(InetSocketAddress(peer.endpoint.host, peer.endpoint.pairingPort), 3000)
            val socket = Tls.context(directory).socketFactory.createSocket(raw, peer.tlsName, peer.endpoint.pairingPort, true) as SSLSocket
            socket.use {
                configure(it)
                it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                it.startHandshake()
                Tls.requireTrustedPeer(it, directory)
                writeRequest(DataOutputStream(it.outputStream), request)
                return readState(DataInputStream(it.inputStream))
            }
        }
    }

    /** The Phone's pairing listener is unused by code entry; paired commands are admitted through mTLS. */
    fun serve(directory: File, raw: Socket, handle: (Request) -> SessionState) {
        val socket = Tls.context(directory).socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        socket.use {
            it.useClientMode = false
            it.needClientAuth = true
            configure(it)
            it.startHandshake()
            Tls.requireTrustedPeer(it, directory)
            writeState(DataOutputStream(it.outputStream), handle(readRequest(DataInputStream(it.inputStream))))
        }
    }

    private fun configure(socket: SSLSocket) {
        socket.soTimeout = 4000; socket.tcpNoDelay = true
        socket.enabledProtocols = socket.supportedProtocols.filter { it in listOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
    }

    internal fun writeRequest(output: DataOutputStream, request: Request) {
        require(request.epoch.length <= 64 && request.startEpoch.length <= 64)
        output.writeInt(MAGIC); output.writeInt(VERSION)
        output.writeByte(request.operation.ordinal); output.writeUTF(request.epoch); output.writeUTF(request.startEpoch); output.flush()
    }

    internal fun readRequest(input: DataInputStream): Request {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val operation = Operation.entries[input.readUnsignedByte()]
        val epoch = input.readUTF().also { require(it.length <= 64) }
        val startEpoch = input.readUTF().also { require(it.length <= 64) }
        return Request(operation, epoch, startEpoch)
    }

    internal fun writeState(output: DataOutputStream, state: SessionState) {
        require(state.epoch.length <= 64 && state.detail.length <= 128)
        output.writeInt(MAGIC); output.writeInt(VERSION)
        output.writeUTF(state.epoch); output.writeByte(state.phase.ordinal); output.writeUTF(state.detail); output.flush()
    }

    internal fun readState(input: DataInputStream): SessionState {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val epoch = input.readUTF().also { require(it.length <= 64) }
        val phase = SessionPhase.entries[input.readUnsignedByte()]
        val detail = input.readUTF().also { require(it.length <= 128) }
        return SessionState(epoch, phase, detail)
    }
}
