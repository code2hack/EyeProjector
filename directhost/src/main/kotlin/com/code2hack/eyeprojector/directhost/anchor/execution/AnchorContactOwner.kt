package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.input.FunctionWork
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
    private var generation = 0L
    private var closed = false
    private var sequence = 0L
    private class Contact(val down: Touch, val setting: AnchorSettingContext,
        val source: ManagedUiSource, val hold: Boolean, val state: AnchorHoldOutput?) {
        var downSubmitted = false
    }
    private var contact: Contact? = null
    private var stateRevision = 0L
    private val handled = mutableMapOf<String, AnchorSettingContext>()
    val holding: Boolean get() = synchronized(lock) { contact != null }

    fun capture(intent: FunctionIntent): AnchorAdmission? = synchronized(lock) {
        if (closed) null else AnchorAdmission(intent, generation)
    }
    fun isCurrent(admission: AnchorAdmission): Boolean = synchronized(lock) {
        !closed && !admission.consumed && admission.generation == generation &&
            admission.control.current(this, sequence)
    }
    fun isCurrent(control: AnchorControlAdmission): Boolean = synchronized(lock) {
        !closed && control.current(this, sequence)
    }

    /** NORMAL is only current in this critical section; it is not a durable effect grant. */
    fun consumeHeldControl(control: AnchorControlAdmission, captureNormal: Boolean = true): AnchorControlDisposition = synchronized(lock) {
        if (control.terminal) return@synchronized control.disposition
        if (closed || !control.current(this, sequence)) {
            control.disposition = AnchorControlDisposition.RETIRED
            return@synchronized control.disposition
        }
        control.owner = this
        val original = contact?.takeIf { it.hold && it.downSubmitted }
        if (original == null) {
            if (captureNormal) control.normalSequence = sequence
            return@synchronized AnchorControlDisposition.NORMAL
        }
        // Fix consumption before output can throw, block or reenter with a successor.
        control.disposition = AnchorControlDisposition.CONSUMED
        generation++
        cancelContact(original)
        AnchorControlDisposition.CONSUMED
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
        !closed && !admission.consumed && admission.generation == generation && handled[setting.packageName] == setting &&
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
        state: AnchorHoldOutput?): AnchorExecutionStatus = synchronized(lock) {
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
        val original = Contact(down, setting, target.source, hold, state)
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
        current: ManagedUiSource?, accepts: () -> Boolean): AnchorReleaseResult = synchronized(lock) {
        if (!isCurrent(admission) || admission.intent.kind != FunctionIntentKind.ACTIVATE)
            return@synchronized AnchorReleaseResult.RETIRED
        val original = contact?.takeIf { it.hold && it.downSubmitted }
        if (admission.releaseInspected && (admission.releaseSequence != sequence || admission.releaseContact !== original)) {
            admission.consumed = true
            return@synchronized AnchorReleaseResult.RETIRED
        }
        if (original == null && expected == null) return@synchronized AnchorReleaseResult.NOT_HELD
        admission.consumed = true
        if (original == null || original.setting != expected) return@synchronized AnchorReleaseResult.RETIRED
        if (!settingCurrent || original.source != current || !accepts()) {
            cancelContact(original)
            return@synchronized AnchorReleaseResult.RETIRED
        }
        try {
            if (!touch(original.down.copy(phase = Touch.Phase.UP))) {
                cancelContact(original)
                return@synchronized AnchorReleaseResult.INPUT_REJECTED
            }
            if (contact !== original || closed || admission.generation != generation)
                return@synchronized AnchorReleaseResult.RETIRED
            contact = null
            publish(original, AnchorHoldState.RELEASED)
            AnchorReleaseResult.RELEASE_SUBMITTED
        } catch (error: Throwable) {
            try { cancelContact(original) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
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
        contact = null // Clear first: a reentrant ordinary-input handoff cannot cancel a successor.
        val revision = if (original.hold) ++stateRevision else null
        try { cancelOutput() }
        finally { if (revision != null) original.state?.publish(revision, AnchorHoldState.CANCELED) }
    }

    private fun publish(original: Contact, state: AnchorHoldState) {
        if (original.hold) original.state?.publish(++stateRevision, state)
    }
}
