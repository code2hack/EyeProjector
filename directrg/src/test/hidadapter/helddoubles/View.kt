package android.view

/** Only the API used by the unchanged LocalPointerDispatcher is modeled here. */
open class View {
    companion object { const val VISIBLE = 0; const val GONE = 8 }
    var width = 100
    var height = 100
    var visibility = VISIBLE
    fun removeCallbacks(task: Runnable) = true
    fun postDelayed(task: Runnable, delayMs: Long) = true
    open fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    open fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = false
}

object InputDevice {
    const val SOURCE_TOUCHSCREEN = 0x1002
    const val SOURCE_MOUSE = 0x2002
}

class MotionEvent(val downTime: Long, val eventTime: Long, val action: Int, val x: Float, val y: Float) {
    var source = 0
    fun recycle() = Unit
    class PointerCoords {
        var x = 0f
        var y = 0f
        fun setAxisValue(axis: Int, value: Float) = Unit
    }
    class PointerProperties { var id = 0; var toolType = 0 }
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_SCROLL = 8
        const val AXIS_VSCROLL = 9
        const val TOOL_TYPE_MOUSE = 3
        fun obtain(down: Long, at: Long, action: Int, x: Float, y: Float, meta: Int) = MotionEvent(down, at, action, x, y)
        fun obtain(down: Long, at: Long, action: Int, count: Int, properties: Array<PointerProperties>,
            coords: Array<PointerCoords>, meta: Int, buttons: Int, xp: Float, yp: Float, device: Int,
            edge: Int, source: Int, flags: Int) = MotionEvent(down, at, action, coords[0].x, coords[0].y).also { it.source = source }
    }
}
