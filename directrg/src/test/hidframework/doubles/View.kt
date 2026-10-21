package android.view

import android.content.Context

open class View(val context: Context) {
    val resources get() = context.resources
    var background: Any? = null
    var backgroundColor = 0; private set
    var minimumHeight = 0
    var minimumWidth = 0
    var isSelected = false
    var isEnabled = true
    var stateDescription: CharSequence? = null
    var contentDescription: CharSequence? = null
    fun setBackgroundColor(value: Int) { backgroundColor = value }
}
