package com.code2hack.eyeprojector.directhost.notifications

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.R
import java.io.Closeable

/** Feature-local view for a later allocated mount; closing UI never retires listener ingress. */
class NotificationAccessPanel internal constructor(context: Context, private val runtime: PhoneNotificationRuntime) : LinearLayout(context), Closeable {
    constructor(context: Context) : this(context, PhoneNotificationRuntime.get(context))
    private val statusText = TextView(context)
    private val setupError = TextView(context)
    private var observation: Closeable? = null

    init {
        orientation = VERTICAL
        addView(TextView(context).apply { setText(R.string.notification_access_title) })
        addView(statusText)
        addView(Button(context).apply {
            setText(R.string.notification_access_settings)
            setOnClickListener {
                setupError.text = if (NotificationAccess.openSettings(context)) "" else context.getString(R.string.notification_access_settings_unavailable)
                refresh()
            }
        })
        addView(Button(context).apply {
            setText(R.string.notification_access_refresh)
            setOnClickListener { refresh() }
        })
        addView(setupError)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); open() }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
    fun open() {
        if (observation == null) observation = runtime.observeStatus(::render)
        refresh()
    }
    /** Call on parent resume/return from Settings as well as the explicit refresh button. */
    fun refresh() { runtime.refresh(); render() }
    override fun close() { observation?.close(); observation = null }
    private fun render() {
        val status = runtime.status()
        val access = when (status.approval) {
            NotificationAccessApproval.GRANTED -> R.string.notification_access_on
            NotificationAccessApproval.DENIED -> R.string.notification_access_off
            NotificationAccessApproval.UNKNOWN -> R.string.notification_access_unknown
        }
        val connection = if (status.connected) R.string.notification_listener_connected else R.string.notification_listener_disconnected
        val baseline = when (status.baseline) {
            NotificationListenerBaseline.DISCONNECTED -> R.string.notification_baseline_disconnected
            NotificationListenerBaseline.NO_CHOSEN_APPS -> R.string.notification_baseline_no_apps
            NotificationListenerBaseline.INITIALIZING -> R.string.notification_baseline_initializing
            NotificationListenerBaseline.OBSERVED -> R.string.notification_baseline_observed
            NotificationListenerBaseline.UNAVAILABLE -> R.string.notification_baseline_unavailable
        }
        statusText.text = listOf(access, connection, baseline).joinToString("\n") { context.getString(it) }
    }
}
