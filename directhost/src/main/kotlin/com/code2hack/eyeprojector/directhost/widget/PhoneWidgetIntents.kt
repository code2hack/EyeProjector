package com.code2hack.eyeprojector.directhost.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.code2hack.eyeprojector.directhost.ProjectionContext

/** Command, widget, epoch and target are token identity, not mutable replacement extras. */
internal object PhoneWidgetIntents {
    private const val ACTION = "com.code2hack.eyeprojector.widget.COMMAND"
    private const val WIDGET = "widget"
    private const val COMMAND = "command"
    private const val EPOCH = "epoch"
    private const val ACTIVATION = "activation"

    fun pending(context: Context, widgetId: Int, action: PhoneWidgetAction): PendingIntent =
        PendingIntent.getActivity(context, widgetId, intent(context, widgetId, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun intent(context: Context, widgetId: Int, action: PhoneWidgetAction): Intent =
        Intent(context, PhoneWidgetActionActivity::class.java).apply {
            this.action = ACTION
            data = Uri.Builder().scheme("eyeprojector-widget").authority("command")
                .appendPath(widgetId.toString()).appendPath(action.command.name).appendPath(action.epoch)
                .appendPath(action.activationId.orEmpty()).appendPath(action.projection.ownerEpoch).appendPath(action.projection.projectionToken).build()
            putExtra(WIDGET, widgetId)
            putExtra(COMMAND, action.command.name)
            putExtra(EPOCH, action.epoch)
            putExtra(ACTIVATION, action.activationId)
            putExtra(ProjectionContext.OWNER_EXTRA, action.projection.ownerEpoch)
            putExtra(ProjectionContext.TOKEN_EXTRA, action.projection.projectionToken)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }

    fun widgetId(intent: Intent): Int = intent.getIntExtra(WIDGET, -1)
    fun read(intent: Intent): PhoneWidgetAction? {
        if (intent.action != ACTION) return null
        val command = PhoneWidgetCommand.entries.firstOrNull { it.name == intent.getStringExtra(COMMAND) } ?: return null
        val epoch = intent.getStringExtra(EPOCH) ?: return null
        return PhoneWidgetAction(command, epoch, ProjectionContext(
            intent.getStringExtra(ProjectionContext.OWNER_EXTRA).orEmpty(),
            intent.getStringExtra(ProjectionContext.TOKEN_EXTRA).orEmpty()), intent.getStringExtra(ACTIVATION))
    }
}
