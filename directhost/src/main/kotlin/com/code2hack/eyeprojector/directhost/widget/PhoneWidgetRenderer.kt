package com.code2hack.eyeprojector.directhost.widget

import android.content.Context
import android.widget.RemoteViews
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.protocol.SessionPhase

internal object PhoneWidgetRenderer {
    fun views(context: Context, widgetId: Int, snapshot: PhoneWidgetSnapshot, panel: PhoneWidgetPanel): RemoteViews {
        val primary = if (snapshot.canStart && snapshot.needsSetup) PhoneWidgetCommand.STATUS else snapshot.primary
        val startLabel = snapshot.canStart
        val state = context.getString(when (snapshot.session.phase) {
            SessionPhase.INACTIVE -> if (snapshot.canStart) R.string.phone_widget_inactive else R.string.phone_widget_unknown
            SessionPhase.STARTING -> R.string.phone_widget_starting
            SessionPhase.AVAILABLE -> R.string.phone_widget_available
            SessionPhase.CONNECTING -> R.string.phone_widget_connecting
            SessionPhase.STREAMING -> R.string.phone_widget_streaming
            SessionPhase.STOPPING -> R.string.phone_widget_stopping
            SessionPhase.UNKNOWN -> R.string.phone_widget_unknown
            SessionPhase.FAILED -> if (snapshot.canStart) R.string.phone_widget_failed_released else R.string.phone_widget_failed
        })
        val status = if (snapshot.needsSetup) "$state\n${context.getString(R.string.phone_widget_setup)}" else state
        return RemoteViews(context.packageName, R.layout.phone_widget).apply {
            setTextViewText(R.id.phone_widget_status, status)
            setTextViewText(R.id.phone_widget_primary, context.getString(if (startLabel) R.string.phone_widget_start else R.string.phone_widget_stop))
            setContentDescription(R.id.phone_widget_primary, context.getString(when {
                primary == PhoneWidgetCommand.STATUS -> R.string.phone_widget_setup_description
                startLabel -> R.string.phone_widget_start_description
                else -> R.string.phone_widget_stop_description
            }))
            setOnClickPendingIntent(R.id.phone_widget_primary,
                PhoneWidgetIntents.pending(context, widgetId, PhoneWidgetAction(primary, snapshot.session.epoch, activationId = snapshot.activationId)))
            setBoolean(R.id.phone_widget_panel, "setEnabled", snapshot.canPanelOff && !panel.submitting && panel.result?.off != PanelSubmission.PENDING)
            setOnClickPendingIntent(R.id.phone_widget_panel, PhoneWidgetIntents.pending(context, widgetId,
                PhoneWidgetAction(PhoneWidgetCommand.PANEL_OFF, snapshot.session.epoch, snapshot.projection)))
            setTextViewText(R.id.phone_widget_panel_status, panelText(context, snapshot, panel))
        }
    }

    private fun panelText(context: Context, snapshot: PhoneWidgetSnapshot, panel: PhoneWidgetPanel): String {
        val result = panel.result
        val message = when {
            panel.submitting -> R.string.phone_widget_panel_pending
            !snapshot.canPanelOff -> R.string.phone_widget_panel_unavailable
            result == null -> R.string.phone_widget_panel_unknown
            result.off == PanelSubmission.PENDING -> R.string.phone_widget_panel_pending
            result.off == PanelSubmission.SUBMITTED -> R.string.phone_widget_panel_submitted
            result.off == PanelSubmission.FAILED -> R.string.phone_widget_panel_failed
            result.off == PanelSubmission.UNAVAILABLE -> R.string.phone_widget_panel_unknown
            else -> R.string.phone_widget_panel_ready
        }
        val restoration = when (result?.restoration) {
            PanelRestoration.PENDING -> R.string.phone_widget_restore_pending
            PanelRestoration.SUBMITTED -> R.string.phone_widget_restore_submitted
            PanelRestoration.FAILED -> R.string.phone_widget_restore_failed
            else -> null
        }
        return context.getString(message) + (restoration?.let { "\n${context.getString(it)}" } ?: "")
    }
}
