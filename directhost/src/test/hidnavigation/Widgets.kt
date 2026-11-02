package android.widget

import android.content.Context
import android.view.View

open class TextView(context: Context) : View(context) {
    var text: CharSequence? = ""
    var textSize = 0f
    var maxLines = Int.MAX_VALUE
    fun setTextColor(color: Int) = Unit
}
class Button(context: Context) : TextView(context) {
    var isAllCaps = true
    private var click: ((View) -> Unit)? = null
    fun setOnClickListener(value: (View) -> Unit) { click = value }
    fun performClick(): Boolean { val action = click ?: return false; action(this); return true }
}
open class LinearLayout(context: Context) : View(context) {
    var orientation = VERTICAL
    val children = mutableListOf<View>()
    class LayoutParams(val width: Int, val height: Int)
    fun addView(view: View) { children.add(view) }
    fun addView(view: View, layout: LayoutParams) { children.add(view) }
    companion object { const val VERTICAL = 1 }
}

open class AdapterView<T>(context: Context) : View(context) {
    interface OnItemSelectedListener {
        fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
        fun onNothingSelected(parent: AdapterView<*>?)
    }
}
class ArrayAdapter<T>(context: Context, layout: Int, val values: List<T>)
class Spinner(context: Context) : AdapterView<Any>(context) {
    var onItemSelectedListener: OnItemSelectedListener? = null
    var adapter: ArrayAdapter<*>? = null
    var selectedItemPosition = 0
        private set
    fun setSelection(value: Int) { selectedItemPosition = value }
}
