package com.code2hack.eyeprojector.directhost

/** A cleanup budget, never a contact/session expiry. Transport/display teardown follows. */
internal object HeldInputRelease {
    const val CANCEL_JOIN_MS = 250L
    fun beforeTransportClose(release: () -> Unit): Thread {
        val worker = Thread({ runCatching(release) }, "eyeprojector-input-cancel").apply { isDaemon = true; start() }
        try { worker.join(CANCEL_JOIN_MS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return worker
    }
}
