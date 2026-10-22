package android.view

import android.content.Context

open class View(val context: Context) {
    var isEnabled = true
    var visibility = VISIBLE
    var tag: Any? = null
    val resources get() = context.resources
    var minimumHeight = 0
    var isSelected = false
    var contentDescription: CharSequence? = null
    private var click: ((View) -> Unit)? = null
    fun setOnClickListener(action: (View) -> Unit) { click = action }
    fun performClick() { click?.invoke(this) }
    fun setBackgroundColor(color: Int) {}
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    companion object { const val VISIBLE = 0; const val GONE = 8 }
}

open class ViewGroup(context: Context) : View(context) {
    val children = mutableListOf<View>()
    fun addView(child: View) { children.add(child) }
    fun addView(child: View, index: Int) { children.add(index.coerceAtMost(children.size), child) }
}
