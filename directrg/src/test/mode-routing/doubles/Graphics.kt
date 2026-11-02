package android.graphics
class Paint(flags:Int) {
    enum class Style { FILL,STROKE }
    var style=Style.FILL;var strokeWidth=0f;var color=0;var textSize=0f
    companion object { const val ANTI_ALIAS_FLAG=1 }
}
object Color { const val BLACK=0;const val YELLOW=-256 }
class Canvas {
    val labels=mutableListOf<String>()
    fun drawCircle(x:Float,y:Float,radius:Float,paint:Paint) {}
    fun drawText(text:String,x:Float,y:Float,paint:Paint) { labels.add(text) }
}
