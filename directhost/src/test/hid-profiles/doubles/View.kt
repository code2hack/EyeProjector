package android.view

import android.content.Context

open class View(val context: Context) {
    var visibility = VISIBLE
    var isEnabled = true
    var isSelected = false
    val resources get() = context.resources
    var minimumHeight = 0
    var contentDescription: CharSequence? = null
    fun setBackgroundColor(color: Int) = Unit
    companion object { const val VISIBLE = 0; const val GONE = 8 }
}
