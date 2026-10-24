package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One retained Receiver lifetime owns admission, including STATUS replies awaiting delivery. */
internal class PairedSessionClient(
    private val directory: File,
    private val onState: (SessionState) -> Unit,
    private val onUnavailable: () -> Unit,
    private val transport: (SessionControl.Request, (Socket) -> Unit) -> SessionState? = { request, opened ->
        pairedControl(directory, request, opened)
    },
    private val deliver: (() -> Unit) -> Unit = { it() },
) : Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var active: Socket? = null
    private var commandGeneration = 0L
    private data class Attempt(val id: String = UUID.randomUUID().toString(), var baseline: String = "", var owner: String? = null)
    // Allocate before even the automatic first STATUS can open a transport.
    private var admission: Attempt? = Attempt()
    private val canceling = linkedMapOf<String, Attempt>()
    private val canceledOwners = mutableSetOf<String>()
    private var observedActiveEpoch: String? = null
    private var connectWanted = false

    fun start() { executor.scheduleWithFixedDelay(::poll, 0, 1, TimeUnit.SECONDS) }

    fun connect() {
        synchronized(lock) {
            if (closed.get()) return
            commandGeneration++
            connectWanted = true
            if (admission == null) admission = Attempt()
            executor.execute(::poll)
        }
    }

    private fun poll() {
        val generation = synchronized(lock) { commandGeneration }
        reconcile()
        val attempt = synchronized(lock) {
            if (closed.get() || generation != commandGeneration) return
            (admission ?: Attempt().also { admission = it })
        }
        val state = exchange(SessionControl.Request.observeAttempt(attempt.id)) ?: return
        val receipt = state.receipt
        synchronized(lock) {
            if (closed.get() || generation != commandGeneration || admission !== attempt) return
            if (receipt?.id != attempt.id || receipt.disposition != AttemptDisposition.OBSERVED) {
                if (receipt?.disposition in setOf(AttemptDisposition.RETIRED, AttemptDisposition.CANCELED)) admission = null
                return
            }
            if (state.phase == SessionPhase.UNKNOWN || !connectWanted || canceling.values.any { it.owner == null }) return
            connectWanted = false
            attempt.baseline = state.epoch
        }
        exchange(SessionControl.Request(SessionControl.Operation.START, state.epoch, attempt.id))
    }

    private fun reconcile() {
        val attempts = synchronized(lock) { canceling.values.toList() }
        attempts.forEach { attempt -> exchange(SessionControl.Request.cancelStart(attempt.baseline, attempt.id), notify = false) }
    }

    fun disconnect(epoch: String?, complete: (() -> Unit)? = null): List<String> {
        val targets = synchronized(lock) {
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            admission?.let { canceling[it.id] = it }
            listOfNotNull(epoch, admission?.id, observedActiveEpoch).distinct().also {
                canceledOwners.addAll(listOfNotNull(epoch, observedActiveEpoch))
                admission = null
            }
        }
        synchronized(lock) {
            if (!closed.get()) executor.execute {
                try {
                    targets.filterNot { synchronized(lock) { it in canceling } }.forEach {
                        exchange(SessionControl.Request(SessionControl.Operation.STOP, it), notify = false)
                    }
                    reconcile()
                } finally { complete?.invoke() }
            } else complete?.invoke()
        }
        return targets
    }

    fun command(operation: SessionControl.Operation, epoch: String) {
        synchronized(lock) {
            if (!closed.get()) executor.execute {
                val id = synchronized(lock) { admission?.id.orEmpty() }
                exchange(SessionControl.Request(operation, epoch, id))
            }
        }
    }

    private fun exchange(request: SessionControl.Request, notify: Boolean = true): SessionState? {
        if (closed.get()) return null
        val generation = synchronized(lock) { commandGeneration }
        val observation = controlObservation(request.operation)
        return try {
            val state = observedTransport(transport, request, { socket ->
                synchronized(lock) {
                    if (closed.get()) { socket.close(); error("Receiver stopped") }
                    active = socket
                }
            }, observation) ?: return null
            val current = synchronized(lock) {
                val receipt = state.receipt
                val canceled = canceling[request.startEpoch]
                if (receipt?.id == request.startEpoch) {
                    if (canceled != null) {
                        receipt.owner?.let { canceled.owner = it; canceledOwners.add(it) }
                        if (receipt.disposition == AttemptDisposition.CANCELED) canceling.remove(canceled.id)
                    } else admission?.takeIf { it.id == receipt.id }?.let { attempt ->
                        receipt.owner?.let { attempt.owner = it }
                        if (attempt.baseline.isEmpty() && state.phase != SessionPhase.UNKNOWN) attempt.baseline = receipt.owner ?: state.epoch
                    }
                }
                if (closed.get() || generation != commandGeneration) false
                else {
                    if (state.phase != SessionPhase.UNKNOWN) observedActiveEpoch = state.epoch.takeIf { state.phase.active }
                    true
                }
            }
            // A STOP reply describes reconciliation; it cannot grant attachment to any owner.
            if (current && (state.receipt?.disposition == AttemptDisposition.RETRYABLE || state.detail == "SESSION_CONTROL_STATE_UNAVAILABLE"))
                dispatch(generation, onUnavailable, observation)
            else if (notify && current && request.operation != SessionControl.Operation.STOP) dispatch(generation, {
                val attachable = synchronized(lock) {
                    val receipt = state.receipt
                    !state.phase.active || (receipt != null && receipt.id == admission?.id &&
                        receipt.disposition == AttemptDisposition.OBSERVED && receipt.owner == state.epoch &&
                        canceling.values.none { it.owner == null } && state.epoch !in canceledOwners)
                }
                if (attachable) onState(state)
                else observation?.emit("ATTACHMENT_SUPPRESSED", deliveryDisposition(generation))
            }, observation)
            state
        } catch (error: Exception) {
            controlTelemetry { if (observation?.failure !== error) observation?.clientFailure = error }
            dispatch(generation, onUnavailable, observation)
            null
        } finally {
            synchronized(lock) { active = null }
            observation?.emit("RESULT", deliveryDisposition(generation))
        }
    }

    private fun deliveryDisposition(generation: Long): String = synchronized(lock) {
        when { closed.get() -> "CLOSED"; generation != commandGeneration -> "STALE"; else -> "CURRENT" }
    }

    private fun dispatch(generation: Long, action: () -> Unit, observation: ControlObservation?) {
        deliver {
            val disposition = deliveryDisposition(generation)
            val current = disposition == "CURRENT"
            // Eligibility at this check is not acknowledgment that the callback or remote input completed.
            observation?.emit("DELIVERY_CHECK", disposition)
            if (current) action()
        }
    }

    fun close(epoch: String?) {
        val targets = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            commandGeneration++
            connectWanted = false
            runCatching { active?.close() }
            active = null
            executor.shutdownNow()
            admission?.let { canceling[it.id] = it }
            val known = listOfNotNull(epoch, observedActiveEpoch).distinct().filterNot { it in canceling }
                .map { SessionControl.Request(SessionControl.Operation.STOP, it) }
            (known + canceling.values.map { SessionControl.Request.cancelStart(it.baseline, it.id) }).also { admission = null }
        }
        // Each transport retains its existing bound. A failure cannot skip a distinct final target.
        Thread({ targets.forEach { target -> runCatching {
            val observation = controlObservation(target.operation)
            try { observedTransport(transport, target, {}, observation) }
            finally { observation?.emit("RESULT", "FINAL_STOP_NO_UI") }
        } } }, "eyeprojector-session-stop").start()
    }

    override fun close() = close(null)
}

