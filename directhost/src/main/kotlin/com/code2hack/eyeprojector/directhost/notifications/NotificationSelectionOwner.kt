package com.code2hack.eyeprojector.directhost.notifications

import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.activation.*
import java.io.Closeable

/** Observation seam for deterministic ownership checks; production uses the captured concrete facade. */
internal interface NotificationSelectionPort {
    val facade: PhoneSessionController
    fun admit(selection: NotificationSelection, snapshot: NotificationPolicy.Snapshot): ActivationHandle?
    fun observe(handle: ActivationHandle, receive: (ActivationState<ProjectionContext>) -> Unit): Closeable
    fun ready(handle: ActivationHandle, receive: (ActivationReady<ProjectionContext>) -> Unit): Closeable
    fun submit(carrier: NotificationLaunchCarrier, settled: (NotificationLaunchSettlement) -> Unit)
}

internal class FacadeNotificationPort(override val facade: PhoneSessionController) : NotificationSelectionPort {
    override fun admit(selection: NotificationSelection, snapshot: NotificationPolicy.Snapshot) = facade.admitNotification(selection, snapshot)
    override fun observe(handle: ActivationHandle, receive: (ActivationState<ProjectionContext>) -> Unit) = facade.observeActivation(handle, receive)
    override fun ready(handle: ActivationHandle, receive: (ActivationReady<ProjectionContext>) -> Unit) = facade.whenActivationReady(handle, receive)
    override fun submit(carrier: NotificationLaunchCarrier, settled: (NotificationLaunchSettlement) -> Unit) = facade.launchNotification(carrier, settled)
}

