package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.IOException
import java.util.UUID

/** Phone and paired Receiver commands share one owner and a durable admission/cancellation ledger. */
class SessionController(
    private val startBackend: (String) -> Unit,
    private val stopBackend: () -> Unit,
    private val newEpoch: () -> String = { UUID.randomUUID().toString() },
    initialPhase: SessionPhase = SessionPhase.INACTIVE,
    initialCorrelation: Correlation = Correlation(),
    private val retainCorrelation: (Correlation) -> Unit = {},
) {
    data class Binding(
        val baseline: String, val owner: String?, val canceled: Boolean = false,
        val unresolved: Boolean = false, val retired: Boolean = false,
    )
    data class Correlation(
        val owner: String? = null, val baseline: String? = null, val attempts: Map<String, Binding> = emptyMap(),
        val stopOwner: String? = null, val stopUnresolved: Boolean = false,
    )
    private var current = SessionState(newEpoch(), initialPhase)
    private var backendStarted = false
    private var backendEpoch: String? = null
    private var recoveredOwner = initialCorrelation.owner
    private var ownerBase = initialCorrelation.baseline
    private var releasing = false
    private var terminalFailure: String? = null
    private val attempts = initialCorrelation.attempts.toMutableMap()
    private var stopOwner = initialCorrelation.stopOwner
    private var stopUnresolved = initialCorrelation.stopUnresolved
    private var stopIssued: String? = null
    private var retained = initialCorrelation
    private var retentionFailed = false

    private fun correlation() = Correlation(backendEpoch ?: recoveredOwner, ownerBase, attempts.toMap(), stopOwner, stopUnresolved)

    /** The desired state remains retryable after an irreversible backend observation. */
    private fun retain(): Boolean = try {
        val value = correlation()
        if (value != retained) { retainCorrelation(value); retained = value }
        retentionFailed = false
        true
    } catch (_: IOException) {
        retentionFailed = true
        false
    }

    private fun actualOwner() = backendEpoch ?: recoveredOwner
    private fun associatedOwner(baseline: String): String? = actualOwner()?.takeIf { it == baseline || ownerBase == baseline }

    private fun refresh(binding: Binding): Binding {
        if (binding.retired) return binding
        val owner = actualOwner()
        if (binding.unresolved) {
            if (current.phase == SessionPhase.UNKNOWN) return binding
            if (binding.baseline.isNotEmpty()) {
                val associated = associatedOwner(binding.baseline)
                return binding.copy(owner = associated, unresolved = false,
                    retired = associated == null && (binding.canceled || binding.baseline != current.epoch))
            }
            return if (owner != null) binding.copy(baseline = ownerBase ?: owner, owner = owner, unresolved = false)
            else binding.copy(baseline = current.epoch, unresolved = false, retired = binding.canceled)
        }
        if (binding.owner != null) return if (binding.owner == owner) binding else binding.copy(owner = null, retired = true)
        if (binding.canceled) return binding.copy(retired = true)
        associatedOwner(binding.baseline)?.let { return binding.copy(owner = it) }
        return if (current.phase != SessionPhase.UNKNOWN && binding.baseline != current.epoch)
            binding.copy(retired = true) else binding
    }

    private fun refreshAttempts() {
        attempts.replaceAll { _, binding -> refresh(binding) }
        if (stopUnresolved && current.phase != SessionPhase.UNKNOWN) {
            stopOwner = actualOwner()
            stopUnresolved = false
        }
        if (stopOwner != null && actualOwner() != stopOwner && current.phase != SessionPhase.UNKNOWN) stopOwner = null
    }

    private fun observe(id: String, baseline: String = ""): Binding {
        val existing = attempts[id]
        if (existing != null) return refresh(existing).also { attempts[id] = it }
        val unknown = current.phase == SessionPhase.UNKNOWN
        val owner = if (baseline.isEmpty()) actualOwner() else associatedOwner(baseline)
        return Binding(baseline.ifEmpty { if (unknown) "" else current.epoch }, owner,
            unresolved = unknown && owner == null,
            retired = !unknown && baseline.isNotEmpty() && baseline != current.epoch && owner == null)
            .also { attempts[id] = it }
    }

    /** Dispatch only after the intent is retained; a recreated Phone derives this again from its ledger. */
    private fun driveCancellation() {
        val owner = backendEpoch ?: return
        if (current.phase == SessionPhase.UNKNOWN) return
        if (owner != stopOwner && attempts.values.none { it.canceled && !it.retired && it.owner == owner }) return
        if (!retain()) return
        if (stopIssued == owner) return
        stopIssued = owner
        releasing = true
        current = SessionState(newEpoch(), if (terminalFailure == null) SessionPhase.STOPPING else SessionPhase.FAILED,
            terminalFailure.orEmpty())
        stopBackend()
    }

    private fun reply(id: String = ""): SessionState {
        val value = if (retentionFailed) current.copy(detail = STORAGE_FAILURE) else current
        if (id.isEmpty()) return value
        val binding = attempts[id]
        val disposition = when {
            retentionFailed -> AttemptDisposition.RETRYABLE
            binding == null -> AttemptDisposition.RETRYABLE
            binding.retired -> if (binding.canceled) AttemptDisposition.CANCELED else AttemptDisposition.RETIRED
            binding.canceled -> AttemptDisposition.CANCEL_PENDING
            else -> AttemptDisposition.OBSERVED
        }
        return value.copy(receipt = AttemptReceipt(id, binding?.owner, disposition))
    }

    /** The first STATUS is an admission, before the Receiver knows any owner or proposes a Start. */
    @Synchronized fun command(request: SessionControl.Request, unavailable: String? = null): SessionState {
        refreshAttempts()
        retain()
        driveCancellation()
        val id = request.startEpoch
        when (request.operation) {
            SessionControl.Operation.STATUS -> if (id.isNotEmpty()) { observe(id); retain(); driveCancellation() }
            SessionControl.Operation.START -> start(request.epoch, id, unavailable)
            SessionControl.Operation.STOP -> if (id.isNotEmpty()) cancelStart(request.epoch, id) else stop(request.epoch)
            SessionControl.Operation.CONNECTING -> receiverConnecting(request.epoch)
            SessionControl.Operation.RENDERED -> receiverRendered(request.epoch)
            SessionControl.Operation.FAILED -> if (current.epoch == request.epoch) backend(request.epoch, "FAILED", "RECEIVER_FAILED")
        }
        return reply(id)
    }

    @Synchronized fun cancelStart(expected: String, proposal: String): SessionState {
        val binding = observe(proposal, expected)
        if (!binding.retired && expected.isNotEmpty() && binding.baseline != expected && binding.owner != expected) return reply(proposal)
        attempts[proposal] = binding.copy(canceled = true, retired = binding.retired || (!binding.unresolved && binding.owner == null))
        if (retain()) driveCancellation()
        return reply(proposal)
    }

    @Synchronized fun state(): SessionState {
        refreshAttempts()
        retain()
        driveCancellation()
        return reply()
    }

    @Synchronized fun start(expectedEpoch: String? = null, startEpoch: String? = null, unavailable: String? = null): SessionState {
        refreshAttempts()
        val id = startEpoch.orEmpty()
        val binding = if (id.isNotEmpty()) observe(id, expectedEpoch.orEmpty()) else null
        if (binding != null && (binding.canceled || binding.retired || binding.owner != null || binding.unresolved)) {
            retain(); driveCancellation(); return reply(id)
        }
        if (expectedEpoch != null && expectedEpoch != current.epoch || current.phase.active ||
            current.phase in setOf(SessionPhase.STOPPING, SessionPhase.UNKNOWN) || releasing || actualOwner() != null) {
            retain(); driveCancellation(); return reply(id)
        }
        val previous = current
        val previousCorrelation = correlation()
        val previousAttempts = attempts.toMap()
        val epoch = id.ifEmpty { newEpoch() }
        current = SessionState(epoch, if (unavailable == null) SessionPhase.STARTING else SessionPhase.FAILED, unavailable.orEmpty())
        ownerBase = previous.epoch
        backendEpoch = epoch.takeIf { unavailable == null }
        recoveredOwner = null
        if (id.isNotEmpty()) attempts[id] = Binding(previous.epoch, backendEpoch, retired = unavailable != null)
        if (!retain()) {
            current = previous.copy(phase = SessionPhase.FAILED, detail = STORAGE_FAILURE)
            ownerBase = previousCorrelation.baseline; backendEpoch = previousCorrelation.owner
            recoveredOwner = null
            attempts.clear(); attempts.putAll(previousAttempts)
            return reply(id)
        }
        backendStarted = false
        terminalFailure = null
        if (unavailable == null) startBackend(epoch)
        return reply(id)
    }

    @Synchronized fun stop(expectedEpoch: String? = null): SessionState {
        val owner = expectedEpoch?.let { attempts[it]?.owner ?: it }
        if (owner != null && owner != actualOwner() && owner != current.epoch) return reply()
        if (current.phase == SessionPhase.INACTIVE) return reply()
        stopOwner = actualOwner()
        stopUnresolved = current.phase == SessionPhase.UNKNOWN && stopOwner == null
        if (retain()) {
            driveCancellation()
            // Unknown ownership is reconciled by restore, never reported as released.
            if (stopUnresolved) current = current.copy(detail = "STOP_PENDING_RECONCILIATION")
            else if (stopOwner == null) release()
        }
        return reply()
    }

    @Synchronized fun receiverConnecting(epoch: String): SessionState {
        if (epoch == current.epoch && current.phase == SessionPhase.AVAILABLE) current = current.copy(phase = SessionPhase.CONNECTING)
        return reply()
    }

    @Synchronized fun receiverRendered(epoch: String): SessionState {
        if (epoch == current.epoch && current.phase in setOf(SessionPhase.AVAILABLE, SessionPhase.CONNECTING)) current = current.copy(phase = SessionPhase.STREAMING)
        return reply()
    }

    @Synchronized fun backend(epoch: String, state: String, detail: String = ""): SessionState {
        if (epoch != backendEpoch) return reply()
        if (releasing && state !in setOf("STOPPED", "STOP_UNVERIFIED", "STOPPING")) return reply()
        when (state) {
            "STARTING" -> backendStarted = true
            "READY", "SESSION_STARTING", "STREAMING" -> {
                backendStarted = true
                if (current.phase in setOf(SessionPhase.STARTING, SessionPhase.UNKNOWN) ||
                    state != "READY" && current.phase in setOf(SessionPhase.AVAILABLE, SessionPhase.CONNECTING)) current = current.copy(
                    phase = if (state == "READY") SessionPhase.AVAILABLE else SessionPhase.CONNECTING, detail = "")
            }
            "STOPPED" -> if (releasing || backendStarted) release(detail)
            "FAILED" -> failAndStop(detail)
            "UNKNOWN" -> if (detail in setOf("SERVICE_DIED", "SERVICE_DISCONNECTED")) failAndStop(detail)
                else current = current.copy(phase = SessionPhase.UNKNOWN, detail = detail)
            "STOPPING", "STOP_UNVERIFIED" -> current = current.copy(
                phase = if (terminalFailure != null) SessionPhase.FAILED else SessionPhase.STOPPING, detail = terminalFailure ?: detail)
        }
        refreshAttempts()
        retain()
        driveCancellation()
        return reply()
    }

    private fun failAndStop(detail: String) {
        current = current.copy(phase = SessionPhase.FAILED, detail = detail)
        terminalFailure = detail
        stopOwner = actualOwner()
        if (retain()) driveCancellation()
    }

    private fun release(detail: String = "") {
        current = SessionState(newEpoch(), if (terminalFailure == null) SessionPhase.INACTIVE else SessionPhase.FAILED, terminalFailure ?: detail)
        backendEpoch = null; recoveredOwner = null; ownerBase = null
        backendStarted = false; releasing = false; terminalFailure = null; stopIssued = null
        stopOwner = null; stopUnresolved = false
        refreshAttempts()
        retain() // STOPPED is authoritative even if its retirement cannot yet be saved.
    }

    @Synchronized fun rejected(epoch: String, detail: String) {
        if (epoch == current.epoch && current.phase == SessionPhase.STARTING) {
            current = current.copy(phase = SessionPhase.FAILED, detail = detail)
            backendEpoch = null
            refreshAttempts(); retain()
        }
    }

    /** Actual service ownership is resolved before retained cancellation may dispatch or expose AVAILABLE. */
    @Synchronized fun restore(epoch: String, state: String) = restoreBackend(epoch, state, "BACKEND_FAILED")

    private fun restoreBackend(epoch: String, state: String, detail: String) {
        if (backendEpoch != null || current.phase !in setOf(SessionPhase.INACTIVE, SessionPhase.UNKNOWN, SessionPhase.STOPPING)) return
        if (state == "STOPPED") { release(); return }
        if (epoch.isEmpty()) {
            if (state == "FAILED") current = current.copy(phase = SessionPhase.UNKNOWN, detail = "BACKEND_RELEASE_UNCONFIRMED")
            return
        }
        val phase = when (state) {
            "STARTING" -> SessionPhase.STARTING
            "READY" -> SessionPhase.AVAILABLE
            "SESSION_STARTING", "STREAMING" -> SessionPhase.CONNECTING
            "STOPPING", "STOP_UNVERIFIED" -> SessionPhase.STOPPING
            "FAILED" -> SessionPhase.FAILED
            else -> return
        }
        backendEpoch = epoch
        ownerBase = ownerBase.takeIf { recoveredOwner == epoch }
        recoveredOwner = null
        backendStarted = true
        releasing = phase == SessionPhase.STOPPING
        current = SessionState(epoch, phase)
        refreshAttempts()
        retain()
        if (state == "FAILED") failAndStop(detail) else driveCancellation()
    }

    /** Shared by the real main-thread bootstrap observer and its host adapter regression. */
    @Synchronized fun observeBackend(epoch: String, state: String, detail: String = "", rejectedEpoch: String? = null) {
        rejectedEpoch?.let { rejected(it, detail.ifEmpty { "START_REJECTED" }) }
        restoreBackend(epoch, state, detail.ifEmpty { "BACKEND_FAILED" })
        backend(epoch, state, detail)
        if (state == "FAILED" || detail == "SESSION_SETUP_REQUIRED") backend(epoch, "FAILED", detail)
    }

    companion object { private const val STORAGE_FAILURE = "SESSION_CONTROL_STATE_UNAVAILABLE" }
}
