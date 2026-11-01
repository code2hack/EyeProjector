package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import com.code2hack.eyeprojector.directhost.PhoneInputTrace
import com.code2hack.eyeprojector.protocol.*

/** A local admission token captured by the original Session before any Function/Binder queue. */
internal class AnchorAdmission(val intent: FunctionIntent, internal val generation: Long) {
    internal var consumed = false // Guarded by the original owner's control lock.
    internal val control = AnchorControlAdmission()
    internal var releaseInspected = false
    internal var releaseContact: Any? = null
    internal var releaseSequence = -1L
    internal var startedSequence: Long? = null
}

/** Framework submission only. No value here acknowledges the target application's action. */
enum class AnchorExecutionStatus { CLICK_SUBMITTED, NO_ANCHOR, UNAVAILABLE, HOLD_MODE, RETIRED, INPUT_REJECTED,
    HOLD_SUBMITTED, RELEASE_SUBMITTED }
internal enum class AnchorReleaseResult { NOT_HELD, RELEASE_SUBMITTED, RETIRED, INPUT_REJECTED }

/** Concrete enqueue: even an inline ordinary handoff cannot deliver state under the contact lock. */
internal class AnchorHoldOutput(private val work: FunctionWork,
    private val deliver: (Long, AnchorHoldState) -> Unit) {
    fun publish(revision: Long, state: AnchorHoldState) = work.submit { deliver(revision, state) }
}

/**
 * One original Session's Anchor contact owner. The lock is its existing control lock. Source queries,
 * Binder calls, accessibility traversal and feedback delivery must all occur outside this lock.
 * Ordinary input/context retirement calls retire before admitting a replacement. Head coordinates
 * never update this owner's contact. Click and stationary Hold share this single contact record.
 */
