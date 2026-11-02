package android.graphics
class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    fun set(other: Rect) { left = other.left; top = other.top; right = other.right; bottom = other.bottom }
}
