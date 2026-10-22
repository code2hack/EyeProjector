package android.view

import android.content.Context
import android.graphics.Rect
import android.graphics.Point

open class View(val context: Context) {
    val resources get() = context.resources
    var background: Any? = null
    var backgroundColor = 0; private set
    var minimumHeight = 0
    var minimumWidth = 0
    var isSelected = false
    var isEnabled = true
    var visibility = VISIBLE
    var hostParent: View? = null
    var hostVisibleRect: Rect? = null
    var hostGlobalOffsetX = 0
    var hostGlobalOffsetY = 0
    val isShown: Boolean get() = visibility == VISIBLE && hostParent?.isShown != false
    var stateDescription: CharSequence? = null
    var contentDescription: CharSequence? = null
    fun setBackgroundColor(value: Int) { backgroundColor = value }
    fun getGlobalVisibleRect(rect: Rect, offset: Point? = null): Boolean {
        val visible = hostVisibleRect ?: return false
        if (!isShown || visible.left >= visible.right || visible.top >= visible.bottom) return false
        rect.set(visible)
        offset?.let { it.x = hostGlobalOffsetX; it.y = hostGlobalOffsetY }
        return true
    }
    companion object { const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8 }
}
