package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock

/** App-process connection only; the enabled Android service owns access, never the shell backend. */
object UiTargetAccess {
    private class Connection(val service: AccessibilityService, val reader: UiTargetReader)
    @Volatile private var connection: Connection? = null

    @Synchronized internal fun connected(service: AccessibilityService) {
        connection = Connection(service, UiTargetReader(AndroidUiAccess(service), SystemClock::uptimeMillis))
    }

    @Synchronized internal fun disconnected(service: AccessibilityService) {
        if (connection?.service === service) connection = null
    }

    fun reader(): UiTargetReader? = connection?.reader
}
