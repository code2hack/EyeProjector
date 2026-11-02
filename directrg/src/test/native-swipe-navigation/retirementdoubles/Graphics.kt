package android.graphics
class Canvas {fun drawRect(left:Float,top:Float,right:Float,bottom:Float,paint:Paint)=Unit}
object Color {const val YELLOW=-256;const val WHITE=-1;fun argb(a:Int,r:Int,g:Int,b:Int)=0}
class Paint(flags:Int){var color=0;var style=Style.STROKE;var strokeWidth=0f;enum class Style{STROKE};companion object{const val ANTI_ALIAS_FLAG=1}}
