package android.view
import android.content.Context
import android.util.AttributeSet
open class View(context:Context,attrs:AttributeSet?=null) {
    var isClickable=false;var isFocusable=false;var importantForAccessibility=0
    fun invalidate() {}
    fun postOnAnimation(task:Runnable) {}
    fun postDelayed(task:Runnable,delay:Long)=true
    fun removeCallbacks(task:Runnable) {}
    open fun onDraw(canvas:android.graphics.Canvas) {}
    open fun onDetachedFromWindow() {}
    companion object { const val IMPORTANT_FOR_ACCESSIBILITY_NO=2 }
}

class Surface
