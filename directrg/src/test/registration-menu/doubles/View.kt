package android.view
import android.content.Context
import android.graphics.Canvas
open class View(val context:Context) {
    val resources=android.content.res.Resources();var width=0;var height=0;var visibility=VISIBLE;var isClickable=false
    fun setWillNotDraw(value:Boolean)=Unit;fun invalidate()=Unit
    protected open fun onDraw(canvas:Canvas)=Unit
    fun draw(canvas:Canvas)=onDraw(canvas)
    open fun onTouchEvent(event:MotionEvent)=false
    open fun performClick()=false
    open fun dispatchGenericMotionEvent(event:MotionEvent)=false
    companion object{const val VISIBLE=0;const val GONE=8}
}
class MotionEvent(val actionMasked:Int) { companion object{const val ACTION_DOWN=0;const val ACTION_UP=1} }