/** Module-internal sink seam: tests can inspect the exact production formatter without Android logging. */
internal object ControlOutcomeLog {
    @Volatile var sink: (String) -> Unit = { android.util.Log.i("EyeProjectorControl", it) }
}

private inline fun controlTelemetry(action: () -> Unit) { try { action() } catch (_: Throwable) { /* Observation only. */ } }

private class ControlOpen(val observation: ControlObservation, val opened: (Socket) -> Unit) : (Socket) -> Unit {
    override fun invoke(socket: Socket) = opened(socket)
}

private fun pairedControl(directory: File, request: SessionControl.Request, opened: (Socket) -> Unit): SessionState? {
    val observation = (opened as? ControlOpen)?.observation
    controlTelemetry { observation?.source = "PEER_LOOKUP" }
    val peer = PeerStore(directory).pairedPeer()
    if (peer == null) {
        controlTelemetry { observation?.outcome = "NO_REMEMBERED_PEER" }
        return null
    }
    controlTelemetry {
        observation?.host = peer.endpoint.host
        observation?.pairingPort = peer.endpoint.pairingPort
        observation?.source = "PAIRED_CONTROL"
    }
    return SessionControl.exchange(directory, peer, request, opened).also {
        controlTelemetry { observation?.authenticated = true }
    }
}

private fun observedTransport(
    transport: (SessionControl.Request, (Socket) -> Unit) -> SessionState?,
    request: SessionControl.Request, opened: (Socket) -> Unit, observation: ControlObservation?,
): SessionState? {
    var callback = opened
    controlTelemetry { if (observation != null) callback = ControlOpen(observation, opened) }
    try {
        return transport(request, callback).also { state -> controlTelemetry {
            if (state != null) {
                observation?.phase = state.phase
                observation?.receipt = state.receipt?.disposition
                observation?.retryable = state.receipt?.disposition == AttemptDisposition.RETRYABLE
                observation?.stateUnavailable = state.detail == "SESSION_CONTROL_STATE_UNAVAILABLE"
                observation?.outcome = "RETURNED_STATE"
            } else if (observation?.outcome != "NO_REMEMBERED_PEER") observation?.outcome = "NULL_RESULT"
        } }
    } catch (error: Throwable) {
        controlTelemetry { observation?.failure = error; observation?.outcome = "EXCEPTION" }
        throw error
    }
}

