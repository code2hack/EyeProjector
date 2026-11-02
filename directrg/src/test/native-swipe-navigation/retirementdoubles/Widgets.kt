package android.widget
import android.content.Context
import android.view.View
open class FrameLayout(context:Context):View(context){
    class LayoutParams(val width:Int,val height:Int,val gravity:Int=0){companion object{const val WRAP_CONTENT=-2}}
    fun addView(view:View,params:LayoutParams=LayoutParams(-1,-1))=Unit
}
open class LinearLayout(context:Context):View(context){
    var orientation=0;var gravity=0
    fun addView(view:View)=Unit
    companion object{const val VERTICAL=1;const val HORIZONTAL=0}
}
open class TextView(context:Context):View(context){var text="";var textSize=0f;var gravity=0;fun setTextColor(color:Int)=Unit}
class Button(context:Context):TextView(context)
