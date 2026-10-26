package android.view

import android.graphics.Matrix
import android.graphics.Insets
import android.graphics.Point
import android.graphics.Rect

/** Each fixture supplies independent platform observations, rather than deriving layout results. */
class ViewFacts {
    var measuredWidth = 100
    var measuredHeight = 80
    var width = 100
    var height = 80
    var left = 0
    var top = 0
    var right = 100
    var bottom = 80
    var visibility = View.VISIBLE
    var windowVisibility = View.VISIBLE
    var shown = true
    var attached = true
    var laidOut = true
    var layoutRequested = false
    var alpha = 1f
    var rotation = 0f
    var rotationX = 0f
    var rotationY = 0f
    var scaleX = 1f
    var scaleY = 1f
    var translationX = 0f
    var translationY = 0f
    var translationZ = 0f
    var pivotX = 0f
    var pivotY = 0f
    var scrollX = 0
    var scrollY = 0
    var elevation = 0f
    var z = 0f
    var layoutDirection = View.LAYOUT_DIRECTION_LTR
    var id = View.NO_ID
    var matrix = Matrix()
    var localToGlobalMatrix = Matrix()
    var root: View? = null
    var parent: ViewParent? = null
    var display: Display? = Display()
    var insets: WindowInsets? = null
    var windowOrigin = Point(0, 0)
    var screenOrigin = Point(0, 0)
    var globalVisible = true
    var globalVisibleRect = Rect(0, 0, 100, 80)
    var globalOffset = Point(0, 0)
    var windowVisibleDisplayFrame = Rect(0, 0, 1080, 1920)
    var clipBounds: Rect? = null
}

interface ViewParent

open class View(val label: String = "view", val facts: ViewFacts = ViewFacts()) : ViewParent {
    private fun <T> read(name: String, value: () -> T): T {
        ViewProbe.reads.add(ViewCall(label, name))
        return value()
    }
    val measuredWidth get() = read("measuredWidth") { facts.measuredWidth }
    val measuredHeight get() = read("measuredHeight") { facts.measuredHeight }
    val width get() = read("width") { facts.width }
    val height get() = read("height") { facts.height }
    val left get() = read("left") { facts.left }
    val top get() = read("top") { facts.top }
    val right get() = read("right") { facts.right }
    val bottom get() = read("bottom") { facts.bottom }
    val visibility get() = read("visibility") { facts.visibility }
    val windowVisibility get() = read("windowVisibility") { facts.windowVisibility }
    val isShown get() = read("isShown") { facts.shown }
    val isAttachedToWindow get() = read("isAttachedToWindow") { facts.attached }
    val isLaidOut get() = read("isLaidOut") { facts.laidOut }
    val isLayoutRequested get() = read("isLayoutRequested") { facts.layoutRequested }
    val alpha get() = read("alpha") { facts.alpha }
    val rotation get() = read("rotation") { facts.rotation }
    val rotationX get() = read("rotationX") { facts.rotationX }
    val rotationY get() = read("rotationY") { facts.rotationY }
    val scaleX get() = read("scaleX") { facts.scaleX }
    val scaleY get() = read("scaleY") { facts.scaleY }
    val translationX get() = read("translationX") { facts.translationX }
    val translationY get() = read("translationY") { facts.translationY }
    val translationZ get() = read("translationZ") { facts.translationZ }
    val pivotX get() = read("pivotX") { facts.pivotX }
    val pivotY get() = read("pivotY") { facts.pivotY }
    val scrollX get() = read("scrollX") { facts.scrollX }
    val scrollY get() = read("scrollY") { facts.scrollY }
    val elevation get() = read("elevation") { facts.elevation }
    val z get() = read("z") { facts.z }
    val layoutDirection get() = read("layoutDirection") { facts.layoutDirection }
    val id get() = read("id") { facts.id }
    val matrix get() = read("matrix") { facts.matrix }
    val rootView get() = read("rootView") { facts.root ?: this }
    val parent get() = read("parent") { facts.parent }
    val display get() = read("display") { facts.display }
    val rootWindowInsets get() = read("rootWindowInsets") { facts.insets }
    val clipBounds get() = read("clipBounds") { facts.clipBounds?.let(::Rect) }