private fun controlObservation(operation: SessionControl.Operation): ControlObservation? =
    try { ControlObservation(operation) } catch (_: Throwable) { null }

private class ControlObservation(val operation: SessionControl.Operation) {
    companion object { private val sequence = java.util.concurrent.atomic.AtomicLong() }
    val call = sequence.incrementAndGet() // Process-local only; unrelated to admission, wire or owner identities.
    val began = System.nanoTime()
    var source = "CUSTOM_TRANSPORT"
    var host: String? = null
    var pairingPort: Int? = null
    var authenticated = false
    var outcome = "PENDING"
    var phase: SessionPhase? = null
    var receipt: AttemptDisposition? = null
    var retryable = false
    var stateUnavailable = false
    var failure: Throwable? = null
    var clientFailure: Throwable? = null

    fun emit(event: String, disposition: String) = controlTelemetry {
        ControlOutcomeLog.sink("event=$event call=$call op=${operation.name} localStartNs=$began elapsedNs=${System.nanoTime() - began} " +
            "endpointRole=CONTROL host=${quotedHost(host)} pairingPort=$pairingPort source=$source outcome=$outcome " +
            "replyAuthentication=${if (authenticated) "CONFIRMED" else "UNOBSERVED"} phase=${phase?.name} receipt=${receipt?.name} retryable=$retryable " +
            "stateUnavailable=$stateUnavailable eligibility=$disposition error=${controlError(failure)} caughtClientError=${controlError(clientFailure)}")
    }
}

private fun quotedHost(host: String?): String = if (host == null) "null" else buildString {
    append('"')
    for (c in host) when {
        c == '"' || c == '\\' -> { append('\\'); append(c) }
        c.code < 32 || c.code == 127 -> append("\\u" + c.code.toString(16).padStart(4, '0'))
        else -> append(c)
    }
    append('"')
}

private fun controlError(error: Throwable?): String {
    if (error == null) return "NONE"
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    var next: Throwable? = error
    val facts = mutableListOf<String>()
    // This is a log-output budget only, never an application attempt or lifetime limit.
    repeat(4) {
        val current = next ?: return@repeat
        if (seen.put(current, true) != null) { next = null; return@repeat }
        val family = when (current) {
            is javax.net.ssl.SSLHandshakeException -> "SSLHandshakeException"
            is javax.net.ssl.SSLPeerUnverifiedException -> "SSLPeerUnverifiedException"
            is javax.net.ssl.SSLException -> "SSLException"
            is java.net.SocketTimeoutException -> "SocketTimeoutException"
            is java.net.ConnectException -> "ConnectException"
            is java.net.UnknownHostException -> "UnknownHostException"
            is java.net.NoRouteToHostException -> "NoRouteToHostException"
            is java.net.SocketException -> "SocketException"
            is java.io.EOFException -> "EOFException"
            is java.io.FileNotFoundException -> "FileNotFoundException"
            is java.io.IOException -> "IOException"
            is java.security.cert.CertificateException -> "CertificateException"
            is java.security.GeneralSecurityException -> "GeneralSecurityException"
            is SecurityException -> "SecurityException"
            is IllegalArgumentException -> "IllegalArgumentException"
            is IllegalStateException -> "IllegalStateException"
            is InterruptedException -> "InterruptedException"
            else -> "OTHER"
        }
        val site = current.stackTrace.firstNotNullOfOrNull { frame ->
            val owner = when (frame.className) {
                "com.code2hack.eyeprojector.protocol.SessionControl" -> if (frame.methodName in listOf("exchange", "readState", "writeRequest")) "SessionControl" else null
                "com.code2hack.eyeprojector.protocol.Tls" -> if (frame.methodName in listOf("context", "requireTrustedPeer")) "Tls" else null
                "com.code2hack.eyeprojector.protocol.Tls\$context\$pin\$1" -> if (frame.methodName in listOf("validate", "checkServerTrusted", "checkClientTrusted")) "TlsPin" else null
                "com.code2hack.eyeprojector.protocol.PeerStore" -> if (frame.methodName == "pairedPeer") "PeerStore" else null
                "com.code2hack.eyeprojector.directrg.PairedSessionClient" -> if (frame.methodName in listOf("exchange", "dispatch", "poll")) "PairedSessionClient" else null
                else -> null
            }
            owner?.let { "$it.${frame.methodName}:${frame.lineNumber}" }
        } ?: "UNKNOWN_SITE"
        facts.add("$family@$site")
        next = current.cause
    }
    return facts.joinToString(",") + if (next != null) ",MORE_OR_CYCLE" else ""
}
