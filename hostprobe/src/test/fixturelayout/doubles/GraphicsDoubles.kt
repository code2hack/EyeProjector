package android.graphics

/** Explicit mutable API containers; these doubles do not compute Android visibility or transforms. */
class Point(var x: Int = 0, var y: Int = 0) {
    fun set(x: Int, y: Int) { this.x = x; this.y = y }
}

data class Insets private constructor(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val NONE = Insets(0, 0, 0, 0)
        @JvmStatic fun of(left: Int, top: Int, right: Int, bottom: Int) = Insets(left, top, right, bottom)
    }
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    constructor(other: Rect) : this(other.left, other.top, other.right, other.bottom)
    val isEmpty: Boolean get() = left >= right || top >= bottom
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }
    fun set(other: Rect) = set(other.left, other.top, other.right, other.bottom)
    fun offset(dx: Int, dy: Int) { left += dx; right += dx; top += dy; bottom += dy }
    fun setEmpty() = set(0, 0, 0, 0)
    override fun toString(): String = "Rect($left,$top,$right,$bottom)"
}

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(other: Rect) : this(other.left.toFloat(), other.top.toFloat(), other.right.toFloat(), other.bottom.toFloat())
    val isEmpty: Boolean get() = left >= right || top >= bottom
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

class Matrix {
    private var elements = identity()
    val isIdentity: Boolean get() = elements.contentEquals(identity())
    fun getValues(destination: FloatArray) { elements.copyInto(destination) }
    fun setValues(values: FloatArray) { elements = values.copyOf() }
    fun reset() { elements = identity() }
    companion object {
        const val MSCALE_X = 0
        const val MSKEW_X = 1
        const val MTRANS_X = 2
        const val MSKEW_Y = 3
        const val MSCALE_Y = 4
        const val MTRANS_Y = 5
        const val MPERSP_0 = 6
        const val MPERSP_1 = 7
        const val MPERSP_2 = 8
        private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