/** Main-owner G/R/S state only; no retry timer, backend, fresh D or replacement permit. */
internal class NotificationSelectionOwner(
    private val port: NotificationSelectionPort,
    private val policy: NotificationPolicy,
    private val readEligibility: (String) -> Pair<Boolean, Boolean>,
    private val post: (() -> Unit) -> Unit,
    private val changed: () -> Unit = {},
) : Closeable {
    private data class ReadyKey(val handle: ActivationHandle, val attempt: ActivationAttempt,
        val target: ProjectionContext, val binding: ActivationBinding?, val authority: Long)
    private class Arm(val requestedAt: ReadyKey?) {
        var returned = false
        var registrationReturned = false
        var handle: Closeable? = null
        var fired = false
        var closing = false
        var detached = false
        var value: ActivationReady<ProjectionContext>? = null
    }
    private class Submission(val carrier: NotificationLaunchCarrier) {
        var returned = false
        var settlement: NotificationLaunchSettlement? = null
    }
    private class Current(val selection: NotificationSelection, val handle: ActivationHandle) {
        var closed = false
        var stream: Closeable? = null
        var streamReturned = false
        var streamRegistrationReturned = false
        var streamClosing = false
        var streamDetached = false
        var latest: ReadyKey? = null
        var lastTried: ReadyKey? = null
        var blockedHint: ReadyKey? = null
        var armedOnce = false
        var arm: Arm? = null
        var submission: Submission? = null
        var fault: NotificationDispatchOutcome? = null
        var faultAt: ReadyKey? = null
    }
    private var current: Current? = null
    // Retain original cleanup obligations, including unknown exceptional ports.
    // This set never gates admission or registration for a different G.
    private val retiringOwners = mutableSetOf<Current>()
    private var latestAcceptedReceipt = -1L
    private var draining = false
    private var again = false
    private val policyObservation = policy.observe { post { drain(); changed() } }
    var lastOutcome: NotificationDispatchOutcome? = null; private set
    val failed: Boolean get() = current?.fault != null || lastOutcome?.faultStage != null || policy.persistenceFailed

    fun receipt(packageName: String) = policy.receipt(packageName)
    fun access(approval: NotificationAccessApproval) = policy.access(approval)

    fun accept(observation: NotificationIngressObservation) {
        val result = observation.event
        if (result.kind == NotificationEventKind.REMOVED_CURRENT) {
            current?.let { original ->
                val selected = original.selection.observation
                // Cross-ingress equality/absence is not proof of matching removal.
                if (observation.receipt > selected.receipt && selected.scope.connection === observation.scope.connection &&
                    selected.event.record == result.record)
                    policy.revoke(original.selection)
            }
            drain()
            return
        }
        if (result.kind !in setOf(NotificationEventKind.NEW_RECORD, NotificationEventKind.UPDATED_OR_REPOSTED)) return
        if (observation.receipt <= latestAcceptedReceipt) return
        val originalTicket = observation.admission ?: return
        val prepared = policy.prepare(originalTicket) { readEligibility(result.record.packageName) } ?: return
        val selection = NotificationSelection(observation, port.facade)
        val handle = port.admit(selection, prepared) ?: return
        if (selection.permit.state != LaunchCommitPermit.State.PENDING) return
        latestAcceptedReceipt = observation.receipt
        lastOutcome = null
        current?.let(::detach)
        // close/changed are external boundaries. A newer receipt admitted during
        // detachment owns the selection, even when this outer invocation resumes.
        if (selection.permit.state != LaunchCommitPermit.State.PENDING || latestAcceptedReceipt != observation.receipt) {
            policy.release(selection)
            return
        }
        val original = Current(selection, handle)
        current = original
        try {
            val stream = port.observe(handle) { value ->
                if (current === original && !original.closed) {
                    original.latest = if (value.phase == ActivationPhase.READY && value.attempt != null && value.target != null &&
                        value.binding != null && value.authority != 0L) ReadyKey(handle, value.attempt, value.target, value.binding, value.authority) else null
                    if (value.phase == ActivationPhase.CANCELED) selection.permit.retire()
                    drain()
                }
            }
            original.streamReturned = true
            original.streamRegistrationReturned = true
            original.stream = stream // A late return still belongs to this original G.
            if (original.closed) detachStream(original)
        } catch (_: Exception) {
            original.fault = NotificationDispatchOutcome(NotificationCommitment.ADAPTER_FAULT,
                faultStage = NotificationFaultStage.PRE_CALL,
                faultCode = if (original.streamRegistrationReturned) "STREAM_CLOSE_UNRESOLVED" else "OBSERVATION_UNRESOLVED")
            if (current === original) changed()
        } finally {
            original.streamReturned = true
            releaseRetired(original)
        }
        drain()
    }

    private fun key(ready: ActivationReady<ProjectionContext>) = ReadyKey(ready.handle, ready.attempt, ready.target, ready.binding, ready.authority)
    private fun drain() {
        if (draining) { again = true; return }
        draining = true
        try {
            do {
                again = false
                val original = current ?: break
                val selection = original.selection
                if (selection.permit.state != LaunchCommitPermit.State.PENDING) { detach(original); continue }
                if (!original.streamReturned || !original.streamRegistrationReturned) continue
                val submission = original.submission
                if (submission != null) {
                    if (!submission.returned || submission.settlement == null) continue
                    if (original.submission !== submission) continue
                    original.submission = null
                    val outcome = submission.settlement!!.outcome
                    lastOutcome = outcome
                    original.fault = null
                    original.faultAt = null
                    if (selection.permit.state != LaunchCommitPermit.State.PENDING) { detach(original); continue }
                    if (outcome.commitment == NotificationCommitment.ADAPTER_FAULT) {
                        original.fault = outcome
                        original.faultAt = key(submission.carrier.ready)
                        changed()
                    }
                    again = true
                    continue
                }
                if (original.fault != null) {
                    // A real complete same-D Ready publication is the recovery
                    // provenance. PENDING/null/fault alone never schedules a retry.
                    // S was resolved above and R must have returned and detached.
                    val hint = original.latest
                    if (original.arm != null || hint == null || hint == original.faultAt) continue
                    original.fault = null
                    original.faultAt = null
                    changed()
                    if (current !== original || original.closed || selection.permit.state != LaunchCommitPermit.State.PENDING) {
                        again = true
                        continue
                    }
                }
                val arm = original.arm
                if (arm != null) {
                    if (!arm.returned || !arm.fired) continue
                    try { if (!detachArm(original, arm)) continue }
                    catch (_: Exception) {
                        original.fault = NotificationDispatchOutcome(NotificationCommitment.ADAPTER_FAULT,
                            faultStage = NotificationFaultStage.PRE_CALL, faultCode = "CLOSE_UNRESOLVED")
                        original.faultAt = arm.requestedAt
                        changed()
                        continue
                    }
                    if (current !== original || original.arm !== arm) continue
                    original.arm = null
                    val ready = arm.value ?: continue
                    val readyKey = key(ready)
                    if (ready.handle !== original.handle || ready.binding == null || ready.authority == 0L) {
                        original.fault = NotificationDispatchOutcome(NotificationCommitment.ADAPTER_FAULT,
                            faultStage = NotificationFaultStage.PRE_CALL, faultCode = "INVALID_READY")
                        original.faultAt = arm.requestedAt
                        changed(); again = true; continue
                    }
                    if (readyKey == original.lastTried) { original.blockedHint = original.latest; continue }
                    val carrier = NotificationLaunchCarrier(selection, ready, Any())
                    val sent = Submission(carrier)
                    original.lastTried = readyKey
                    original.submission = sent // Before invocation, including inline settlement.
                    try {
                        port.submit(carrier) { settlement ->
                            if (settlement.carrier === carrier && sent.settlement == null) sent.settlement = settlement
                            settleDetached(original, sent)
                            if (current === original && original.submission === sent) drain()
                        }
                    } catch (error: Exception) {
                        if (sent.settlement == null) {
                            // An escaped invocation does not establish lane release.
                            // Production coordinator owns failure settlement; retain
                            // S until its matching receipt actually arrives.
                            original.fault = NotificationDispatchOutcome.fault(carrier, NotificationFaultStage.COORDINATOR, "SUBMISSION_UNRESOLVED")
                            original.faultAt = readyKey
                            if (selection.observation.receipt == latestAcceptedReceipt) lastOutcome = original.fault
                        }
                    } finally { sent.returned = true; settleDetached(original, sent) }
                    again = true
                    changed()
                    continue
                }
                val hint = original.latest
                if (original.armedOnce && (hint == null || hint == original.lastTried || hint == original.blockedHint)) continue
                val next = Arm(original.latest)
                original.arm = next // Original R installed BEFORE registration can reenter.
                original.armedOnce = true
                try {
                    val registration = port.ready(original.handle) { ready ->
                        if (current === original && original.arm === next && !original.closed && !next.fired) {
                            next.fired = true; next.value = ready
                            drain()
                        }
                    }
                    next.returned = true; next.registrationReturned = true
                    next.handle = registration
                    if (current !== original || original.arm !== next || original.closed || next.fired) detachArm(original, next)
                } catch (error: Exception) {
                    next.returned = true
                    // No returned handle means detachment cannot be established.
                    // The facade has no exceptional cleanup receipt; keep this R
                    // unresolved rather than pretend a successor arm is safe.
                    original.fault = NotificationDispatchOutcome(NotificationCommitment.ADAPTER_FAULT,
                        faultStage = NotificationFaultStage.PRE_CALL,
                        faultCode = if (next.registrationReturned) "CLOSE_UNRESOLVED" else "REGISTRATION_UNRESOLVED")
                    original.faultAt = next.requestedAt
                    changed()
                } finally {
                    releaseRetired(original)
                }
                if (next.fired) again = true
            } while (again)
        } finally { draining = false }
    }

    private fun detachArm(original: Current, arm: Arm): Boolean {
        if (!arm.returned || !arm.registrationReturned || arm.closing) return false
        if (arm.detached) return true
        val registration = arm.handle ?: return false
        arm.closing = true
        try {
            registration.close()
            arm.detached = true
            arm.handle = null
            return true
        } finally { arm.closing = false; releaseRetired(original) }
    }
    private fun detachStream(original: Current): Boolean {
        if (!original.streamReturned || !original.streamRegistrationReturned || original.streamClosing) return false
        if (original.streamDetached) return true
        val registration = original.stream ?: return false
        original.streamClosing = true
        try {
            registration.close()
            original.streamDetached = true
            original.stream = null
            return true
        } finally { original.streamClosing = false; releaseRetired(original) }
    }
    private fun detach(original: Current) {
        if (original.closed) return
        original.closed = true
        if (current === original) current = null
        original.selection.permit.retire()
        retiringOwners.add(original) // Before close/changed can reenter with a newer G.
        runCatching { detachStream(original) }
        original.arm?.let { runCatching { detachArm(original, it) } }
        original.submission?.let { settleDetached(original, it) }
        releaseRetired(original)
        policy.release(original.selection)
        changed()
    }
    private fun settleDetached(original: Current, submission: Submission) {
        if (original.closed && original.submission === submission && submission.returned && submission.settlement != null) {
            if (original.selection.observation.receipt == latestAcceptedReceipt) lastOutcome = submission.settlement!!.outcome
            original.submission = null
            releaseRetired(original)
            changed()
        }
    }
    private fun releaseRetired(original: Current) {
        val arm = original.arm
        val armResolved = arm == null || (arm.returned && arm.registrationReturned && arm.detached)
        if (original.closed && original.streamReturned && original.streamRegistrationReturned && original.streamDetached &&
            armResolved && original.submission == null) retiringOwners.remove(original)
    }
    override fun close() { current?.let(::detach); policyObservation.close() }
}
