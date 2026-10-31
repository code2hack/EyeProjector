package android.view.accessibility

import android.graphics.Rect

class AccessibilityEvent

class AccessibilityNodeInfo(
    val packageName: CharSequence? = "selected.app",
    val windowId: Int = 7,
    val viewIdResourceName: String? = null,
    val className: CharSequence? = "android.widget.Button",
    val screen: Rect = Rect(24, 95, 1224, 221),
    val isClickable: Boolean = true,
    val isLongClickable: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val isEnabled: Boolean = true,
    val actionList: List<AccessibilityAction> = emptyList(),
    val children: List<AccessibilityNodeInfo?> = emptyList(),
    val failBounds: Boolean = false,
) {
    var closed = 0
    var childrenRead = 0
    val childCount get() = children.size
    fun getChild(index: Int): AccessibilityNodeInfo? { childrenRead++; return children[index] }
    fun getBoundsInScreen(out: Rect) {
        if (failBounds) throw IllegalStateException("Content must not reach failure output")
        out.set(screen)
    }
    fun recycle() { closed++ }
    val text: CharSequence get() = error("Must not read content")
    val contentDescription: CharSequence get() = error("Must not read content")
    fun performAction(action: Int): Boolean = error("Must not invoke actions")
    class AccessibilityAction(val id: Int) {
        val label: CharSequence get() = error("Must not read labels")
        companion object { val ACTION_PRESS_AND_HOLD = AccessibilityAction(16973842) }
    }
    companion object { const val ACTION_CLICK = 16; const val ACTION_LONG_CLICK = 32 }
}

class AccessibilityWindowInfo(
    val displayId: Int = 34,
    val id: Int = 7,
    val type: Int = 1,
    val layer: Int = 0,
    val isActive: Boolean = true,
    val isFocused: Boolean = true,
    private val node: AccessibilityNodeInfo? = null,
    private val screen: Rect = Rect(0, 0, 1248, 1664),
) {
    var rootsRead = 0
    var closed = 0
    val root: AccessibilityNodeInfo? get() { rootsRead++; return node }
    val title: CharSequence get() = error("Must not read window content")
    fun getBoundsInScreen(out: Rect) = out.set(screen)
    fun recycle() { closed++ }
}
