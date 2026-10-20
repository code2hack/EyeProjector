package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class HostActivity : Activity() {
    private lateinit var bootstrap: HostBootstrap
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var authorize: Button
    private val observer: (HostBootstrap.Status) -> Unit = { state ->
        status.text = getString(R.string.host_status, setupText(state.setup), backendText(state))
        start.isEnabled = state.setup == HostBootstrap.Setup.AVAILABLE && state.backend !in listOf("STARTING", "STOPPING", "READY", "STOP_UNVERIFIED")
        authorize.isEnabled = state.setup == HostBootstrap.Setup.AUTHORIZATION_REQUIRED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bootstrap = HostBootstrap.get(this)
        status = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE) }
        start = button(R.string.host_start) { bootstrap.start() }
        authorize = button(R.string.host_authorize) { bootstrap.requestAuthorization() }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24)
            addView(status); addView(start)
            addView(button(R.string.host_stop) { bootstrap.stop() })
            addView(authorize)
            addView(button(R.string.host_open_shizuku) {
                val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent != null) startActivity(intent) else openGuide()
            })
            addView(button(R.string.host_setup_guide) { openGuide() })
        })
    }

    override fun onStart() { super.onStart(); bootstrap.observe(observer); bootstrap.refresh() }
    override fun onResume() { super.onResume(); if (::bootstrap.isInitialized) bootstrap.refresh() }
    override fun onStop() { bootstrap.removeObserver(observer); super.onStop() }

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
        state.detail == "SESSION_SETUP_REQUIRED" -> R.string.host_session_missing
        state.detail == "BIND_PENDING_REOPEN_SHIZUKU" -> R.string.host_bind_pending
        state.backend == "READY" -> R.string.host_listening
        state.backend == "STARTING" -> R.string.host_starting
        state.backend == "STOPPED" -> R.string.host_stopped
        state.backend == "STOPPING" -> R.string.host_stopping
        state.backend == "STOP_UNVERIFIED" -> R.string.host_stop_unknown
        state.backend == "FAILED" -> R.string.host_start_failed
        else -> R.string.host_unknown
    })
}