internal class AnchorContactOwner(
    private val lock: Any,
    private val touch: (Touch) -> Boolean,
    private val cancelOutput: () -> Unit,
) {
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var sequence = 0L
    private class Contact(val intent: FunctionIntent, val down: Touch, val setting: AnchorSettingContext,
        val source: ManagedUiSource, val hold: Boolean, val state: AnchorHoldOutput?) {
        var downSubmitted = false
    }
    @Volatile private var contact: Contact? = null
    @Volatile private var cancellation: Contact? = null
    private var prepareCancellation: () -> (() -> Unit) = { cancelOutput }
    private var scheduleCancellation: (() -> Unit) -> Unit = { it() }
    private var cancellationFailure: (Throwable) -> Unit = { throw it }
    private var prepareRelease: (Touch) -> (() -> Boolean) = { down -> {
        var result = false
        var failure: Throwable? = null
        try { result = touch(down.copy(phase = Touch.Phase.UP)) } catch (error: Throwable) { failure = error }
        if (!result) try { cancelOutput() } catch (cleanup: Throwable) {
            failure?.let { it.addSuppressed(cleanup) } ?: run { failure = cleanup }
        }
        failure?.let { throw it }
        result
    } }
    fun releaseOutput(prepare: (Touch) -> (() -> Boolean)) { prepareRelease = prepare }
    private var stateRevision = 0L
    @Volatile private var observation: PhoneInputTrace? = null
    fun observation(trace: PhoneInputTrace?) { observation = trace }
    private fun observed(stage: String, original: Contact? = (contact ?: cancellation), control: AnchorControlAdmission? = null,
                         failure: Throwable? = null, cleared: Boolean? = null) {
        val trace = observation ?: return
        try {
        val fact = original?.intent?.context?.usability
        trace.record(stage, mapOf("anchorOwnerIdentity" to System.identityHashCode(this),
            "contactRole" to if(control==null) "capturedOriginal" else "atControlObservation",
            "contactIdentity" to original?.let(System::identityHashCode), "contactId" to original?.down?.contactId,
            "anchorSequence" to sequence, "anchorGeneration" to generation,
            "controlIdentity" to control?.let(System::identityHashCode), "disposition" to control?.disposition?.name,
            "capturedSequence" to control?.normalSequence, "receivedDuringRetirement" to control?.receivedDuringRetirement,
            "canceling" to (cancellation != null), "downSubmitted" to original?.downSubmitted,
            "functionSource" to original?.intent?.source?.name, "gestureId" to original?.intent?.gestureId,
            "receiverOwnerEpoch" to fact?.ownerEpoch, "incarnation" to fact?.incarnation?.id,
            "incarnationSequence" to fact?.incarnation?.sequence, "inputRevision" to fact?.inputRevision,
            "displayId" to original?.source?.displayId, "displayUniqueId" to original?.source?.displayUniqueId,
            "videoWidth" to original?.source?.geometry?.video?.width, "videoHeight" to original?.source?.geometry?.video?.height,
            "targetVideoX" to original?.down?.target?.x, "targetVideoY" to original?.down?.target?.y,
            "clearedOriginal" to cleared, "failureFamily" to failure?.javaClass?.name))
        } catch (_: Throwable) { /* Observational copies cannot alter input or its failure. */ }
    }
    private inline fun <T> observing(operation: () -> T): T {
        val original = observation?.retain()
        try { return operation() } finally { original?.close() }
    }
    private val handled = mutableMapOf<String, AnchorSettingContext>()
    val holding: Boolean get() = cancellation != null || synchronized(lock) { contact != null }
    val discardingControls: Boolean get() = cancellation != null

    /** First ingress observation, before the merger/Binder/FIFO can wait. This records lifetime;
     * completed Short still selects its release contact only on the original app FIFO. */
    fun receiveControl(control: AnchorControlAdmission, function: Boolean = false) {
        try {
        if (control.terminal) return
        if (discardingControls) {
            control.receivedDuringRetirement = true
            control.disposition = AnchorControlDisposition.CONSUMED; return
        }
        if (control.owner == null) {
            control.owner = this
            control.receivedSequence = sequence
            if (function) control.functionGeneration = generation
            val held = contact?.let { it.hold && it.downSubmitted } == true
            control.capturedHold = held
            if (!function || held) control.normalSequence = sequence
        }
        if (closed) control.disposition = AnchorControlDisposition.RETIRED
        else if (discardingControls) {
            control.receivedDuringRetirement = true
            control.disposition = AnchorControlDisposition.CONSUMED
        }
        } finally { observed("CONTROL_RECEIVED", control = control) }
    }

    fun cancellationOutput(prepare: () -> (() -> Unit), schedule: (() -> Unit) -> Unit,
        failure: (Throwable) -> Unit) {
        prepareCancellation = prepare; scheduleCancellation = schedule; cancellationFailure = failure
    }

    fun capture(intent: FunctionIntent, original: AnchorControlAdmission? = null): AnchorAdmission? {
        if (discardingControls) return null
        return synchronized(lock) {
            if (closed || discardingControls || original != null && (original.terminal || original.owner !== this || original.functionGeneration != generation ||
                original.capturedHold && (original.normalSequence != sequence || contact?.let { it.hold && it.downSubmitted } != true))) null
            else AnchorAdmission(intent, generation)
        }
    }
    fun isCurrent(admission: AnchorAdmission): Boolean = synchronized(lock) {
        !closed && !discardingControls && !admission.consumed && admission.generation == generation &&
            admission.control.current(this, sequence)
    }
    fun isCurrent(control: AnchorControlAdmission): Boolean = synchronized(lock) {
        !closed && !discardingControls && control.current(this, sequence)
    }

    /** Pure original ownership witness for the last process/write submission boundary. */
    fun currentAtSubmission(control: AnchorControlAdmission): Boolean =
        !closed && !discardingControls && control.current(this, sequence)

    /** Retain a Function original's existing Hold for later Long classification; no cancellation. */
    fun captureHeldControl(control: AnchorControlAdmission) = synchronized(lock) {
        if (discardingControls) { control.disposition = AnchorControlDisposition.CONSUMED; return@synchronized }
        if (control.terminal) return@synchronized
        if (control.owner != null) {
            if (control.owner !== this || control.functionGeneration != generation)
                control.disposition = AnchorControlDisposition.RETIRED
            return@synchronized
        }
        if (closed) return@synchronized
        control.owner = this
        control.receivedSequence = sequence
        control.functionGeneration = generation // Lifetime only; FIFO still chooses Short mode.
        if (contact?.let { it.hold && it.downSubmitted } == true) {
            control.normalSequence = sequence
            control.capturedHold = true
        }
    }

    /** NORMAL is only current in this critical section; it is not a durable effect grant. */
    fun consumeHeldControl(control: AnchorControlAdmission, captureNormal: Boolean = true): AnchorControlDisposition = synchronized(lock) {
        val observedContact = contact ?: cancellation
        try {
        if (control.terminal) return@synchronized control.disposition
        if (discardingControls) {
            control.disposition = AnchorControlDisposition.CONSUMED
            return@synchronized control.disposition
        }
        if (closed || !control.current(this, sequence) || !control.capturedHold &&
            control.functionGeneration?.let { it != generation } == true) {
            control.disposition = AnchorControlDisposition.RETIRED
            return@synchronized control.disposition
        }
        control.owner = this
        val original = contact?.takeIf { it.hold && it.downSubmitted }
        if (original == null) {
            if (control.capturedHold) {
                control.disposition = AnchorControlDisposition.CONSUMED
                return@synchronized control.disposition
            }
            if (captureNormal) control.normalSequence = sequence
            return@synchronized AnchorControlDisposition.NORMAL
        }
        // Fix consumption before output can throw, block or reenter with a successor.
        control.disposition = AnchorControlDisposition.CONSUMED
        generation++
        cancelContact(original)
        AnchorControlDisposition.CONSUMED
        } finally { observed("CONTROL_DISPOSITION", observedContact, control) }
    }

    fun consumeHeldControl(admission: AnchorAdmission, captureNormal: Boolean = true): AnchorControlDisposition = synchronized(lock) {
        if (admission.control.terminal) return@synchronized admission.control.disposition
        if (!isCurrent(admission)) {
            admission.control.disposition = AnchorControlDisposition.RETIRED
            return@synchronized admission.control.disposition
        }
        val result = consumeHeldControl(admission.control, captureNormal)
        if (result != AnchorControlDisposition.NORMAL) admission.consumed = true
        result
    }

    /** This return acknowledges retirement, unlike a preference event or isCurrent comparison. */
    fun reconcile(context: AnchorSettingContext, control: AnchorControlAdmission? = null): Boolean = synchronized(lock) {
        if (closed || control != null && !control.current(this, sequence)) return@synchronized false
        val previous = handled[context.packageName]
        if (previous == context) return@synchronized true
        // A delayed observer/response must never roll the handled revision backwards.
        if (previous != null && previous.instanceId == context.instanceId && previous.revision > context.revision)
            return@synchronized false
        cancelContact()
        handled[context.packageName] = context
        true
    }

    fun admits(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean): Boolean = synchronized(lock) {
        !closed && !discardingControls && !admission.consumed && admission.generation == generation && handled[setting.packageName] == setting &&
            admission.control.current(this, sequence) &&
            (!admission.releaseInspected || admission.releaseSequence == sequence && admission.releaseContact == null) &&
            target.source == current && target.target.packageName == setting.packageName && accepts()
    }

    fun click(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean): AnchorExecutionStatus =
        start(admission, setting, target, current, accepts, false, null)

    fun hold(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean, state: AnchorHoldOutput): AnchorExecutionStatus =
        start(admission, setting, target, current, accepts, true, state)

    private fun start(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean, hold: Boolean,
        state: AnchorHoldOutput?): AnchorExecutionStatus = observing { synchronized(lock) {
        if (!admits(admission, setting, target, current, accepts))
            return@synchronized AnchorExecutionStatus.RETIRED
        admission.consumed = true
        val geometry = target.source.geometry
        val bounds = target.target.bounds
        if (!bounds.hasArea) return@synchronized AnchorExecutionStatus.UNAVAILABLE
        val point = geometry.sourceToVideo(UiPoint((bounds.left.toDouble() + bounds.right) / 2,
            (bounds.top.toDouble() + bounds.bottom) / 2))
        val x = point.x.toInt(); val y = point.y.toInt()
        if (x !in 0 until geometry.video.width || y !in 0 until geometry.video.height)
            return@synchronized AnchorExecutionStatus.UNAVAILABLE
        cancelContact()
        val down = Touch(Touch.Phase.DOWN, ++sequence, Tap(x, y, geometry.video))
        admission.startedSequence = sequence
        val original = Contact(admission.intent, down, setting, target.source, hold, state)
        contact = original // Own cleanup even when output throws or retires reentrantly.
        try {
            if (!touch(down)) {
                val retired = contact !== original || admission.generation != generation || closed
                cancelContact(original)
                return@synchronized if (retired) AnchorExecutionStatus.RETIRED else AnchorExecutionStatus.INPUT_REJECTED
            }
            original.downSubmitted = true
            if (contact !== original || closed || admission.generation != generation || !accepts()) {
                cancelContact(original); return@synchronized AnchorExecutionStatus.RETIRED
            }
            if (hold) {
                observed("HELD", original)
                publish(original, AnchorHoldState.HELD)
                return@synchronized AnchorExecutionStatus.HOLD_SUBMITTED
            }
            val up = down.copy(phase = Touch.Phase.UP)
            if (!touch(up)) { cancelContact(original); return@synchronized AnchorExecutionStatus.INPUT_REJECTED }
            if (contact !== original || closed || admission.generation != generation)
                return@synchronized AnchorExecutionStatus.RETIRED
            contact = null
            AnchorExecutionStatus.CLICK_SUBMITTED
        } catch (error: Throwable) {
            try { cancelContact(original) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    }

    /** Metadata only. A successful retirement call, not this read, acknowledges cancellation. */
    fun heldSetting(): AnchorSettingContext? = synchronized(lock) { contact?.takeIf { it.hold && it.downSubmitted }?.setting }

    /** FIFO release inspection captures one Contact, not every Short at transport arrival. */
    fun heldSetting(admission: AnchorAdmission): AnchorSettingContext? = synchronized(lock) {
        if (!isCurrent(admission)) return@synchronized null
        if (!admission.releaseInspected) {
            admission.releaseInspected = true
            admission.releaseSequence = sequence
            admission.releaseContact = contact?.takeIf { it.hold && it.downSubmitted }
        }
        (admission.releaseContact as? Contact)?.setting
    }

    fun releaseHeld(admission: AnchorAdmission, expected: AnchorSettingContext?, settingCurrent: Boolean,
        current: ManagedUiSource?, accepts: () -> Boolean): AnchorReleaseResult {
        val observationObligation = observation?.retain()
        try {
        val original: Contact
        val finish: () -> Boolean
        val revision: Long
        synchronized(lock) {
            if (!isCurrent(admission) || admission.intent.kind != FunctionIntentKind.ACTIVATE)
                return AnchorReleaseResult.RETIRED
            val held = contact?.takeIf { it.hold && it.downSubmitted }
            if (admission.releaseInspected && (admission.releaseSequence != sequence || admission.releaseContact !== held)) {
                admission.consumed = true
                return AnchorReleaseResult.RETIRED
            }
            if (held == null && expected == null) return AnchorReleaseResult.NOT_HELD
            admission.consumed = true
            if (held == null || held.setting != expected) return AnchorReleaseResult.RETIRED
            if (!settingCurrent || held.source != current || !accepts()) {
                cancelContact(held)
                return AnchorReleaseResult.RETIRED
            }
            original = held
            // The exact original retirement is fixed before a blocking or reentrant UP.
            cancellation = original
            observed("SHORT_UP_CANCELING", original)
            contact = null
            revision = ++stateRevision
            finish = try { prepareRelease(original.down) }
            catch (error: Throwable) { observed("SHORT_UP_PREPARE_FAILED", original, failure = error); cancellationFailure(error); throw error }
            observed("SHORT_UP_PREPARED", original)
        }
        val submitted = try {
            val result = finish()
            if (result) original.state?.publish(revision, AnchorHoldState.RELEASED)
            result
        } catch (error: Throwable) {
            observed("SHORT_UP_FAILED", original, failure = error)
            cancellationFailure(error)
            throw error
        }
        if (!submitted) {
            observed("SHORT_UP_REJECTED", original)
            cancellationFailure(java.io.IOException("Original Anchor UP was rejected"))
            return AnchorReleaseResult.INPUT_REJECTED
        }
        synchronized(lock) {
            val same = cancellation === original
            if (same) cancellation = null
            observed("SHORT_UP_FINISHED", original, cleared = same)
        }
        return AnchorReleaseResult.RELEASE_SUBMITTED
        } finally { observationObligation?.close() }
    }

    /** Registration cancels old Hold without invalidating its own captured REGISTER admission. */
    fun cancelHeld() = synchronized(lock) { contact?.takeIf { it.hold }?.let(::cancelContact) }

    /** Retire pending admissions too; the next press must capture a fresh original context. */
    fun retire() = synchronized(lock) { generation++; cancelContact() }
    /** A failed original Binder operation cannot retire a later admitted contact. */
    fun retire(admission: AnchorAdmission) = synchronized(lock) {
        val originalSequence = admission.startedSequence ?: admission.releaseSequence.takeIf { admission.releaseInspected }
        if (closed || originalSequence?.let { it != sequence } == true ||
            originalSequence == null && admission.generation != generation ||
            !admission.control.current(this, sequence)) return@synchronized
        generation++; cancelContact()
    }
    fun close() = synchronized(lock) { if (!closed) { closed = true; generation++; cancelContact() } }

    private fun cancelContact(original: Contact? = contact) {
        if (original == null || contact !== original) return
        val persistent = original.hold && original.downSubmitted
        if (persistent) {
            cancellation = original // Owner119: discard before detachment, callbacks or output.
            observed("CANCELING", original)
        }
        contact = null // Clear first: a reentrant ordinary-input handoff cannot cancel a successor.
        val revision = if (original.hold) ++stateRevision else null
        if (!persistent) {
            try { cancelOutput() }
            finally { if (revision != null) original.state?.publish(revision, AnchorHoldState.CANCELED) }
            return
        }
        val finish = try { prepareCancellation() }
        catch (error: Throwable) { observed("CANCEL_PREPARE_FAILED", original, failure = error); cancellationFailure(error); throw error }
        observed("CANCEL_PREPARED", original)
        var started = false
        try { scheduleCancellation {
            started = true
            var failure: Throwable? = null
            observed("CANCEL_FINISH_ENTER", original)
            try { finish() } catch (error: Throwable) { failure = error }
            try { if (revision != null) original.state?.publish(revision, AnchorHoldState.CANCELED) }
            catch (error: Throwable) { failure?.let { if (it !== error) it.addSuppressed(error) } ?: run { failure = error } }
            val error = failure
            if (error == null) synchronized(lock) {
                val same = cancellation === original
                if (same) cancellation = null
                observed("CANCEL_FINISHED", original, cleared = same)
            } else {
                observed("CANCEL_FAILED", original, failure = error)
                cancellationFailure(error)
            } // Failed retirement never makes the gate normal-ready.
        } } catch (error: Throwable) { if (!started) cancellationFailure(error); throw error }
    }

    private fun publish(original: Contact, state: AnchorHoldState) {
        if (original.hold) original.state?.publish(++stateRevision, state)
    }
}
