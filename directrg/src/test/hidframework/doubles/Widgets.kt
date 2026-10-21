package android.widget

import android.content.Context
import android.content.res.ColorStateList
import android.view.View

open class TextView(context: Context) : View(context) {
    var text: CharSequence = ""
    var textSize = 0f
    var gravity = 0
    var textColor = 0; private set
    var textColors: ColorStateList? = null; private set
    fun setTextColor(value: Int) { textColor = value }
    fun setTextColor(value: ColorStateList) { textColors = value; textColor = value.colors[0] }
}
open class Button(context: Context) : TextView(context) {
    private var click: ((View) -> Unit)? = null
    fun setOnClickListener(value: (View) -> Unit) { click = value }
    fun performClick(): Boolean {
        val listener = click ?: return false
        listener(this)
        return true
    }
}
open class LinearLayout(context: Context) : View(context) {
    var orientation = 0
    var gravity = 0
    val children = mutableListOf<View>()
    val childLayouts = mutableMapOf<View, LayoutParams>()
    class LayoutParams(val width: Int, val height: Int, val weight: Float = 0f)
    fun addView(view: View) { children.add(view) }
    fun addView(view: View, params: LayoutParams) { children.add(view); childLayouts[view] = params }
    companion object { const val VERTICAL = 1 }
}
