package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import com.code2hack.eyeprojector.protocol.SessionControl
import java.util.UUID
import java.io.IOException

/** All entry points (Phone, paired Glasses and widget) operate the same session generation. */
class SessionController(
    private val startBackend: (String) -> Unit,
    private val stopBackend: () -> Unit,
    private val newEpoch: () -> String = { UUID.randomUUID().toString() },
    initialPhase: SessionPhase = SessionPhase.INACTIVE,
    initialCorrelation: Correlation = Correlation(),
    private val retainCorrelation: (Correlation) -> Unit = {},
) {
    data class Binding(val baseline: String, val owner: String?)
    data class Correlation(val owner: String? = null, val baseline: String? = null, val attempts: Map<String, Binding> = emptyMap())
    private var current = SessionState(newEpoch(), initialPhase)
    private var backendStarted = false
    private var backendEpoch: String? = null
    private var releasing = false
    private var terminalFailure: String? = null
    private data class Attempt(val base: String, val owner: String?)
    private val attempts = initialCorrelation.attempts.mapValues { (_, value) -> Attempt(value.baseline, value.owner) }.toMutableMap()
    private var ownerBase: String? = initialCorrelation.baseline
    private var recoveredOwner: String? = initialCorrelation.owner
    private var retained = initialCorrelation

    private fun persistCorrelation() {
        val value = Correlation(backendEpoch ?: recoveredOwner, ownerBase,
            attempts.mapValues { (_, attempt) -> Binding(attempt.base, attempt.owner) })
        if (value != retained) { retainCorrelation(value); retained = value }
    }

    /** Attempt identities live with their baseline/owner, rather than a clock or an arbitrary cache limit. */
    private fun retireAttempts() {
        attempts.entries.removeAll { (_, attempt) ->
            attempt.base != current.epoch && attempt.base != ownerBase &&
                (attempt.owner == null || (attempt.owner != backendEpoch && attempt.owner != recoveredOwner))
        }
    }

    private fun associate(expected: String?, proposal: String?, retain: Boolean = true) {
        if (expected == null || proposal.isNullOrEmpty() || proposal in attempts) return
        associatedOwner(expected)?.let { owner ->
            attempts[proposal] = Attempt(expected, owner)
            if (retain) persistCorrelation()
        }
    }

    private fun associatedOwner(expected: String): String? {
        if (!current.phase.active && current.phase != SessionPhase.UNKNOWN) return null
        val owner = backendEpoch ?: recoveredOwner ?: current.epoch.takeIf { current.phase.active } ?: return null
        return owner.takeIf { expected == owner || expected == ownerBase }
    }

    /** Both the real Phone adapter and host regressions use this same command dispatch. */
    @Synchronized fun command(request: SessionControl.Request, unavailable: String? = null): SessionState = when (request.operation) {
        SessionControl.Operation.STATUS -> state()
        SessionControl.Operation.START -> start(request.epoch, request.startEpoch, unavailable)
        SessionControl.Operation.STOP -> if (request.startEpoch.isNotEmpty()) cancelStart(request.epoch, request.startEpoch) else stop(request.epoch)
        SessionControl.Operation.CONNECTING -> receiverConnecting(request.epoch)
        SessionControl.Operation.RENDERED -> receiverRendered(request.epoch)
        SessionControl.Operation.FAILED -> {
            if (current.epoch == request.epoch) backend(request.epoch, "FAILED", "RECEIVER_FAILED")
            current
        }
    }

    /** Cancel B's associated owner only. A late B cannot bind to an unrelated, newer owner C. */
    @Synchronized fun cancelStart(expected: String, proposal: String): SessionState {
        retireAttempts()
        val attempt = attempts[proposal] ?: Attempt(expected, associatedOwner(expected)).also { attempts[proposal] = it }
        if (attempt.base != expected) return current
        persistCorrelation() // Durable before acknowledging cancellation, including STOP-before-START.
        return attempt.owner?.let(::stop) ?: current
    }

    @Synchronized fun state(): SessionState = current

    /** A remote command carries its observed epoch; a delayed command cannot restart a newer session. */
    @Synchronized fun start(expectedEpoch: String? = null, startEpoch: String? = null, unavailable: String? = null): SessionState {
        retireAttempts()
        if (startEpoch != null && startEpoch in attempts) return current
        if (expectedEpoch != null && expectedEpoch != current.epoch) {
            associate(expectedEpoch, startEpoch)
            return current
        }
        if (current.phase.active || current.phase in setOf(SessionPhase.STOPPING, SessionPhase.UNKNOWN) || releasing) {
            associate(expectedEpoch, startEpoch)
            return current
        }
        val previous = current
        val previousBase = ownerBase
        val previousAttempts = attempts.toMap()
        val previousBackend = backendEpoch
        val previousStarted = backendStarted
        val previousFailure = terminalFailure
        try {
            current = SessionState(startEpoch?.takeIf { it.isNotEmpty() } ?: newEpoch(), SessionPhase.STARTING)
            ownerBase = previous.epoch
            retireAttempts()
            associate(expectedEpoch, startEpoch, retain = false)
            backendStarted = false
            terminalFailure = null
            backendEpoch = if (unavailable == null) current.epoch else null
            if (unavailable != null) current = current.copy(phase = SessionPhase.FAILED, detail = unavailable)
            persistCorrelation() // Record the owner/base before dispatching a surviving backend.
        } catch (_: IOException) {
            attempts.clear(); attempts.putAll(previousAttempts)
            ownerBase = previousBase; backendEpoch = previousBackend
            backendStarted = previousStarted; terminalFailure = previousFailure
            current = previous.copy(phase = SessionPhase.FAILED, detail = "SESSION_CONTROL_STATE_UNAVAILABLE")
            return current
        }
        if (unavailable != null) return current
        startBackend(current.epoch)
        return current
    }

    @Synchronized fun stop(expectedEpoch: String? = null): SessionState {
        retireAttempts()
        val attempt = expectedEpoch?.let(attempts::get)
        val owner = attempt?.owner ?: expectedEpoch
        if (owner != null && owner != current.epoch) return current
        if (current.phase in setOf(SessionPhase.STOPPING, SessionPhase.INACTIVE)) return current
        current = SessionState(newEpoch(), SessionPhase.STOPPING)
        terminalFailure = null
        releasing = true
        stopBackend()
        return current
    }

    @Synchronized fun receiverConnecting(epoch: String): SessionState {
        if (epoch == current.epoch && current.phase == SessionPhase.AVAILABLE)
            current = current.copy(phase = SessionPhase.CONNECTING)
        return current
    }

    @Synchronized fun receiverRendered(epoch: String): SessionState {
        if (epoch == current.epoch && current.phase in setOf(SessionPhase.AVAILABLE, SessionPhase.CONNECTING))
            current = current.copy(phase = SessionPhase.STREAMING)
        return current
    }

    @Synchronized fun backend(epoch: String, state: String, detail: String = ""): SessionState {
        if (epoch != backendEpoch) return current
        if (releasing && state !in setOf("STOPPED", "STOP_UNVERIFIED", "STOPPING")) return current
        when (state) {
            "STARTING" -> if (current.phase.active) backendStarted = true
            "READY" -> if (current.phase.active || current.phase == SessionPhase.UNKNOWN) {
                backendStarted = true
                if (current.phase in setOf(SessionPhase.STARTING, SessionPhase.UNKNOWN)) current = current.copy(phase = SessionPhase.AVAILABLE)
            }
            "SESSION_STARTING", "STREAMING" -> if (current.phase.active || current.phase == SessionPhase.UNKNOWN) {
                backendStarted = true
                if (current.phase != SessionPhase.STREAMING) current = current.copy(phase = SessionPhase.CONNECTING)
            }
            "STOPPED" -> if (releasing || backendStarted) {
                current = SessionState(newEpoch(), if (terminalFailure != null) SessionPhase.FAILED else SessionPhase.INACTIVE, terminalFailure ?: detail)
                backendStarted = false
                backendEpoch = null
                ownerBase = null
                recoveredOwner = null
                releasing = false
                terminalFailure = null
            }
            "FAILED" -> {
                current = current.copy(phase = SessionPhase.FAILED, detail = detail)
                terminalFailure = detail
                releasing = true
                stopBackend()
            }
            "UNKNOWN" -> {
                if (detail in setOf("SERVICE_DIED", "SERVICE_DISCONNECTED")) {
                    current = current.copy(phase = SessionPhase.FAILED, detail = detail)
                    terminalFailure = detail
                    releasing = true
                    stopBackend()
                } else current = current.copy(phase = SessionPhase.UNKNOWN, detail = detail)
            }
            "STOPPING", "STOP_UNVERIFIED" -> current = current.copy(
                phase = if (terminalFailure != null) SessionPhase.FAILED else SessionPhase.STOPPING,
                detail = terminalFailure ?: detail,
            )
        }
        retireAttempts()
        persistCorrelation()
        return current
    }

    @Synchronized fun rejected(epoch: String, detail: String) {
        if (epoch == current.epoch && current.phase == SessionPhase.STARTING) {
            current = current.copy(phase = SessionPhase.FAILED, detail = detail)
            backendEpoch = null
        }
    }

    /** Reattach to the authoritative shell service after an ordinary app-process restart. */
    @Synchronized fun restore(epoch: String, state: String) {
        if (backendEpoch == null && current.phase == SessionPhase.STOPPING) {
            if (state == "STOPPED") {
                current = SessionState(newEpoch(), SessionPhase.INACTIVE)
                ownerBase = null
                recoveredOwner = null
                releasing = false
                retireAttempts()
                persistCorrelation()
            } else if (epoch.isNotEmpty()) backendEpoch = epoch
            return
        }
        if (backendEpoch != null || current.phase !in setOf(SessionPhase.INACTIVE, SessionPhase.UNKNOWN)) return
        if (state in setOf("STOPPED", "FAILED")) {
            current = current.copy(phase = if (state == "STOPPED") SessionPhase.INACTIVE else SessionPhase.FAILED)
            ownerBase = null
            recoveredOwner = null
            retireAttempts()
            persistCorrelation()
            return
        }
        if (epoch.isEmpty()) return
        val phase = when (state) {
            "STARTING" -> SessionPhase.STARTING
            "READY" -> SessionPhase.AVAILABLE
            "SESSION_STARTING" -> SessionPhase.CONNECTING
            "STREAMING" -> SessionPhase.CONNECTING
            "STOPPING", "STOP_UNVERIFIED" -> SessionPhase.STOPPING
            else -> return
        }
        backendEpoch = epoch
        ownerBase = ownerBase.takeIf { recoveredOwner == epoch }
        recoveredOwner = null
        backendStarted = true
        releasing = phase == SessionPhase.STOPPING
        current = SessionState(epoch, phase)
        retireAttempts()
        persistCorrelation()
    }

}
