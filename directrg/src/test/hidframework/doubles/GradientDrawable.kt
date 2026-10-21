package android.graphics.drawable

class GradientDrawable {
    var fillColor = 0; private set
    var strokeWidth = 0; private set
    var strokeColor = 0; private set
    fun setColor(value: Int) { fillColor = value }
    fun setStroke(width: Int, color: Int) { strokeWidth = width; strokeColor = color }
}
