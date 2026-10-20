package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import com.code2hack.eyeprojector.directhost.apps.ChosenAppsPanel
import com.code2hack.eyeprojector.protocol.PairingPanel
import com.code2hack.eyeprojector.protocol.PeerRole
import com.code2hack.eyeprojector.protocol.SessionPhase
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView

class HostActivity : Activity() {
    private lateinit var bootstrap: HostBootstrap
    private lateinit var sessions: PhoneSessionController
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var authorize: Button
    private lateinit var pairingPanel: PairingPanel
    private val observer: (HostBootstrap.Status) -> Unit = { state ->
        val session = sessions.state()
        val sessionText = when (session.phase) {
            SessionPhase.STARTING -> "Starting projection"
            SessionPhase.AVAILABLE -> "Waiting for the paired Glasses to connect"
            SessionPhase.CONNECTING -> "Connecting; waiting for displayed video"
            SessionPhase.STREAMING -> "Projecting on the paired Glasses"
            SessionPhase.STOPPING -> "Stopping projection"
            SessionPhase.INACTIVE -> "Projection inactive"
            SessionPhase.FAILED -> "Projection failed: ${session.detail}"
            SessionPhase.UNKNOWN -> "Projection state unavailable"
        }
        status.text = getString(R.string.host_status, setupText(state.setup), sessionText + "\n" + backendText(state))
        start.isEnabled = pairingPanel.isPaired() && !pairingPanel.forgetting && state.setup == HostBootstrap.Setup.AVAILABLE &&
            !session.phase.active && session.phase !in setOf(SessionPhase.STOPPING, SessionPhase.UNKNOWN) &&
            state.backend !in listOf("STARTING", "STOPPING", "READY", "SESSION_STARTING", "STREAMING", "STOP_UNVERIFIED") &&
            state.detail !in listOf("FORGET_PENDING", "RESTORE_SHIZUKU_TO_VERIFY_STOP")
        authorize.isEnabled = state.setup == HostBootstrap.Setup.AUTHORIZATION_REQUIRED
    }
    private val sessionTick = object : Runnable {
        override fun run() { observer(bootstrap.status()); main.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = PhoneSessionController.get(this)
        bootstrap = sessions.bootstrap
        status = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE) }
        start = button(R.string.host_start) { sessions.start() }
        authorize = button(R.string.host_authorize) { bootstrap.requestAuthorization() }
        val stop = button(R.string.host_stop) { sessions.stop() }
        pairingPanel = PairingPanel(this, PeerRole.PHONE, { paired ->
            start.visibility = if (paired) View.VISIBLE else View.GONE
            stop.visibility = if (paired) View.VISIBLE else View.GONE
            bootstrap.refresh()
        }, { complete ->
            start.isEnabled = false; stop.isEnabled = false
            sessions.stop()
            bootstrap.stopBeforeForget { complete(); stop.isEnabled = true }
        }, sessions.pairing)
        pairingPanel.addView(start, 2)
        pairingPanel.addView(stop, 3)
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@HostActivity).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24)
                addView(pairingPanel); addView(status)
                addView(authorize)
                addView(button(R.string.host_open_shizuku) {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent) else openGuide()
                })
                addView(button(R.string.host_setup_guide) { openGuide() })
                addView(ChosenAppsPanel(this@HostActivity))
            })
        })
    }

    override fun onStart() { super.onStart(); bootstrap.observe(observer); bootstrap.refresh(); main.post(sessionTick) }
    override fun onResume() { super.onResume(); if (::bootstrap.isInitialized) bootstrap.refresh() }
    override fun onStop() { main.removeCallbacks(sessionTick); bootstrap.removeObserver(observer); super.onStop() }
    override fun onDestroy() { pairingPanel.close(); super.onDestroy() }

    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        setText(label); setOnClickListener { action() }
    }
    private fun openGuide() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }
            .onFailure { status.setText(R.string.host_guide_address) }
    }
    private fun setupText(setup: HostBootstrap.Setup) = getString(when (setup) {
        HostBootstrap.Setup.MISSING -> R.string.host_shizuku_missing
        HostBootstrap.Setup.UNAVAILABLE -> R.string.host_shizuku_unavailable
        HostBootstrap.Setup.UPDATE_REQUIRED -> R.string.host_shizuku_update
        HostBootstrap.Setup.AUTHORIZATION_REQUIRED -> R.string.host_shizuku_authorize
        HostBootstrap.Setup.SHELL_REQUIRED -> R.string.host_shizuku_shell
        HostBootstrap.Setup.AVAILABLE -> R.string.host_shizuku_available
    })
    private fun backendText(state: HostBootstrap.Status): String = getString(when {
        state.detail in listOf("FORGET_PENDING", "RESTORE_SHIZUKU_TO_VERIFY_STOP") -> R.string.host_forget_pending
        state.detail == "SESSION_SETUP_REQUIRED" -> R.string.host_session_missing
        state.detail == "BIND_PENDING_REOPEN_SHIZUKU" -> R.string.host_bind_pending
        state.backend == "READY" -> R.string.host_listening
        state.backend == "SESSION_STARTING" -> R.string.host_starting
        state.backend == "STREAMING" -> R.string.host_backend_streaming
        state.backend == "STARTING" -> R.string.host_starting
        state.backend == "STOPPED" -> R.string.host_stopped
        state.backend == "STOPPING" -> R.string.host_stopping
        state.backend == "STOP_UNVERIFIED" -> R.string.host_stop_unknown
        state.backend == "FAILED" -> R.string.host_start_failed
        else -> R.string.host_unknown
    })
}
