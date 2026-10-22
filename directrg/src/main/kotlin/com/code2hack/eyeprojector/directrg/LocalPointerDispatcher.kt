package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.code2hack.eyeprojector.protocol.Touch

/** Ordinary Android events traverse the local View hierarchy, including nested widgets. */
internal class LocalPointerDispatcher(private val root: View) {
    private var held: HeldPointerDispatcher.Event? = null
    private var epoch = 0L
    private var dispatchDepth = 0
    private var deferredCancel: HeldPointerDispatcher.Event? = null

    private fun dispatch(event: MotionEvent): Boolean {
        dispatchDepth++
        return try { root.dispatchTouchEvent(event) } finally {
            dispatchDepth--
            if (dispatchDepth == 0) {
                val old = deferredCancel
                deferredCancel = null
                if (old != null) dispatchCancel(old)
            }
        }
    }

    fun touch(event: HeldPointerDispatcher.Event): Boolean {
        if (event.phase == Touch.Phase.CANCEL) {
            if (held?.contactId != event.contactId) return false
            cancel(); return true
        }
        if (event.phase == Touch.Phase.DOWN) cancel()
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

    fun tap(action: PadGestureRecognizer.Action.Tap): Boolean {
        cancel()
        val point = action.position ?: return false
        if (!point.available) return false
        val offset = action.timeMs - action.upTimeMs
        val downTime = action.downTimeMs + offset
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
        val up = MotionEvent.obtain(downTime, action.timeMs, MotionEvent.ACTION_UP, point.x, point.y, 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
            val accepted = root.dispatchTouchEvent(down)
            root.dispatchTouchEvent(up) || accepted
        } finally { down.recycle(); up.recycle() }
    }

    fun scroll(point: PointerPosition, pixels: Int): Boolean {
        cancel()
        if (!point.available || pixels == 0) return false
        val coords = MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y
            setAxisValue(MotionEvent.AXIS_VSCROLL, -pixels / 64f)
        }
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        return try { root.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }

    fun cancel() {
        epoch++
        val old = held ?: return
        held = null
        // ViewGroup attaches a DOWN target after its child callback returns. Cancel after that
        // attachment if a local widget callback changed the panel during DOWN dispatch.
        if (dispatchDepth > 0) deferredCancel = old else dispatchCancel(old)
    }

    private fun dispatchCancel(old: HeldPointerDispatcher.Event) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(old.contactId, now, MotionEvent.ACTION_CANCEL, old.position.x, old.position.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { dispatch(event) } finally { event.recycle() }
    }
}
