package com.code2hack.eyeprojector.directhost.widget

import com.code2hack.eyeprojector.directhost.PanelPowerState
import com.code2hack.eyeprojector.directhost.ProjectionContext

/** Panel query and command lanes may complete out of order. Only the latest local turn can publish. */
internal class PhoneWidgetPanel(
    private val sessions: PhoneWidgetSession,
    private val changed: () -> Unit,
) {
    var result: PanelPowerState? = null
        private set
    var submitting: Boolean = false
        private set
    private var projection = ProjectionContext()
    private var available = false
    private var generation = 0L
    private var queryPending = false

    fun reconcile(snapshot: PhoneWidgetSnapshot) {
        if (projection != snapshot.projection || available != snapshot.canPanelOff) {
            projection = snapshot.projection
            available = snapshot.canPanelOff
            generation++
            result = null; submitting = false; queryPending = false
        }
    }

    fun query() {
        if (queryPending || submitting || !available) return
        val expected = projection
        val turn = ++generation
        queryPending = true
        sessions.panelState { reply ->
            if (!current(turn, expected)) return@panelState
            queryPending = false
            result = reply?.takeIf { it.context.ownerEpoch == expected.ownerEpoch && it.context.projectionToken == expected.projectionToken }
            changed()
        }
    }

    fun off(expected: ProjectionContext) {
        val actual = sessions.snapshot()
        reconcile(actual)
        if (!actual.canPanelOff || expected != projection || submitting) return
        val turn = ++generation
        queryPending = false; submitting = true; result = null
        changed()
        sessions.requestPanelOff(expected) { reply ->
            if (!current(turn, expected)) return@requestPanelOff
            submitting = false
            result = reply?.takeIf { it.context.ownerEpoch == expected.ownerEpoch && it.context.projectionToken == expected.projectionToken }
            changed()
        }
    }

    fun close() { generation++; queryPending = false; submitting = false; result = null }

    private fun current(turn: Long, expected: ProjectionContext): Boolean {
        val actual = sessions.snapshot()
        reconcile(actual)
        return generation == turn && projection == expected && actual.projection == expected && actual.canPanelOff
    }
}
