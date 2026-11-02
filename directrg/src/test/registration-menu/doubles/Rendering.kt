package android.graphics
class Paint(flags:Int=0) {
    enum class Style { FILL, STROKE };enum class Align { CENTER }
    var color=0;var style=Style.FILL;var strokeWidth=0f;var textSize=0f;var textAlign=Align.CENTER
    fun ascent()=-10f;fun descent()=2f
    companion object { const val ANTI_ALIAS_FLAG=1 }
}
class Canvas {
    val rectangles=mutableListOf<List<Float>>();val labels=mutableListOf<String>()
    val rectangleColors=mutableListOf<Int>()
    fun drawRect(l:Float,t:Float,r:Float,b:Float,p:Paint){rectangles.add(listOf(l,t,r,b));rectangleColors.add(p.color)}
    fun drawText(text:String,x:Float,y:Float,p:Paint){labels.add(text)}
}
object Color { const val YELLOW=-256;const val BLACK=-16777216;const val WHITE=-1
    fun argb(a:Int,r:Int,g:Int,b:Int)=(a shl 24)or(r shl 16)or(g shl 8)or b }
