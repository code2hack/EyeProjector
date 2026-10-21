package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

open class TextView(context: Context) : View(context) {
    var text: CharSequence = ""
    var textSize: Float = 0f
    fun setText(id: Int) { text = "resource:$id:" }
    fun setTextColor(color: Int) {}
}
class Button(context: Context) : TextView(context)
open class LinearLayout(context: Context) : ViewGroup(context) {
    var orientation = VERTICAL
    companion object { const val VERTICAL = 1 }
}
class ScrollView(context: Context) : ViewGroup(context)
