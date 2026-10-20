package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.os.Bundle
import com.code2hack.eyeprojector.directhost.apps.ChosenApp

/** Explicitly opened by the existing shell-owned session on its actual virtual display. */
class ProjectedHomeActivity : Activity() {
    private lateinit var home: ProjectedHomeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessions = PhoneSessionController.get(this)
        home = ProjectedHomeView(this).apply {
            onAppSelected = { app ->
                showStatus(getString(R.string.projected_home_opening, app.label))
                sessions.launchApp(app.packageName) { result ->
                    showStatus(launchFeedback(app, result))
                }
            }
        }
        setContentView(home)
    }

    override fun onResume() { super.onResume(); home.refresh() }

    private fun launchFeedback(app: ChosenApp, result: ProjectionDisplayState?): String = getString(when {
        result?.foregroundPackage == app.packageName -> R.string.projected_home_opened
        result?.status == ProjectionLaunchStatus.TIMED_OUT -> R.string.projected_home_timeout
        result?.status == ProjectionLaunchStatus.STARTED -> R.string.projected_home_unconfirmed
        else -> R.string.projected_home_unavailable
    }, app.label)
}
