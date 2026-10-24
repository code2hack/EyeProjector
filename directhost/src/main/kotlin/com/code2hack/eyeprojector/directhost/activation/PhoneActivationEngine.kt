package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.SessionController
import com.code2hack.eyeprojector.protocol.*
import java.util.UUID

/** Ordered feature-writer lane. Existing core owns all admission, resources and release. */
internal class PhoneActivationEngine(
    private val core: SessionController,
    private val read: () -> ActivationJournal,
    private val write: (ActivationJournal) -> Unit,
    private val setup: () -> String?,
    private val context: () -> ProjectionContext,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val contextRevision: () -> Long = { 0L },
) {
    val local = PhoneActivationOwner<ProjectionContext>()
    var journal = read(); private set
    var handle: ActivationHandle? = journal.current?.takeIf { it.desired }?.let { local.restoreActivation(it.id, ActivationCause.GLASSES_LAUNCH) }; private set
    private var localAttempt: ActivationAttempt? = null
    private val canceledLocalAttempts = mutableMapOf<String, ActivationAttempt>()
    private var lastContext = ProjectionContext()
    private var lastContextRevision = contextRevision()
    private var presentation: PresentationReport? = null
    var inputPresentation: PresentationReport? = null; private set
    val goalPresentation get() = presentation
    val attempt get() = localAttempt
    val inputUsable get(): Boolean {
        val c = journal.current ?: return false
        val report = inputPresentation ?: return false
        val actual = core.ownership()
        return c.desired && c.id !in journal.canceled && report.presented && report.activationId == c.id &&
            report.incarnation == journal.incarnation && report.ownerEpoch == c.ownerEpoch &&
            actual.resourceOwner == c.ownerEpoch && !actual.releasing && actual.state.phase.active &&
            lastContext.available && lastContext.ownerEpoch == c.ownerEpoch
    }
    private var presentationRevision = -1L
    private var demandPending = false
    val desired get() = journal.current?.desired == true
    val ready get() = handle?.let { local.state(it).phase == ActivationPhase.READY } == true
    private fun save(value: ActivationJournal) {
        try { write(value); journal = value }
        catch (error: Exception) { journal = read(); throw error }
    }
    fun resolve(id: String) = journal.aliases[id] ?: id
    private fun retireLocal(id: String) {
        if (handle?.id == id) {
            local.cancelActivation(handle!!)?.let { canceledLocalAttempts[it.id] = it }
            handle = null; localAttempt = null; presentation = null; inputPresentation = null
        }
    }

    fun request(cause: ActivationCause, id: String = newId(), demandId: String? = null): ActivationHandle {
        val original = journal.aliases[id]
        if (original != null && (original != journal.current?.id || !desired)) return ActivationHandle(original, cause)
        if (desired) {
            if (id != handle!!.id && original == null) save(journal.copy(aliases = journal.aliases + (id to handle!!.id)))
            val h = handle!!
            val originalDemand = journal.current!!
            if (demandId != null && originalDemand.demandId != demandId) {
                save(journal.copy(current = originalDemand.copy(goal = ActivationGoal.PROJECTED,
                    demandId = demandId, wakeRevision = originalDemand.wakeRevision + 1)))
                presentation = null; demandPending = true
                localAttempt?.let(local::awaitingPresentation)
            }
            // Weaker requests join the current PROJECTED demand; only a new wake after loss advances it.
            else if (!ready && !demandPending) {
                val c = journal.current!!
                save(journal.copy(current = c.copy(wakeRevision = c.wakeRevision + 1)))
                presentation = null; demandPending = true
            }
            return h
        }
        save(journal.copy(current = DesiredActivation(id, goal = if (demandId == null) ActivationGoal.USABLE else ActivationGoal.PROJECTED,
            demandId = demandId), aliases = journal.aliases + (id to id)))
        handle = local.restoreActivation(id, cause)
        localAttempt = null; presentation = null; inputPresentation = null; presentationRevision = -1; demandPending = true
        return handle!!
    }

    fun start(expectedEpoch: String? = null): SessionState {
        if (expectedEpoch != null && !core.acceptsStartCaller(expectedEpoch)) return core.state()
        request(ActivationCause.PHONE_START)
        tick()
        return state()
    }
    fun stop(expectedEpoch: String? = null): SessionState {
        if (expectedEpoch != null && !core.acceptsStopCaller(expectedEpoch)) return core.state()
        if (expectedEpoch == null) journal.current?.let { cancel(it.id) }
        else core.stopCallerOwner(expectedEpoch)?.let { owner -> journal.attempts[owner]?.activation?.let(::cancel) }
        // A legacy backend with no feature mapping still retains its original epoch-bound semantics.
        if (expectedEpoch != null) core.stop(expectedEpoch) else if (!desired) core.ownership().resourceOwner?.let { owner ->
            if (journal.attempts[owner] == null) core.stop(owner)
        }
        tick()
        return state()
    }
    fun cancel(requested: String, initialOriginal: String? = null) {
        // Only the facade's immutable first journal observation supplies initialOriginal.
        // Unknown remote T / arbitrary stale H cancellation never resolves against journal.current.
        val id = journal.aliases[requested] ?: initialOriginal ?: requested
        val c = journal.current
        save(journal.copy(aliases = journal.aliases + (requested to id), canceled = journal.canceled + id,
            current = c?.takeIf { it.id == id }?.copy(desired = false) ?: c))
        retireLocal(id)
        cancelActualOwner()
    }
    private fun cancelActualOwner() {
        val actual = core.ownership().resourceOwner ?: return
        val a = journal.attempts[actual] ?: return
        if (a.activation in journal.canceled) core.cancelStart(a.baseline, actual)
    }

    fun command(request: SessionControl.Request): SessionState {
        request.incarnation?.let { incoming ->
            val old = journal.incarnation
            if (old == null || incoming.sequence > old.sequence) {
                save(journal.copy(incarnation = incoming)); presentation = null; inputPresentation = null; presentationRevision = -1
                localAttempt?.let(local::awaitingPresentation)
            }
        }
        request.cancelTrigger?.let { cancel ->
            val old = journal.triggers[cancel.triggerId]
            // Original T and its mapped whole-H cancellation are one durable transition.
            val h = old?.activation
            val c = journal.current
            save(journal.copy(triggers = journal.triggers + (cancel.triggerId to
                (old ?: TriggerBinding(null, cancel.admissionId)).copy(canceled = true)),
                canceled = journal.canceled + listOfNotNull(h),
                current = c?.takeIf { it.id == h }?.copy(desired = false) ?: c))
            h?.let(::retireLocal)
            cancelActualOwner()
        }
        request.cancelActivation?.let { cancel(it.activationId) }
        val incoming = request.intent
        if (request.cancelTrigger != null || request.cancelActivation != null) {
            tick()
            return reply(core.state(), request.cancelTrigger?.triggerId).let { it.copy(activation = it.activation?.copy(cancelAcknowledged = request.cancelActivation?.activationId)) }
        }
        if (request.incarnation != null && request.incarnation != journal.incarnation) return reply(core.state(), incoming?.triggerId)
        if (incoming != null) {
            val old = journal.triggers[incoming.triggerId]
            if (old?.canceled == true || old?.activation in journal.canceled) return reply(core.state(), incoming.triggerId)
            if (old == null) {
                if (request.operation == SessionControl.Operation.START && !core.acceptsStartCaller(request.epoch))
                    return reply(core.state(), incoming.triggerId)
                val id = if (desired) handle!!.id else newId()
                // H and T->H are ONE journal write. A crash cannot leave an orphan desired H.
                save(journal.copy(current = if (desired) journal.current else DesiredActivation(id),
                    triggers = journal.triggers + (incoming.triggerId to TriggerBinding(id, request.startEpoch))))
                if (handle == null) handle = local.restoreActivation(id, ActivationCause.GLASSES_LAUNCH)
            }
        }
        reconcilePresentationContext()
        request.presentation?.let(::observePresentation)
        // Feature START is driven by the durable desired owner. Legacy attempt cleanup stays core-only.
        val coreRequest = if (incoming != null && request.operation == SessionControl.Operation.START)
            request.copy(operation = SessionControl.Operation.STATUS) else request
        val result = core.command(coreRequest, setup())
        tick()
        val triggerId = request.cancelTrigger?.triggerId ?: incoming?.triggerId
        val after = if (request.startEpoch.isNotEmpty() && request.operation != SessionControl.Operation.STOP)
            core.command(SessionControl.Request.observeAttempt(request.startEpoch)) else result
        return reply(after, triggerId)
    }

    fun tick() {
        journal.current?.takeIf { !it.desired || it.id in journal.canceled }?.let { retireLocal(it.id) }
        if (handle == null) journal.current?.takeIf { it.desired && it.id !in journal.canceled }?.let {
            handle = local.restoreActivation(it.id, ActivationCause.GLASSES_LAUNCH)
        }
        core.state() // Explicit ledger reconciliation on this writer lane, never in HID snapshot reads.
        cancelActualOwner()
        if (core.ownership().released) {
            canceledLocalAttempts.values.forEach(local::released); canceledLocalAttempts.clear()
        }
        var c = journal.current ?: return
        if (!c.desired || c.id in journal.canceled) return
        val actual = core.ownership()
        val contextNow = reconcilePresentationContext()
        if (c.attempt != null && actual.released) {
            localAttempt?.let { local.released(it) }; localAttempt = null
            save(journal.copy(current = c.copy(attempt = null, ownerEpoch = "")))
            c = journal.current!!
        }
        if (c.attempt == null && actual.resourceOwner != null && !actual.releasing && actual.state.phase.active &&
            journal.attempts[actual.resourceOwner] == null) {
            val a = actual.resourceOwner
            save(journal.copy(current = c.copy(attempt = a, ownerEpoch = a),
                attempts = journal.attempts + (a to FeatureAttempt(c.id, a))))
            c = journal.current!!
        }
        if (c.attempt == null) {
            if (!actual.released) return
            setup()?.let { return }
            val a = newId()
            // Journal A->H before core admission; a crash between these writes can only retry this A.
            save(journal.copy(current = c.copy(attempt = a), attempts = journal.attempts + (a to FeatureAttempt(c.id, actual.state.epoch))))
            c = journal.current!!
        }
        val a = c.attempt!!
        if (localAttempt?.id != a) localAttempt = local.beginAttempt(handle!!, a)
        val binding = journal.attempts[a] ?: return
        if (actual.resourceOwner == null && actual.released) core.start(binding.baseline, a, setup())
        val after = core.ownership()
        val owner = after.resourceOwner?.takeIf { it == a }
        if (owner != null && c.ownerEpoch != owner) save(journal.copy(current = journal.current!!.copy(ownerEpoch = owner)))
        if (after.releasing || after.state.phase in setOf(SessionPhase.FAILED, SessionPhase.STOPPING))
            localAttempt?.let { local.releasing(it, after.state.detail) }
        else if (after.state.phase == SessionPhase.UNKNOWN) localAttempt?.let(local::awaitingPresentation)
        else if (setup() != null) localAttempt?.let { local.setupRequired(it, setup().orEmpty()) }
        val report = presentation
        if (report != null && satisfies(c.goal, report.mode) && owner != null && contextNow.available && contextNow.ownerEpoch == owner &&
            report.ownerEpoch == owner && report.activationId == c.id && report.wakeRevision == c.wakeRevision)
            localAttempt?.let { local.ready(it, contextNow) }
    }

    private fun reconcilePresentationContext(): ProjectionContext {
        val now = context()
        val revision = contextRevision()
        val changedSource = revision != lastContextRevision
        if (now != lastContext || changedSource) {
            lastContext = now; lastContextRevision = revision; presentation = null; inputPresentation = null
            localAttempt?.let(local::invalidatePresentation)
            // A fresh source generation solicits new Receiver presentation for the SAME original D/H.
            // This is a correlation change, never a release witness or a new backend/session owner.
            if (changedSource) journal.current?.takeIf { it.desired && it.attempt != null }?.let { c ->
                save(journal.copy(current = c.copy(wakeRevision = c.wakeRevision + 1)))
                demandPending = true
            }
        }
        return now
    }

    fun acceptsPresentation(report: PresentationReport): Boolean {
        val c = journal.current ?: return false
        return c.desired && c.id !in journal.canceled && report.incarnation == journal.incarnation &&
            report.revision > presentationRevision && report.activationId == c.id && report.ownerEpoch == c.ownerEpoch &&
            report.wakeRevision <= c.wakeRevision
    }
    private fun satisfies(goal: ActivationGoal, mode: ReceiverUsabilityMode) =
        mode == ReceiverUsabilityMode.PROJECTED || goal == ActivationGoal.USABLE && mode == ReceiverUsabilityMode.LOCAL_PANEL

    private fun observePresentation(report: PresentationReport) {
        val c = journal.current ?: return
        if (!acceptsPresentation(report)) return
        // One source-order high-water mark: newer NONE still retires input even for an older demand revision.
        presentationRevision = report.revision; inputPresentation = report
        presentation = report.takeIf { it.wakeRevision == c.wakeRevision && satisfies(c.goal, it.mode) }
        if (presentation != null) demandPending = false
        else localAttempt?.let(local::awaitingPresentation)
    }
    fun state() = reply(core.state(), null)
    private fun reply(value: SessionState, triggerId: String?): SessionState {
        val c = journal.current
        val t = triggerId?.let { id -> journal.triggers[id]?.let {
            TriggerReceipt(id, it.activation, if (it.canceled || it.activation in journal.canceled) TriggerDisposition.CANCELED else TriggerDisposition.MAPPED)
        } }
        val status = if (c?.desired == true && !value.phase.active) setup()?.let { value.copy(detail = it) } ?: value else value
        return status.copy(activation = ActivationReply(c?.let { ActivationDirective(it.id, it.desired, it.wakeRevision, it.ownerEpoch, it.goal) }, t))
    }
}
