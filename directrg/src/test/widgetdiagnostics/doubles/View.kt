package android.view

import android.content.Context
import widgethost.Host

open class View(val context: Context) {
    var width = 0; var height = 0; var top = 0; var left = 0
    var minHeight = 0
    var isSoundEffectsEnabled = true
    var isHapticFeedbackEnabled = true
    open fun layout(x: Int, y: Int, w: Int, h: Int) { left = x; top = y; width = w; height = h }
    fun setBackgroundColor(color: Int) {}
    fun post(task: Runnable) = Host.post(task)
    open fun dispatchTouchEvent(event: MotionEvent) = onTouchEvent(event)
    open fun onTouchEvent(event: MotionEvent) = false
    open fun dispatchGenericMotionEvent(event: MotionEvent) = false
}
open class ViewGroup(context: Context) : View(context) {
    data class Entry(val view: View, val params: LayoutParams)
    open class LayoutParams(val width: Int, val height: Int)
    val children = mutableListOf<Entry>()
    open val childCount get() = children.size
    private var target: View? = null
    fun addView(view: View) = addView(view, LayoutParams(-1, -2))
    fun addView(view: View, params: LayoutParams) { children += Entry(view, params) }
    open fun getChildAt(index: Int) = children[index].view
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val candidate = children.map { it.view }.lastOrNull { event.y >= it.top && event.y < it.top + it.height }
            if (Host.failure == "native-extra-downs" && Host.fixture == 1 && Host.nativeTouchCount == 0 && candidate is android.widget.Button) {
                // Deliberately malformed host widget callback delivery exercises diagnostic
                // array caps before the actual unchanged down-interval assertion fails.
                repeat(9) { candidate.dispatchTouchEvent(event.child(candidate)) }
            }
            val accepted = candidate?.dispatchTouchEvent(event.child(candidate)) ?: onTouchEvent(event)
            target = if (accepted) candidate else null
            return accepted
        }
        val old = target
        val accepted = old?.dispatchTouchEvent(event.child(old)) ?: onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) target = null
        return accepted
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val candidate = children.map { it.view }.lastOrNull { event.y >= it.top && event.y < it.top + it.height }
        return candidate?.dispatchGenericMotionEvent(event.child(candidate)) ?: false
    }
}
