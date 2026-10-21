package android.widget
import android.app.PendingIntent
class RemoteViews(val packageName: String, val layout: Int) {
    val text = mutableMapOf<Int, CharSequence>()
    val descriptions = mutableMapOf<Int, CharSequence>()
    val enabled = mutableMapOf<Int, Boolean>()
    val intents = mutableMapOf<Int, PendingIntent>()
    fun setTextViewText(id: Int, value: CharSequence) { text[id] = value }
    fun setContentDescription(id: Int, value: CharSequence) { descriptions[id] = value }
    fun setBoolean(id: Int, method: String, value: Boolean) { check(method == "setEnabled"); enabled[id] = value }
    fun setOnClickPendingIntent(id: Int, value: PendingIntent) { intents[id] = value }
}
open class TextView(context: android.content.Context) {
    fun setText(id: Int) {}
    fun setTextColor(color: Int) {}
}
class Button(context: android.content.Context) : TextView(context) {
    private var listener: (() -> Unit)? = null
    fun setOnClickListener(listener: () -> Unit) { this.listener = listener }
    fun performClick() { listener!!() }
}
class LinearLayout(context: android.content.Context) {
    var orientation = 0
    val children = mutableListOf<Any>()
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    fun setBackgroundColor(color: Int) {}
    fun addView(view: Any) { children.add(view) }
    companion object { const val VERTICAL = 1 }
}