    fun getLocationInWindow(destination: IntArray) {
        read("getLocationInWindow") { facts.windowOrigin }.let { destination[0] = it.x; destination[1] = it.y }
    }
    fun getLocationOnScreen(destination: IntArray) {
        read("getLocationOnScreen") { facts.screenOrigin }.let { destination[0] = it.x; destination[1] = it.y }
    }
    fun transformMatrixToGlobal(destination: Matrix) {
        ViewProbe.reads.add(ViewCall(label, "transformMatrixToGlobal"))
        val values = FloatArray(9)
        facts.localToGlobalMatrix.getValues(values)
        destination.setValues(values)
    }
    fun getGlobalVisibleRect(destination: Rect, globalOffset: Point): Boolean {
        ViewProbe.reads.add(ViewCall(label, "getGlobalVisibleRect(Rect,Point)"))
        destination.set(facts.globalVisibleRect)
        globalOffset.set(facts.globalOffset.x, facts.globalOffset.y)
        return facts.globalVisible
    }
    fun getGlobalVisibleRect(destination: Rect): Boolean = getGlobalVisibleRect(destination, Point())
    fun getWindowVisibleDisplayFrame(destination: Rect) {
        ViewProbe.reads.add(ViewCall(label, "getWindowVisibleDisplayFrame"))
        destination.set(facts.windowVisibleDisplayFrame)
    }

    // Android-style mutators remain observable should production accidentally use one.
    fun requestLayout() { ViewProbe.mutations.add(ViewCall(label, "requestLayout")) }
    fun invalidate() { ViewProbe.mutations.add(ViewCall(label, "invalidate")) }
    fun setVisibility(value: Int) { ViewProbe.mutations.add(ViewCall(label, "setVisibility")); facts.visibility = value }
    fun setAlpha(value: Float) { ViewProbe.mutations.add(ViewCall(label, "setAlpha")); facts.alpha = value }
    fun setRotation(value: Float) { ViewProbe.mutations.add(ViewCall(label, "setRotation")); facts.rotation = value }
    fun layout(left: Int, top: Int, right: Int, bottom: Int) {
        ViewProbe.mutations.add(ViewCall(label, "layout"))
        facts.left = left; facts.top = top; facts.right = right; facts.bottom = bottom
    }
    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val NO_ID = -1
        const val LAYOUT_DIRECTION_LTR = 0
        const val LAYOUT_DIRECTION_RTL = 1
    }
}

class ViewGroup(label: String = "group", facts: ViewFacts = ViewFacts()) : View(label, facts) {
    val children = mutableListOf<View>()
    fun indexOfChild(child: View): Int {
        ViewProbe.reads.add(ViewCall(label, "indexOfChild"))
        return children.indexOf(child)
    }
    /** Fixture setup, separate from Android-style mutators observed by the exporter scenarios. */
    fun addFixtureChild(child: View) { children.add(child); child.facts.parent = this }
}

class WindowInsets(
    val systemWindowInsetLeft: Int = 0,
    val systemWindowInsetTop: Int = 0,
    val systemWindowInsetRight: Int = 0,
    val systemWindowInsetBottom: Int = 0,
) {
    val insets = mutableMapOf<Int, Insets>()
    val ignoringVisibilityInsets = mutableMapOf<Int, Insets>()
    val visible = mutableMapOf<Int, Boolean>()
    fun getInsets(typeMask: Int): Insets {
        ViewProbe.reads.add(ViewCall("WindowInsets", "getInsets:$typeMask"))
        return insets[typeMask] ?: Insets.NONE
    }
    fun getInsetsIgnoringVisibility(typeMask: Int): Insets {
        ViewProbe.reads.add(ViewCall("WindowInsets", "getInsetsIgnoringVisibility:$typeMask"))
        return ignoringVisibilityInsets[typeMask] ?: Insets.NONE
    }
    fun isVisible(typeMask: Int): Boolean {
        ViewProbe.reads.add(ViewCall("WindowInsets", "isVisible:$typeMask"))
        return visible[typeMask] ?: false
    }
    object Type {
        @JvmStatic fun systemBars(): Int = 7
        @JvmStatic fun ime(): Int = 8
        @JvmStatic fun systemGestures(): Int = 16
        @JvmStatic fun displayCutout(): Int = 128
    }
}

class Display(val displayId: Int = 0, val rotation: Int = Surface.ROTATION_0)
object Surface {
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3
}

data class ViewCall(val view: String, val member: String)
object ViewProbe {
    val reads = mutableListOf<ViewCall>()
    val mutations = mutableListOf<ViewCall>()
    fun reset() { reads.clear(); mutations.clear() }
}
