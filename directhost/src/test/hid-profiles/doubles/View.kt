package android.view

import android.content.Context

open class View(val context: Context) {
    var isEnabled = true
    var isSelected = false
    val resources get() = context.resources
    var minimumHeight = 0
    var contentDescription: CharSequence? = null
    fun setBackgroundColor(color: Int) = Unit
}
