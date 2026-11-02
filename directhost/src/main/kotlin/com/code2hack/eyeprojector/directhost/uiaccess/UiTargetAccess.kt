package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** App-process connection only; the enabled Android service owns access, never the shell backend. */
object UiTargetAccess {
    internal data class WindowChange(val displayId: Int, val windowId: Int, val currentChanged: Boolean)
    private class Connection(val service: AccessibilityService, val reader: UiTargetReader)
    @Volatile private var connection: Connection? = null
    private val observers = CopyOnWriteArraySet<(WindowChange?) -> Unit>()

    internal fun observe(changed: (WindowChange?) -> Unit): Closeable {
        observers.add(changed)
        return Closeable { observers.remove(changed) }
    }

    @Synchronized internal fun connected(service: AccessibilityService) {
        connection = Connection(service, UiTargetReader(AndroidUiAccess(service), SystemClock::uptimeMillis))
        observers.forEach { it(null) }
    }

    @Synchronized internal fun disconnected(service: AccessibilityService) {
        if (connection?.service === service) { connection = null; observers.forEach { it(null) } }
    }

    /** Window identity/lifetime metadata only. Content refreshes do not retire a held contact. */
    internal fun windowChanged(service: AccessibilityService, event: AccessibilityEvent?) {
        if (connection?.service !== service || event == null) return
        val current = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges and
                (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED) != 0
        val removedOrMoved = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges and
            (AccessibilityEvent.WINDOWS_CHANGE_REMOVED or AccessibilityEvent.WINDOWS_CHANGE_BOUNDS) != 0
        if (current || removedOrMoved) {
            val change = WindowChange(event.displayId, event.windowId, current)
            observers.forEach { it(change) }
        }
    }

    fun reader(): UiTargetReader? = connection?.reader
}
