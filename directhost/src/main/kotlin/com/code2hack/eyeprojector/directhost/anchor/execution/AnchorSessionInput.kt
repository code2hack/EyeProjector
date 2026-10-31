package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher
import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.protocol.*

/**
 * Session's ordinary input and Anchor share the existing dispatcher and control lock. This adapter
 * is the only place to enter either route. Its Anchor handoff is synchronous under that lock;
 * callbacks may retire reentrantly but cannot make an old UP refer to a replacement contact.
 */
internal class AnchorSessionInput(private val lock: Any, write: (ByteArray) -> Unit) {
    private val output = PhoneTouchDispatcher(lock, write)
    private var retirementGeometry: Geometry? = null
    val anchor = AnchorContactOwner(lock, output::touch) { output.cancel(retirementGeometry) }
    private var transferring = false // Only the ordinary merger's pre-Anchor cancellation bypasses retirement.
    private var geometry: Geometry? = null
    val holding: Boolean get() = output.holding

    fun bindMotionTarget(target: PhoneTouchDispatcher.MotionTarget) = output.bindMotionTarget(target)
    fun geometry(next: Geometry) = synchronized(lock) {
        if (geometry != next) {
            retirementGeometry = next
            try { anchor.retire() } finally { retirementGeometry = null }
        }
        geometry = next; output.geometry(next)
    }
    fun touch(event: Touch): Boolean = synchronized(lock) { anchor.retire(); output.touch(event) }
    fun tap(event: Tap): Boolean = synchronized(lock) { anchor.retire(); output.tap(event) }
    fun scroll(event: Scroll): Boolean = synchronized(lock) { anchor.retire(); output.scroll(event) }
    fun navigate(event: Navigate): Boolean = synchronized(lock) { anchor.retire(); output.navigate(event) }
    fun cancel(next: Geometry? = null) = synchronized(lock) {
        retirementGeometry = next
        try { if (!transferring) anchor.retire() } finally { retirementGeometry = null }
        output.cancel(next)
    }
    fun close() = synchronized(lock) { anchor.close(); output.close() }

    /** The actual merger's nativeAction retires its previous ordinary contact/source bookkeeping. */
    fun click(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean,
        ordinaryHandoff: ((() -> Boolean) -> Boolean)): AnchorExecutionStatus =
        activate(admission, setting, target, current, accepts, ordinaryHandoff, null)

    fun hold(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean,
        ordinaryHandoff: ((() -> Boolean) -> Boolean), state: AnchorHoldOutput): AnchorExecutionStatus =
        activate(admission, setting, target, current, accepts, ordinaryHandoff, state)

    private fun activate(admission: AnchorAdmission, setting: AnchorSettingContext, target: ResolvedAnchorTarget,
        current: ManagedUiSource?, accepts: () -> Boolean,
        ordinaryHandoff: ((() -> Boolean) -> Boolean), state: AnchorHoldOutput?): AnchorExecutionStatus = synchronized(lock) {
        // Reject pending/changed contexts before canceling any unrelated ordinary contact.
        if (!anchor.admits(admission, setting, target, current, accepts))
            return@synchronized AnchorExecutionStatus.RETIRED
        var result = AnchorExecutionStatus.RETIRED
        transferring = true
        try {
            ordinaryHandoff {
                transferring = false
                result = if (state == null) anchor.click(admission, setting, target, current, accepts)
                    else anchor.hold(admission, setting, target, current, accepts, state)
                result == AnchorExecutionStatus.CLICK_SUBMITTED || result == AnchorExecutionStatus.HOLD_SUBMITTED
            }
            result
        } finally { transferring = false }
    }
}
