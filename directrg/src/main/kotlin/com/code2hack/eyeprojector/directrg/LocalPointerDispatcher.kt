package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.code2hack.eyeprojector.protocol.Touch

/** Ordinary Android events traverse the local View hierarchy, including nested widgets. */
internal class LocalPointerDispatcher(private val root: View,
    private val admit: (((Boolean) -> Unit) -> Unit)? = null) {
    private var held: HeldPointerDispatcher.Event? = null
    private var epoch = 0L
    private var dispatchDepth = 0
    private var deferredCancel: HeldPointerDispatcher.Event? = null
    private val deferredInput = ArrayDeque<HeldPointerDispatcher.Event>()
    private var drainingDeferred = false
    private val contacts = admit?.let { ReceiverContactInput<HeldPointerDispatcher.Event>(
        { it.contactId }, { it.phase }, it, { event -> touchAdmitted(event) }) }
    private val pendingActions = mutableSetOf<Any>()
    private var actionAdmission = 0L

    private fun action(operation: () -> Boolean): Boolean {
        val gate = admit ?: return operation()
        val original = Any()
        pendingActions.add(original); actionAdmission++
        gate { normal -> if (pendingActions.remove(original) && normal) operation() }
        return true
    }

    private fun dispatch(event: MotionEvent): Boolean {
        dispatchDepth++
        return try { root.dispatchTouchEvent(event) } finally {
            dispatchDepth--
            drainDeferred()
        }
    }

    private fun drainDeferred() {
        if (dispatchDepth != 0 || drainingDeferred) return
        drainingDeferred = true
        try {
            while (deferredCancel != null || deferredInput.isNotEmpty()) {
                val old = deferredCancel
                deferredCancel = null
                if (old != null) dispatchCancel(old)
                if (deferredInput.isNotEmpty()) touchAdmitted(deferredInput.removeFirst())
            }
        } finally { drainingDeferred = false }
    }

    fun touch(event: HeldPointerDispatcher.Event): Boolean {
        if (event.phase == Touch.Phase.CANCEL) {
            // Original cleanup is a release obligation, never a fresh user-control request.
            return contacts?.accept(event) == true || touchAdmitted(event)
        }
        return contacts?.accept(event) ?: touchAdmitted(event)
    }

    private fun touchAdmitted(event: HeldPointerDispatcher.Event): Boolean {
        if (dispatchDepth > 0 && event.phase != Touch.Phase.CANCEL) {
            if (event.phase == Touch.Phase.DOWN) { cancelHeld(); epoch++ }
            deferredInput.addLast(event)
            return true
        }
        if (event.phase == Touch.Phase.CANCEL) {
            if (held?.contactId != event.contactId) return false
            cancelHeld(); return true
        }
        if (event.phase == Touch.Phase.DOWN) {
            val expected = epoch + 1
            cancelHeld()
            if (epoch != expected) return false
            epoch++
        } // Record actual admission separately from release.
        else if (held?.contactId != event.contactId) return false
        val generation = epoch
        held = if (event.phase == Touch.Phase.DOWN || event.phase == Touch.Phase.MOVE) event else null
        val motion = MotionEvent.obtain(event.contactId, event.timeMs, event.phase.action, event.position.x, event.position.y, 0)
        motion.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            val accepted = dispatch(motion)
            if (event.phase == Touch.Phase.DOWN && !accepted && generation == epoch) held = null
            accepted || event.phase != Touch.Phase.DOWN
        } finally { motion.recycle() }
    }

    fun tap(action: PadGestureRecognizer.Action.Tap): Boolean = action {
        val point = action.position ?: return@action false
        if (!point.available) return@action false
        val downTime = action.downTimeMs + action.timeMs - action.upTimeMs
        val down = HeldPointerDispatcher.Event(Touch.Phase.DOWN, downTime, downTime, point)
        if (dispatchDepth > 0) {
            // This is a complete admitted tap, so defer both of its own edges together.
            cancelHeld(); epoch++
            deferredInput.addLast(down)
            deferredInput.addLast(down.copy(phase = Touch.Phase.UP, timeMs = action.timeMs))
            return@action true
        }
        val accepted = touchAdmitted(down)
        // A widget DOWN can admit another contact. Only this exact local DOWN owns its UP.
        if (held !== down) accepted
        else touchAdmitted(down.copy(phase = Touch.Phase.UP, timeMs = action.timeMs)) || accepted
    }

    fun scroll(point: PointerPosition, pixels: Int): Boolean = action {
        val expected = epoch + 1
        cancelHeld()
        if (epoch != expected) return@action false
        if (!point.available || pixels == 0) return@action false
        val coords = MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y
            setAxisValue(MotionEvent.AXIS_VSCROLL, -pixels / 64f)
        }
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { root.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }

    fun cancel() {
        contacts?.retire()
        pendingActions.clear()
        deferredInput.clear()
        cancelHeld()
    }

    private fun cancelHeld() {
        epoch++
        val old = held ?: return
        held = null
        // ViewGroup attaches a DOWN target after its child callback returns. Cancel after that
        // attachment if a local widget callback changed the panel during DOWN dispatch.
        if (dispatchDepth > 0) deferredCancel = old else dispatchCancel(old)
    }

    fun captureCancellation(): InputRetirement {
        contacts?.retire()
        pendingActions.clear()
        deferredInput.clear()
        val contactBaseline = contacts?.admission
        val actionBaseline = actionAdmission
        val original = held
        val baseline = epoch
        var expected = baseline
        var released = false
        fun observeOriginalRelease() {
            // A shared HeldInputOwner may already have released this same local contact.
            // New DOWN advances twice, so a completed later contact cannot look like this release.
            if (!released && original != null && held == null && epoch == baseline + 1) {
                released = true; expected = epoch
            }
        }
        return InputRetirement({
            observeOriginalRelease()
            if (!released && original != null && epoch == baseline && held?.contactId == original.contactId) {
                released = true; expected = epoch + 1 // Fix own release before dispatch can reenter.
                cancelHeld()
            }
        }, {
            observeOriginalRelease()
            epoch != expected || contacts?.admission != contactBaseline || actionAdmission != actionBaseline
        })
    }

    private fun dispatchCancel(old: HeldPointerDispatcher.Event) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(old.contactId, now, MotionEvent.ACTION_CANCEL, old.position.x, old.position.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { dispatch(event) } finally { event.recycle() }
    }
}
