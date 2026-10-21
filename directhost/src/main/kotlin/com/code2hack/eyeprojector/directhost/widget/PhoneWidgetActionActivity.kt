package com.code2hack.eyeprojector.directhost.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.HostActivity
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.R

/** Direct widget Activity PendingIntent keeps setup navigation on an Activity route. */
class PhoneWidgetActionActivity : Activity() {
    private var sessions: PhoneSessionController? = null
    private var turn: PhoneWidgetActionTurn? = null
    private val observer: () -> Unit = { turn?.advance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity recreation must not replay a command. App-scoped callbacks never retain this Activity.
        val action = PhoneWidgetIntents.read(intent)
        if (savedInstanceState != null || action == null) { finish(); return }
        if (action.command == PhoneWidgetCommand.STATUS) { complete(PhoneWidgetRoute.OPEN_STATUS); return }
        val owner = PhoneSessionController.get(this)
        sessions = owner
        val port = PhoneWidgetSessionAdapter(owner)
        val pending = PhoneWidgetActionTurn(port::snapshot,
            { PhoneWidgetUpdates.deliver(this, PhoneWidgetIntents.widgetId(intent), action) }, ::complete)
        turn = pending
        owner.observeState(observer)
        pending.advance()
        if (pending.finished) return
        // Existing asynchronous reconciliation supplies the next event; no new timer or polling owner.
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24); setBackgroundColor(Color.BLACK)
            addView(TextView(this@PhoneWidgetActionActivity).apply {
                setText(R.string.phone_widget_checking); setTextColor(Color.WHITE)
            })
            addView(Button(this@PhoneWidgetActionActivity).apply {
                setText(R.string.phone_widget_open_status); setOnClickListener { pending.openStatus() }
            })
        })
        owner.bootstrap.refresh()
    }

    private fun complete(route: PhoneWidgetRoute) {
        sessions?.removeStateObserver(observer)
        if (route == PhoneWidgetRoute.OPEN_STATUS) {
            startActivity(Intent(this, HostActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    override fun onDestroy() {
        turn?.cancel()
        sessions?.removeStateObserver(observer)
        super.onDestroy()
    }

    override fun finish() {
        turn?.cancel()
        sessions?.removeStateObserver(observer)
        super.finish()
    }
}
