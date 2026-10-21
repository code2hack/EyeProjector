package com.code2hack.eyeprojector.directhost.widget

import com.code2hack.eyeprojector.protocol.SessionPhase

/** One Activity-owned observation turn. It never changes the rendered action or retries dispatch. */
internal class PhoneWidgetActionTurn(
    private val snapshot: () -> PhoneWidgetSnapshot,
    private val dispatch: () -> PhoneWidgetRoute,
    private val complete: (PhoneWidgetRoute) -> Unit,
) {
    var finished = false
        private set

    fun advance() {
        if (finished) return
        val actual = snapshot()
        if (actual.session.phase == SessionPhase.UNKNOWN && !actual.needsSetup) return
        finished = true
        complete(dispatch())
    }

    fun openStatus() {
        if (finished) return
        finished = true
        complete(PhoneWidgetRoute.OPEN_STATUS)
    }

    fun cancel() { finished = true }
}
