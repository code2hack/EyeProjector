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
import com.code2hack.eyeprojector.directhost.apps.ChosenApp
import com.code2hack.eyeprojector.protocol.PairingPanel
import com.code2hack.eyeprojector.protocol.PeerRole
import com.code2hack.eyeprojector.protocol.SessionPhase
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.hid.PhoneHidPanel
import com.code2hack.eyeprojector.directhost.hid.PhoneHidRuntime

class HostActivity : Activity() {
    private lateinit var notificationAccess: com.code2hack.eyeprojector.directhost.notifications.NotificationAccessPanel
    private lateinit var bootstrap: HostBootstrap
    private lateinit var sessions: PhoneSessionController
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var authorize: Button
    private lateinit var pairingPanel: PairingPanel
    private lateinit var chosenApps: ChosenAppsPanel
    private lateinit var launcherStatus: TextView
    private lateinit var phoneHid: PhoneHidPanel
    private lateinit var phoneControls: LinearLayout
    private var launchFeedbackIntent = 0L
    private var launchEnabled = false
    private var launchContext = ProjectionContext()
    private fun launchChosenApp(app: ChosenApp, expected: ProjectionContext) {
        val feedbackIntent = ++launchFeedbackIntent
        launcherStatus.text = getString(R.string.projected_home_opening, app.label)
        sessions.launchApp(app.packageName, expected) { result ->
            if (launchContext != expected || launchFeedbackIntent != feedbackIntent) return@launchApp
            launcherStatus.text = getString(when {
                result?.foregroundPackage == app.packageName -> R.string.projected_home_opened
                result?.status == ProjectionLaunchStatus.TIMED_OUT -> R.string.projected_home_timeout
                result?.status == ProjectionLaunchStatus.STARTED -> R.string.projected_home_unconfirmed
                else -> R.string.projected_home_unavailable
            }, app.label)
        }
    }
    private fun scaleChosenApp(app: ChosenApp, expected: ProjectionContext) {
        val feedbackIntent = ++launchFeedbackIntent
        launcherStatus.text = getString(R.string.projected_home_opening, app.label)
        val target = sessions.launchAppForScale(app.packageName, expected) { result ->
            if (launchContext != expected || launchFeedbackIntent != feedbackIntent) return@launchAppForScale
            launcherStatus.text = getString(when {
                result?.foregroundPackage == app.packageName -> R.string.projected_home_opened
                result?.status == ProjectionLaunchStatus.TIMED_OUT -> R.string.projected_home_timeout
                result?.status == ProjectionLaunchStatus.STARTED -> R.string.projected_home_unconfirmed
                else -> R.string.projected_home_unavailable
            }, app.label)
        }
        startActivity(com.code2hack.eyeprojector.directhost.scale.AppScaleActivity.intent(this, target))
    }
    private val observer: (HostBootstrap.Status) -> Unit = { state ->
        val session = sessions.state()
        val sessionText = if (sessions.activationId() != null) sessions.activationStatus() else when (session.phase) {
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
            sessions.activationId() == null && !session.phase.active && session.phase !in setOf(SessionPhase.STOPPING, SessionPhase.UNKNOWN) &&
            state.backend !in listOf("STARTING", "STOPPING", "READY", "SESSION_STARTING", "STREAMING", "STOP_UNVERIFIED") &&
            state.detail !in listOf("FORGET_PENDING", "RESTORE_SHIZUKU_TO_VERIFY_STOP")
        authorize.isEnabled = state.setup == HostBootstrap.Setup.AUTHORIZATION_REQUIRED
        val expected = state.projectionContext
        val canLaunch = session.phase == SessionPhase.STREAMING && expected.available
        if (::chosenApps.isInitialized && (canLaunch != launchEnabled || expected != launchContext)) {
            launchEnabled = canLaunch
            launchContext = expected
            chosenApps.onAppSelected = if (canLaunch) { app -> launchChosenApp(app, expected) } else null
            chosenApps.onAppScale = if (canLaunch) { app -> scaleChosenApp(app, expected) } else null
        }
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
        chosenApps = ChosenAppsPanel(this)
        notificationAccess = com.code2hack.eyeprojector.directhost.notifications.NotificationAccessPanel(this)
        launcherStatus = TextView(this).apply { setTextColor(Color.WHITE) }
        val stop = button(R.string.host_stop) { sessions.stop() }
        pairingPanel = PairingPanel(this, PeerRole.PHONE, { paired ->
            start.visibility = if (paired) View.VISIBLE else View.GONE
            stop.visibility = if (paired) View.VISIBLE else View.GONE
            bootstrap.refresh()
        }, { complete ->
            start.isEnabled = false; stop.isEnabled = false
            sessions.stopBeforeForget { complete(); stop.isEnabled = true }
        }, sessions.pairing)
        pairingPanel.addView(start, 2)
        pairingPanel.addView(stop, 3)
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@HostActivity).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24)
                phoneControls = LinearLayout(this@HostActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(pairingPanel); addView(status)
                    addView(authorize)
                    addView(button(R.string.host_open_shizuku) {
                        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (intent != null) startActivity(intent) else openGuide()
                    })
                    addView(button(R.string.host_setup_guide) { openGuide() })
                    addView(chosenApps)
                    addView(launcherStatus)
                    addView(notificationAccess)
                }
                addView(phoneControls)
                initializePhoneHid(this)
            })
        })
    }

    override fun onStart() { super.onStart(); bootstrap.observe(observer); bootstrap.refresh(); main.post(sessionTick) }
    override fun onResume() { super.onResume(); if (::bootstrap.isInitialized) bootstrap.refresh(); if (::notificationAccess.isInitialized) notificationAccess.open(); resumePhoneHid() }
    override fun onStop() { if (::notificationAccess.isInitialized) notificationAccess.close(); stopPhoneHid(); main.removeCallbacks(sessionTick); bootstrap.removeObserver(observer); super.onStop() }
    override fun onDestroy() { if (::notificationAccess.isInitialized) notificationAccess.close(); stopPhoneHid(); pairingPanel.close(); super.onDestroy() }

    private fun initializePhoneHid(container: LinearLayout) {
        phoneHid = PhoneHidPanel(this, PhoneHidRuntime.get(this)) { showPhoneHid(false) }.apply { visibility = View.GONE }
        phoneControls.addView(Button(this).apply {
            text = "HID key bindings"; isAllCaps = false
            setOnClickListener { showPhoneHid(true) }
        })
        container.addView(phoneHid)
    }

    private fun showPhoneHid(show: Boolean) {
        if (show) {
            phoneControls.visibility = View.GONE
            phoneHid.visibility = View.VISIBLE
            phoneHid.open()
        } else {
            phoneHid.close()
            phoneHid.visibility = View.GONE
            phoneControls.visibility = View.VISIBLE
        }
    }

    private fun resumePhoneHid() {
        if (::phoneHid.isInitialized && phoneHid.visibility == View.VISIBLE) phoneHid.open()
    }

    private fun stopPhoneHid() {
        if (::phoneHid.isInitialized) phoneHid.close()
    }

    @Deprecated("Phone HID settings use local Back")
    override fun onBackPressed() {
        if (::phoneHid.isInitialized && phoneHid.visibility == View.VISIBLE) showPhoneHid(false)
        else super.onBackPressed()
    }

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
