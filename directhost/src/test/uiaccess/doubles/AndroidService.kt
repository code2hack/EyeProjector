package android.accessibilityservice

import android.view.accessibility.AccessibilityWindowInfo
import android.util.SparseArray
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AccessibilityServiceInfo(var flags: Int = FLAG_RETRIEVE_INTERACTIVE_WINDOWS,
    val capabilities: Int = CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) {
    companion object {
        const val CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT = 1
        const val FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 64
        const val FLAG_REQUEST_FILTER_KEY_EVENTS = 32
        const val FLAG_REPORT_VIEW_IDS = 16
    }
}
open class AccessibilityService(
    var serviceInfo: AccessibilityServiceInfo = AccessibilityServiceInfo(),
    var windowsOnAllDisplays: SparseArray<List<AccessibilityWindowInfo>> = SparseArray(emptyMap()),
) : Context() {
    open fun onServiceConnected() {}
    open fun onKeyEvent(event: KeyEvent) = false
    open fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    open fun onInterrupt() {}
    open fun onUnbind(intent: Intent?) = false
    open fun onDestroy() {}
}
