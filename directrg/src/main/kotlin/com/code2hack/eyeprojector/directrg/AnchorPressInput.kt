package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.AnchorIntentKind

/** Semantic operation and original ownership passed to Anchor, independent of a hardware grammar. */
internal data class AnchorGesture(val kind: AnchorIntentKind, val gestureId: Long, val sequence: Long,
    val original: Any, val physicalHeld: Boolean = false)

internal class AnchorInputIdentity {
    private val gesture = java.util.concurrent.atomic.AtomicLong()
    private val delivery = java.util.concurrent.atomic.AtomicLong()
    fun gesture() = gesture.getAndIncrement()
    fun delivery() = delivery.getAndIncrement()
}

/** One semantic Anchor key: each Short activates in order; a held press registers and its same
 * release completes the chooser. There are no native reports, multi-clicks or navigation actions. */
internal class AnchorPressInput(
    private val now: () -> Long,
    private val context: () -> Any?,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (AnchorGesture) -> Unit,
    private val capture: (Any) -> Any? = { it },
    private val identity: AnchorInputIdentity = AnchorInputIdentity(),
    private val captureOriginal: ((Any, Long) -> Any?)? = null,
    private val retireOriginal: ((Long, Any) -> Unit)? = null,
    private val registrationRelease: (AnchorGesture, Boolean) -> Unit = { _, _ -> },
    private val longMs: Long = 800,
) {
    private class Press(val id: Long, val owner: Any, val original: Any, val down: Long)
    private var held: Press? = null
    private var registration: AnchorGesture? = null
    private var cancelTimer: (() -> Unit)? = null
    private var timerGeneration = 0L
    private var ownershipGeneration = 0L
    val awaitingRegistrationRelease: Boolean get() = registration != null
    fun retirementState(): Any = listOf(held, registration, timerGeneration, ownershipGeneration)

    fun down(at: Long = now()) {
        if (registration != null) { cancel(); return } // Consume the whole press which cancels a chooser.
        val owner = context() ?: run { cancel(); return }
        if (held != null) cancel()
        if (context() != owner) return
        val generation = ownershipGeneration
        val id = identity.gesture()
        val original = if (captureOriginal != null) captureOriginal.invoke(owner, id) else capture(owner)
        if (original == null) return
        if (generation != ownershipGeneration || held != null || context() != owner) {
            retireOriginal?.invoke(id, original); return
        }
        val press = Press(id, owner, original, at)
        held = press
        arm(press)
    }

    fun up(at: Long = now()) {
        registration?.let {
            registration = null
            registrationRelease(it, false)
            return
        }
        val press = held ?: return
        complete(press, if (at - press.down >= longMs) AnchorIntentKind.REGISTER else AnchorIntentKind.ACTIVATE, false)
    }

    private fun arm(press: Press) {
        val generation = ++timerGeneration
        val deadline = press.down + longMs
        val cancel = schedule(deadline) {
            if (held !== press || timerGeneration != generation) return@schedule
            if (context() != press.owner) cancel()
            else if (now() < deadline) arm(press)
            else complete(press, AnchorIntentKind.REGISTER, true)
        }
        if (held === press && timerGeneration == generation) cancelTimer = cancel else cancel()
    }

    private fun complete(press: Press, kind: AnchorIntentKind, physicalHeld: Boolean) {
        if (held !== press) return
        held = null
        val generation = ownershipGeneration
        timerGeneration++
        val oldTimer = cancelTimer; cancelTimer = null
        val operation = AnchorGesture(kind, press.id, identity.delivery(), press.original, physicalHeld)
        if (physicalHeld) registration = operation
        InputRetirement.finishAll(listOf({
            if (generation == ownershipGeneration && context() == press.owner) dispatch(operation)
            else {
                if (registration === operation) registration = null
                retireOriginal?.invoke(press.id, press.original)
            }
            Unit
        }, { oldTimer?.invoke(); Unit }))
    }

    fun cancel(preserveRegistration: Boolean = false) {
        ownershipGeneration++; timerGeneration++
        val old = held; held = null
        val oldTimer = cancelTimer; cancelTimer = null
        val registered = if (preserveRegistration) null else registration.also { registration = null }
        InputRetirement.finishAll(listOf({ oldTimer?.invoke(); Unit },
            { registered?.let { registrationRelease(it, true) }; Unit },
            { old?.let { retireOriginal?.invoke(it.id, it.original) }; Unit }))
    }
}
