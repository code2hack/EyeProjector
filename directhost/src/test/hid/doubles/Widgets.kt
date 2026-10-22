package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

open class TextView(context: Context) : View(context) {
    var text: CharSequence = ""
    var textSize: Float = 0f
    var maxLines = Int.MAX_VALUE
    fun setText(id: Int) { text = "resource:$id:" }
    fun setTextColor(color: Int) {}
}
class Button(context: Context) : TextView(context) { var isAllCaps = true }
open class LinearLayout(context: Context) : ViewGroup(context) {
    var orientation = VERTICAL
    class LayoutParams(val width: Int, val height: Int)
    val childLayouts = mutableMapOf<View, LayoutParams>()
    fun addView(child: View, params: LayoutParams) { children.add(child); childLayouts[child] = params }
    companion object { const val VERTICAL = 1 }
}
class ScrollView(context: Context) : ViewGroup(context)
