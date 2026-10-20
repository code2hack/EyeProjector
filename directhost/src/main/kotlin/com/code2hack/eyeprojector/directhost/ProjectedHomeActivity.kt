package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.code2hack.eyeprojector.directhost.apps.ChosenApp

/** Explicitly opened by the existing shell-owned session on its actual virtual display. */
class ProjectedHomeActivity : Activity() {
    private lateinit var home: ProjectedHomeView
    private lateinit var sessions: PhoneSessionController
    private var homeContext = ProjectionContext()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = PhoneSessionController.get(this)
        home = ProjectedHomeView(this)
        bindIntent(intent)
        setContentView(home)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindIntent(intent)
    }

    private fun bindIntent(intent: Intent) {
        val expected = ProjectionContext(intent.getStringExtra(ProjectionContext.OWNER_EXTRA).orEmpty(),
            intent.getStringExtra(ProjectionContext.TOKEN_EXTRA).orEmpty())
        homeContext = expected
        home.onAppSelected = { app ->
            if (homeContext == expected) home.showStatus(getString(R.string.projected_home_opening, app.label))
            sessions.launchApp(app.packageName, expected) { result ->
                if (homeContext == expected) home.showStatus(launchFeedback(app, result))
            }
        }
    }

    override fun onResume() { super.onResume(); home.refresh() }

    private fun launchFeedback(app: ChosenApp, result: ProjectionDisplayState?): String = getString(when {
        result?.foregroundPackage == app.packageName -> R.string.projected_home_opened
        result?.status == ProjectionLaunchStatus.TIMED_OUT -> R.string.projected_home_timeout
        result?.status == ProjectionLaunchStatus.STARTED -> R.string.projected_home_unconfirmed
        else -> R.string.projected_home_unavailable
    }, app.label)
}
