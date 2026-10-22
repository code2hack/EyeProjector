package android.view

import android.content.Context

object InputDevice { const val SOURCE_TOUCHSCREEN = 0x1002; const val SOURCE_MOUSE = 0x2002 }
class MotionEvent(val downTime: Long, val eventTime: Long, val action: Int, var x: Float, var y: Float) {
    val actionMasked get() = action
    var source = 0
    var scrollAxis = 0f
    fun recycle() {}
    fun child(child: View) = MotionEvent(downTime, eventTime, action, x - child.left, y - child.top).also {
        it.source = source; it.scrollAxis = scrollAxis
    }
    class PointerCoords {
        var x = 0f; var y = 0f; var axis = 0f
        fun setAxisValue(which: Int, value: Float) { axis = value }
    }
    class PointerProperties { var id = 0; var toolType = 0 }
    companion object {
        const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3; const val ACTION_SCROLL = 8
        const val AXIS_VSCROLL = 9; const val TOOL_TYPE_MOUSE = 3
        fun obtain(down: Long, at: Long, action: Int, x: Float, y: Float, meta: Int) = MotionEvent(down, at, action, x, y)
        fun obtain(down: Long, at: Long, action: Int, count: Int, properties: Array<PointerProperties>,
            coords: Array<PointerCoords>, meta: Int, buttons: Int, xp: Float, yp: Float, device: Int,
            edge: Int, source: Int, flags: Int) = MotionEvent(down, at, action, coords[0].x, coords[0].y).also {
            it.source = source; it.scrollAxis = coords[0].axis
        }
    }
}
class GestureDetector(context: Context, private val listener: SimpleOnGestureListener) {
    private var downs = 0
    open class SimpleOnGestureListener {
        open fun onDown(e: MotionEvent) = false
        open fun onDoubleTap(e: MotionEvent) = false
    }
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        downs++
        listener.onDown(event)
        if (downs == 2) listener.onDoubleTap(event)
        return true
    }
}
object ViewConfiguration { fun getLongPressTimeout() = 500 }
