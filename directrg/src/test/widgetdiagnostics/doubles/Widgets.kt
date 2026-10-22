package android.widget

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import widgethost.Host

open class TextView(context: Context) : View(context) { var text: CharSequence = "" }
open class Button(context: Context) : TextView(context) {
    private var click: ((View) -> Unit)? = null
    private var longClick: ((View) -> Boolean)? = null
    private var consumedLong = false
    init { if (Host.failure == "native-setup" && Host.fixture == 0) error("sentinel sensitive setup text") }
    fun setOnClickListener(listener: (View) -> Unit) { click = listener }
    fun setOnLongClickListener(listener: (View) -> Boolean) { longClick = listener }
    open fun performClick(): Boolean { click?.invoke(this); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Host.fixture == 1) Host.nativeTouchCount++ else Host.heldTouchCount++
        if (Host.failure == "held-down" && Host.fixture == 2 && event.actionMasked == MotionEvent.ACTION_DOWN) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> consumedLong = longClick?.invoke(this) ?: false
            MotionEvent.ACTION_UP -> if (!consumedLong) performClick()
            MotionEvent.ACTION_CANCEL -> consumedLong = false
        }
        return true
    }
}
class SeekBar(context: Context) : View(context) {
    var max = 100; var progress = 0
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_CANCEL) progress = (event.x / width * max).toInt()
        return true
    }
}
open class LinearLayout(context: Context) : ViewGroup(context) {
    var orientation = 0
    class LayoutParams(width: Int, height: Int, val weight: Float = 0f) : ViewGroup.LayoutParams(width, height)
    override fun layout(x: Int, y: Int, w: Int, h: Int) {
        super.layout(x, y, w, h)
        val fixed = children.sumOf { if (it.params.height > 0) it.params.height else it.view.minHeight }
        var cursor = 0
        for ((view, params) in children) {
            val childHeight = when {
                params.height > 0 -> params.height
                params is LayoutParams && params.weight > 0 -> (h - fixed).coerceAtLeast(0)
                view.minHeight > 0 -> view.minHeight
                view is LinearLayout -> view.children.sumOf { if (it.params.height > 0) it.params.height else it.view.minHeight }
                else -> h
            }
            view.layout(0, cursor, w, childHeight); cursor += childHeight
        }
    }
    companion object { const val VERTICAL = 1 }
}
open class FrameLayout(context: Context) : ViewGroup(context)
class ScrollView(context: Context) : ViewGroup(context) {
    var scrollY = 0
    override fun layout(x: Int, y: Int, w: Int, h: Int) {
        super.layout(x, y, w, h)
        children.forEach { it.view.layout(0, 0, w, h) }
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (super.dispatchGenericMotionEvent(event)) return true
        scrollY = (scrollY - event.scrollAxis * 64).toInt().coerceAtLeast(0)
        return true
    }
}
class ArrayAdapter<T>(context: Context, resource: Int, val rows: List<T>)
class ListView(context: Context) : ViewGroup(context) {
    var adapter: ArrayAdapter<String>? = null
    var firstVisiblePosition = 0
    private val row = TextView(context)
    override val childCount get() = 1
    override fun getChildAt(index: Int): View = row
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        row.top += (event.scrollAxis * 64).toInt()
        return true
    }
}
