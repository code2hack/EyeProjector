package android.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet

/** Scheduling is explicit so tests can delay the expiry callback while admitting input. */
open class View(context: Context, attrs: AttributeSet? = null) {
    companion object {
        const val IMPORTANT_FOR_ACCESSIBILITY_NO = 2
    }
    var isClickable = false
    var isFocusable = false
    var importantForAccessibility = 0
    private val animations = mutableListOf<Runnable>()
    private val delayed = mutableListOf<Runnable>()

    fun postOnAnimation(task: Runnable) { animations.add(task) }
    fun postDelayed(task: Runnable, delayMs: Long): Boolean { delayed.add(task); return true }
    fun removeCallbacks(task: Runnable): Boolean {
        val removed = animations.removeAll { it === task }
        return delayed.removeAll { it === task } || removed
    }
    fun invalidate() = Unit
    fun runAnimationFrame() {
        val scheduled = animations.toList()
        animations.clear()
        scheduled.forEach { it.run() }
    }
    fun runDelayedCallbacks() {
        val scheduled = delayed.toList()
        delayed.clear()
        scheduled.forEach { it.run() }
    }
    protected open fun onDraw(canvas: Canvas) = Unit
    protected open fun onDetachedFromWindow() = Unit
}

class InputDevice(private val touchpad: Boolean = true) {
    companion object {
        const val SOURCE_TOUCHPAD = 0x00100008
    }
    fun supportsSource(source: Int) = touchpad && source == SOURCE_TOUCHPAD
}

/** Pad coordinates are intentionally absent: the production adapter must use the head pointer. */
class MotionEvent(
    val actionMasked: Int,
    val eventTime: Long,
    val downTime: Long,
    val deviceId: Int = 7,
    private val pointerId: Int = 11,
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
    fun isFromSource(source: Int) = source == InputDevice.SOURCE_TOUCHPAD
    fun getPointerId(index: Int): Int { check(index == 0); return pointerId }
    fun findPointerIndex(id: Int) = if (id == pointerId) 0 else -1
}
