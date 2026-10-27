package android.widget
import android.content.Context
open class View(val context: Context) {
    private var click: (() -> Unit)? = null
    open fun onAttachedToWindow() {}
    open fun onDetachedFromWindow() {}
    fun setOnClickListener(action: (View) -> Unit) { click = { action(this) } }
    fun performClick() { click?.invoke() }
}
open class TextView(context: Context) : View(context) {
    var text: CharSequence = ""
    fun setText(id: Int) { text = context.getString(id) }
}
class Button(context: Context) : TextView(context)
open class LinearLayout(context: Context) : View(context) {
    var orientation = 0
    val children = mutableListOf<View>()
    fun addView(child: View) { children.add(child) }
    companion object { const val VERTICAL = 1 }
}
