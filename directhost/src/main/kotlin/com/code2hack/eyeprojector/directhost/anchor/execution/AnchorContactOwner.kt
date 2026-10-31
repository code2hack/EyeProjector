package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.protocol.*

/** A local admission token captured by the original Session before any Function/Binder queue. */
internal class AnchorAdmission(val intent: FunctionIntent, internal val generation: Long) {
    internal var consumed = false // Guarded by the original owner's control lock.
}

/** Framework submission only. No value here acknowledges the target application's action. */
enum class AnchorExecutionStatus { CLICK_SUBMITTED, NO_ANCHOR, UNAVAILABLE, HOLD_MODE, RETIRED, INPUT_REJECTED }

/**
 * One original Session's Anchor contact owner. The lock is its existing control lock. Source queries,
 * Binder calls, accessibility traversal and feedback delivery must all occur outside this lock.
 * Ordinary input/context retirement calls retire before admitting a replacement. Head coordinates
 * never update this owner's contact. #123 extends this same owner with stationary Hold.
 */
internal class AnchorContactOwner(
    private val lock: Any,
    private val touch: (Touch) -> Boolean,
    private val cancelOutput: () -> Unit,
) {
    private var generation = 0L
    private var closed = false
    private var sequence = 0L
    private var contact: Touch? = null
    private val handled = mutableMapOf<String, AnchorSettingContext>()
    val holding: Boolean get() = synchronized(lock) { contact != null }

    fun capture(intent: FunctionIntent): AnchorAdmission? = synchronized(lock) {
        if (closed) null else AnchorAdmission(intent, generation)
    }
    fun isCurrent(admission: AnchorAdmission): Boolean = synchronized(lock) {
        !closed && !admission.consumed && admission.generation == generation
    }

    /** This return acknowledges retirement, unlike a preference event or isCurrent comparison. */
    fun reconcile(context: AnchorSettingContext): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
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
            target.source == current && target.target.packageName == setting.packageName && accepts()
    }

    fun click(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean): AnchorExecutionStatus = synchronized(lock) {
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
        contact = down // Own cleanup even when an output callback throws or retires reentrantly.
        try {
            if (!touch(down)) {
                val retired = contact !== down || admission.generation != generation || closed
                cancelContact()
                return@synchronized if (retired) AnchorExecutionStatus.RETIRED else AnchorExecutionStatus.INPUT_REJECTED
            }
            if (contact !== down || closed || admission.generation != generation || !accepts()) {
                cancelContact(); return@synchronized AnchorExecutionStatus.RETIRED
            }
            val up = down.copy(phase = Touch.Phase.UP)
            if (!touch(up)) { cancelContact(); return@synchronized AnchorExecutionStatus.INPUT_REJECTED }
            if (contact !== down || closed || admission.generation != generation)
                return@synchronized AnchorExecutionStatus.RETIRED
            contact = null
            AnchorExecutionStatus.CLICK_SUBMITTED
        } catch (error: Throwable) {
            try { cancelContact() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** Retire pending admissions too; the next press must capture a fresh original context. */
    fun retire() = synchronized(lock) { generation++; cancelContact() }
    fun close() = synchronized(lock) { if (!closed) { closed = true; generation++; cancelContact() } }

    private fun cancelContact() {
        if (contact == null) return
        contact = null // Clear first: a reentrant ordinary-input handoff cannot cancel a successor.
        cancelOutput()
    }
}
