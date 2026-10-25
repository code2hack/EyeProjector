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
    private const val VERSION = 3 // Typed attempt receipts; independent of the unchanged media Wire version.
    enum class Operation { STATUS, START, STOP, CONNECTING, RENDERED, FAILED }
    // startEpoch is the admission identity, allocated before STATUS; empty retains legacy owner operations.
    data class Request(val operation: Operation, val epoch: String = "", val startEpoch: String = "",
        val intent: ActivationIntent? = null, val cancelTrigger: CancelTrigger? = null,
        val cancelActivation: CancelActivation? = null, val incarnation: ReceiverIncarnation? = null,
        val presentation: PresentationReport? = null) {
        companion object {
            fun observeAttempt(id: String) = Request(Operation.STATUS, startEpoch = id)
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
        output.writeByte(request.operation.ordinal); output.writeUTF(request.epoch); output.writeUTF(request.startEpoch)
        output.optional(request.intent) { id(it.triggerId) }
        output.optional(request.cancelTrigger) { id(it.triggerId); id(it.admissionId) }
        output.optional(request.cancelActivation) { id(it.activationId) }
        output.optional(request.incarnation) { incarnation(it) }
        output.optional(request.presentation) {
            id(it.activationId); id(it.ownerEpoch); writeLong(it.wakeRevision); incarnation(it.incarnation)
            writeLong(it.revision); writeByte(it.mode.ordinal); detail(it.detail)
        }
        output.flush()
    }

    internal fun readRequest(input: DataInputStream): Request {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val operation = Operation.entries[input.readUnsignedByte()]
        val epoch = input.readUTF().also { require(it.length <= 64) }
        val startEpoch = input.readUTF().also { require(it.length <= 64) }
        val intent = input.optional { ActivationIntent(id()) }
        val cancelTrigger = input.optional { CancelTrigger(id(), id()) }
        val cancelActivation = input.optional { CancelActivation(id()) }
        val incarnation = input.optional { incarnation() }
        val presentation = input.optional { PresentationReport(id(), id(), readLong(), incarnation(), readLong(), ReceiverUsabilityMode.entries[readUnsignedByte()], detail()) }
        return Request(operation, epoch, startEpoch, intent, cancelTrigger, cancelActivation, incarnation, presentation)
    }

    internal fun writeState(output: DataOutputStream, state: SessionState) {
        require(state.epoch.length <= 64 && state.detail.length <= 128)
        output.writeInt(MAGIC); output.writeInt(VERSION)
        output.writeUTF(state.epoch); output.writeByte(state.phase.ordinal); output.writeUTF(state.detail)
        output.writeBoolean(state.receipt != null)
        state.receipt?.let {
            require(it.id.length <= 64 && (it.owner?.length ?: 0) <= 64)
            output.writeUTF(it.id); output.writeUTF(it.owner.orEmpty()); output.writeByte(it.disposition.ordinal)
        }
        output.optional(state.activation) { reply ->
            optional(reply.directive) { id(it.activationId); writeBoolean(it.desired); writeLong(it.wakeRevision); id(it.ownerEpoch); writeByte(it.goal.ordinal) }
            optional(reply.trigger) { id(it.triggerId); id(it.activationId.orEmpty()); writeByte(it.disposition.ordinal) }
            optional(reply.cancelAcknowledged) { id(it) }
        }
        output.flush()
    }

    internal fun readState(input: DataInputStream): SessionState {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val epoch = input.readUTF().also { require(it.length <= 64) }
        val phase = SessionPhase.entries[input.readUnsignedByte()]
        val detail = input.readUTF().also { require(it.length <= 128) }
        val receipt = if (input.readBoolean()) {
            val id = input.readUTF().also { require(it.length <= 64) }
            val owner = input.readUTF().also { require(it.length <= 64) }.takeIf { it.isNotEmpty() }
            AttemptReceipt(id, owner, AttemptDisposition.entries[input.readUnsignedByte()])
        } else null
        val activation = input.optional {
            val directive = optional { ActivationDirective(id(), readBoolean(), readLong(), id(), ActivationGoal.entries[readUnsignedByte()]) }
            val trigger = optional { TriggerReceipt(id(), id().takeIf { it.isNotEmpty() }, TriggerDisposition.entries[readUnsignedByte()]) }
            ActivationReply(directive, trigger, optional { id() })
        }
        return SessionState(epoch, phase, detail, receipt, activation)
    }

    private fun DataOutputStream.id(value: String) { require(value.length <= 64); writeUTF(value) }
    private fun DataInputStream.id() = readUTF().also { require(it.length <= 64) }
    private fun DataOutputStream.detail(value: String) { require(value.length <= 128); writeUTF(value) }
    private fun DataInputStream.detail() = readUTF().also { require(it.length <= 128) }
    private fun DataOutputStream.incarnation(value: ReceiverIncarnation) { id(value.id); writeLong(value.sequence) }
    private fun DataInputStream.incarnation() = ReceiverIncarnation(id(), readLong())
    private fun <T> DataOutputStream.optional(value: T?, write: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null); if (value != null) write(value)
    }
    private fun <T> DataInputStream.optional(read: DataInputStream.() -> T): T? = if (readBoolean()) read(this) else null
}
