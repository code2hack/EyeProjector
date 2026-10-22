package android.accessibilityservice

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Explicit public callback entrypoints; tests invoke the actual service overrides. */
open class AccessibilityService : Context() {
    var serviceInfo = AccessibilityServiceInfo()
    open fun onServiceConnected() {}
    open fun onKeyEvent(event: KeyEvent): Boolean = false
    open fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    open fun onInterrupt() {}
    open fun onUnbind(intent: Intent?): Boolean = false
    open fun onDestroy() {}
}

class AccessibilityServiceInfo {
    val resolveInfo = android.content.pm.ResolveInfo()
    var flags = 0
    var eventTypes = 0
    var feedbackType = 0
    var notificationTimeout = 0L
    companion object {
        const val FLAG_REQUEST_FILTER_KEY_EVENTS = 0x20
        const val FEEDBACK_GENERIC = 0x10
        const val FEEDBACK_ALL_MASK = -1
    }
}
