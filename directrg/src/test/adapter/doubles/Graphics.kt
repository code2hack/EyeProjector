package android.graphics

class Canvas {
    fun drawCircle(x: Float, y: Float, radius: Float, paint: Paint) = Unit
}

object Color {
    const val BLACK = -16777216
}

class Paint(flags: Int) {
    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
    enum class Style { STROKE, FILL }
    var style = Style.STROKE
    var strokeWidth = 0f
    var color = 0
}
