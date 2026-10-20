package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import java.util.UUID

/** All entry points (Phone, paired Glasses and widget) operate the same session generation. */
class SessionController(
    private val startBackend: (String) -> Unit,
    private val stopBackend: () -> Unit,
    private val newEpoch: () -> String = { UUID.randomUUID().toString() },
    initialPhase: SessionPhase = SessionPhase.INACTIVE,
) {
    private var current = SessionState(newEpoch(), initialPhase)
    private var backendStarted = false
    private var backendEpoch: String? = null
    private var releasing = false
    private var terminalFailure: String? = null

    @Synchronized fun state(): SessionState = current

    /** A remote command carries its observed epoch; a delayed command cannot restart a newer session. */
    @Synchronized fun start(expectedEpoch: String? = null, startEpoch: String? = null, unavailable: String? = null): SessionState {
        if (expectedEpoch != null && expectedEpoch != current.epoch) return current
        if (current.phase.active || current.phase in setOf(SessionPhase.STOPPING, SessionPhase.UNKNOWN) || releasing) return current
        current = SessionState(startEpoch?.takeIf { it.isNotEmpty() } ?: newEpoch(), SessionPhase.STARTING)
        backendStarted = false
        terminalFailure = null
        if (unavailable != null) {
            backendEpoch = null
            current = current.copy(phase = SessionPhase.FAILED, detail = unavailable)
            return current
        }
        backendEpoch = current.epoch
        startBackend(current.epoch)
        return current
    }

    @Synchronized fun stop(expectedEpoch: String? = null): SessionState {
        if (expectedEpoch != null && expectedEpoch != current.epoch) return current
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
                releasing = false
            } else if (epoch.isNotEmpty()) backendEpoch = epoch
            return
        }
        if (backendEpoch != null || current.phase !in setOf(SessionPhase.INACTIVE, SessionPhase.UNKNOWN)) return
        if (state in setOf("STOPPED", "FAILED")) {
            current = current.copy(phase = if (state == "STOPPED") SessionPhase.INACTIVE else SessionPhase.FAILED)
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
        backendStarted = true
        releasing = phase == SessionPhase.STOPPING
        current = SessionState(epoch, phase)
    }

}
