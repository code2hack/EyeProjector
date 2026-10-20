package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/** Ordinary Android events traverse the local View hierarchy, including nested widgets. */
internal class LocalPointerDispatcher(private val root: View) {
    fun tap(action: PadGestureRecognizer.Action.Tap): Boolean {
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
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
