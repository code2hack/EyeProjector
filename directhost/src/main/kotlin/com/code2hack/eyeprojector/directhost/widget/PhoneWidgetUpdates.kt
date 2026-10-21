package com.code2hack.eyeprojector.directhost.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.PhoneSessionController

/** Process-local view subscription only. It owns no service, thread, timer or projection lifetime. */
object PhoneWidgetUpdates {
    private val main = Handler(Looper.getMainLooper())
    @SuppressLint("StaticFieldLeak") // Binding retains applicationContext only.
    private var binding: Binding? = null

    fun attach(context: Context, sessions: PhoneSessionController) {
        val app = context.applicationContext
        onMain {
            // A launcher/render failure must never prevent get(context).stop() from reaching its owner.
            runCatching {
                val active = binding ?: Binding(app, sessions).also { binding = it }
                active.syncInstances()
            }
        }
    }

    internal fun update(context: Context) {
        val sessions = PhoneSessionController.get(context)
        onMain {
            attach(context, sessions)
            binding?.publish()
            sessions.bootstrap.refresh()
        }
    }

    internal fun removed() { onMain { binding?.syncInstances() } }
    internal fun disabled() { onMain { binding?.close(); binding = null } }

    internal fun deliver(context: Context, widgetId: Int, action: PhoneWidgetAction): PhoneWidgetRoute {
        val sessions = PhoneSessionController.get(context)
        attach(context, sessions)
        val active = binding ?: return PhoneWidgetRoute.OPEN_STATUS
        if (widgetId !in active.ids()) return PhoneWidgetRoute.COMPLETE
        val route = PhoneWidgetActions(active.port, active.panel::off).deliver(action)
        active.publish()
        return route
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private class Binding(private val context: Context, private val sessions: PhoneSessionController) {
        private val manager = AppWidgetManager.getInstance(context)
        val port = PhoneWidgetSessionAdapter(sessions)
        val panel = PhoneWidgetPanel(port) { publish() }
        private var observing = false
        private val observer: () -> Unit = {
            if (ids().isEmpty()) close()
            else { publish(); panel.query() }
        }

        fun ids(): IntArray = manager.getAppWidgetIds(ComponentName(context, PhoneWidgetProvider::class.java))
        fun syncInstances() {
            if (ids().isEmpty()) { close(); return }
            if (!observing) {
                observing = true
                sessions.observeState(observer)
                publish()
            }
        }
        fun publish() {
            val snapshot = port.snapshot()
            panel.reconcile(snapshot)
            ids().forEach { manager.updateAppWidget(it, PhoneWidgetRenderer.views(context, it, snapshot, panel)) }
        }
        fun close() {
            if (observing) sessions.removeStateObserver(observer)
            observing = false
            panel.close()
        }
    }
}
