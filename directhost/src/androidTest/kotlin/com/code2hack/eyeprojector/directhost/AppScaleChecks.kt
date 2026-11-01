package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.apps.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchor
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStorage
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStore
import com.code2hack.eyeprojector.directhost.notifications.AppNotificationPreferences
import com.code2hack.eyeprojector.directhost.scale.*
import com.code2hack.eyeprojector.protocol.*

/** UI-only fixtures; this does not claim a real backend pinch or continuing device video. */
object AppScaleChecks {
    private fun fixture(context: Context) = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("instrumentation_scale_$name", mode)
    }
    fun persistence(context: Context, write: Boolean) {
        val profiles = AppScalePreferences.open(fixture(context))
        if (write) { check(profiles.write("example.scale.a", 1.5)); check(profiles.write("example.scale.b", .75)) }
        else { check(profiles.read("example.scale.a") == 1.5); check(profiles.read("example.scale.b") == .75) }
    }
    fun run(driver: Instrumentation, context: Context) {
        val activity = driver.startActivitySync(Intent(context, FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var menu: ChosenAppsPanel
        lateinit var panel: ScalePanelView
        var factor = 1.0
        var closed = false
        val isolated = fixture(activity)
        val apps = ChosenApps.open(isolated)
        val notifications = AppNotificationPreferences.open(isolated)
        val scales = AppScalePreferences.open(isolated)
        val scaleFixture = ScaleUiFixture(scales)
        val anchorValues = mutableMapOf<String, AppAnchor>()
        val anchors = AppAnchorStore(object : AppAnchorStorage {
            override fun read(packageName: String) = anchorValues[packageName]
            override fun write(packageName: String, anchor: AppAnchor?) {
                if (anchor == null) anchorValues.remove(packageName) else anchorValues[packageName] = anchor
            }
        })
        val app = InstalledApp("example.scale.ui", "Scale Fixture")
        val catalog = object : AppCatalog {
            var available = true
            override fun launchableApps() = listOf(app)
            override fun find(packageName: String) = app.takeIf { available && it.packageName == packageName }
        }
        try {
            notifications.setEnabled(app.packageName, true)
            check(scales.write(app.packageName, 1.5))
            driver.runOnMainSync {
                apps.read().forEach { apps.remove(it.packageName) }; apps.add(app)
                menu = ChosenAppsPanel(isolated, apps, catalog, notifications, anchors)
                activity.setContentView(menu)
                menu.findViewWithTag<View>(app.packageName).performLongClick()
            }
            driver.waitForIdleSync()
            driver.runOnMainSync {
                // Current menu always renders inline scale controls; disconnected controls are disabled.
                check(!menuControl<EditText>(menu, "app-scale-value").isEnabled)
                check(!menuControl<SeekBar>(menu, "app-scale-slider").isEnabled)
                check(menuControl<TextView>(menu, "app-scale-feedback").text.toString().contains("Connect to adjust scale."))
                check(appMenu(menu).getButton(AlertDialog.BUTTON_NEGATIVE).text == activity.getString(R.string.chosen_apps_remove))
                check(notificationSwitch(menu).isChecked)
                notificationSwitch(menu).performClick()
            }
            driver.waitForIdleSync()
            check(!notifications.isEnabled(app.packageName))
            check(apps.read().single().packageName == app.packageName)
            check(scales.read(app.packageName) == 1.5 && scaleFixture.backend.launches.isEmpty())
            driver.runOnMainSync { appMenu(menu).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            driver.waitForIdleSync()
            check(apps.read().isEmpty())
            driver.runOnMainSync {
                apps.add(app); menu.refresh()
                menu.appScales = scaleFixture.coordinator
                menu.onAppScale = scaleFixture::select
                menu.findViewWithTag<View>(app.packageName).performLongClick()
            }
            driver.waitForIdleSync()
            driver.runOnMainSync {
                check(menuControl<EditText>(menu, "app-scale-value").isEnabled)
                check(menuControl<SeekBar>(menu, "app-scale-slider").isEnabled)
                check(appMenu(menu).getButton(AlertDialog.BUTTON_NEGATIVE).text == activity.getString(R.string.chosen_apps_remove))
                check(!notificationSwitch(menu).isChecked)
                notificationSwitch(menu).performClick()
            }
            driver.waitForIdleSync()
            check(notifications.isEnabled(app.packageName))
            check(scaleFixture.chosen == null) // Notification switch/opening the menu must not select/resize.
            check(scaleFixture.backend.launches.isEmpty() && scaleFixture.backend.requests.isEmpty() && scaleFixture.backend.notificationLaunches == 0)
            check(scales.read(app.packageName) == 1.5)
            check(apps.read().single().packageName == app.packageName)
            driver.runOnMainSync {
                val input = menuControl<EditText>(menu, "app-scale-value")
                input.setText("1.25")
                input.onEditorAction(EditorInfo.IME_ACTION_DONE) // Actual current inline commit listener.
                check(scaleFixture.chosen?.packageName == app.packageName)
                check(scaleFixture.backend.launches == listOf(app.packageName))
                check(scaleFixture.backend.requests.last() == 1.25)
                check(scaleFixture.coordinator.state()?.target?.packageName == app.packageName)
                check(scales.read(app.packageName) == 1.25)
                appMenu(menu).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            driver.waitForIdleSync()
            val callsBeforeReopen = scaleFixture.backend.launches.size to scaleFixture.backend.requests.size
            driver.runOnMainSync {
                menu.findViewWithTag<View>(app.packageName).performLongClick()
                check((scaleFixture.backend.launches.size to scaleFixture.backend.requests.size) == callsBeforeReopen)
                menu.onAppScale = null // A disconnected context dismisses an open Scale menu.
            }
            driver.waitForIdleSync()
            driver.runOnMainSync { check(currentMenu(menu)?.isShowing != true) }
            driver.runOnMainSync {
                menu.onAppScale = scaleFixture::select; catalog.available = false; menu.refresh()
                menu.findViewWithTag<View>(app.packageName).performLongClick()
                check(!menuControl<EditText>(menu, "app-scale-value").isEnabled)
                check(!menuControl<SeekBar>(menu, "app-scale-slider").isEnabled)
                check(notificationSwitch(menu).isChecked)
                check((scaleFixture.backend.launches.size to scaleFixture.backend.requests.size) == callsBeforeReopen)
                appMenu(menu).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            driver.waitForIdleSync(); check(apps.read().isEmpty())
            driver.runOnMainSync {
                panel = ScalePanelView(activity, { factor *= it }, { closed = true })
                activity.setContentView(panel)
                val target = AppScaleTarget("ui-fixture", app.packageName, ProjectionContext("owner", "token"))
                panel.render(AppScalePanelState(target, scale = ScaleState(9, 1.5, LayoutScale.BASELINE, LayoutScale.target(1.5), ScaleStatus.PENDING, false),
                    requested = 1.5, detail = "PENDING", canPinch = true))
                check(panel.findViewWithTag<TextView>("scale-applied").text == "1.00×")
                check(panel.findViewWithTag<TextView>("scale-feedback").text.toString().contains("observed"))
            }
            driver.waitForIdleSync()
            driver.runOnMainSync {
                pinch(panel.findViewWithTag("scale-gesture"))
                check(factor > 1.1)
                panel.render(null)
                val before = factor; pinch(panel.findViewWithTag("scale-gesture")); check(factor == before)
                panel.findViewWithTag<View>("scale-close").performClick(); check(closed)
                check(panel.findViewWithTag<TextView>("scale-applied").text == "—")
            }
            persistence(context, true); persistence(context, false)
        } finally { driver.runOnMainSync { activity.finish() } }
    }

    private fun pinch(view: View) {
        val start = SystemClock.uptimeMillis()
        val center = view.width / 2f
        val y = view.height / 2f
        fun event(action: Int, offset: Float, step: Long, count: Int = 2) {
            val properties = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = center + if (i == 0) -offset else offset; this.y = y; pressure = 1f; size = 1f
            } }
            MotionEvent.obtain(start, start + step, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 30f, 0, 1)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 30f, 10)
        for (i in 1..8) event(MotionEvent.ACTION_MOVE, 30f + i * 24f, 10 + i * 20L)
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 222f, 190)
        event(MotionEvent.ACTION_UP, 222f, 200, 1)
    }
    /** Own component's pinned private dialog field; actual widgets/listeners, no UiAutomation connection. */
    private fun currentMenu(menu: ChosenAppsPanel): AlertDialog? = ChosenAppsPanel::class.java
        .getDeclaredField("dialog").apply { isAccessible = true }.get(menu) as? AlertDialog
    private fun appMenu(menu: ChosenAppsPanel): AlertDialog = checkNotNull(currentMenu(menu)).also { check(it.isShowing) }
    private fun <T : View> menuControl(menu: ChosenAppsPanel, tag: String): T =
        checkNotNull(appMenu(menu).window?.decorView?.findViewWithTag<T>(tag))
    private fun notificationSwitch(menu: ChosenAppsPanel): Switch {
        val found = mutableListOf<Switch>()
        fun visit(view: View) {
            if (view is Switch && view.text == "Glasses notifications") found.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(appMenu(menu).window!!.decorView)
        return found.single()
    }

    // BEGIN APPSCALE_UI_BACKEND_FIXTURE
    /** Real scale coordinator; synchronous backend observations are UI-only test boundaries. */
    private class ScaleUiFixture(scales: AppScaleStore) {
        val context = ProjectionContext("scale-ui-fixture-owner", "scale-ui-fixture-target")
        val backend = ScaleUiBackend()
        val coordinator = AppScaleCoordinator(scales, backend)
        var chosen: ChosenApp? = null
            private set
        fun select(app: ChosenApp): AppScaleTarget {
            chosen = app
            return coordinator.launch(app.packageName, context) { observed ->
                check(observed?.context == context && observed.foregroundPackage == app.packageName)
            }
        }
    }
    private class ScaleUiBackend : AppScaleBackend {
        val launches = mutableListOf<String>()
        val requests = mutableListOf<Double>()
        var notificationLaunches = 0
            private set
        private var foreground: String? = null
        private var observed = LayoutScale.BASELINE
        private var requested = 1.0
        private var sequence = 0L
        override fun launchNotification(carrier: com.code2hack.eyeprojector.directhost.notifications.NotificationLaunchCarrier,
            complete: (com.code2hack.eyeprojector.directhost.notifications.NotificationDispatchOutcome) -> Unit) {
            notificationLaunches++
            error("Notification launch is outside this scale UI backend fixture")
        }
        override fun launch(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
            launches.add(packageName); foreground = packageName
            complete(ProjectionDisplayState(requestId = ++sequence, generation = "scale-ui-fixture",
                requestedPackage = packageName, foregroundPackage = packageName, status = ProjectionLaunchStatus.STARTED, context = expected))
        }
        override fun display(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
            complete(ProjectionDisplayState(context = expected, foregroundPackage = foreground))
        override fun request(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
            requests.add(value); requested = value; observed = checkNotNull(LayoutScale.target(value))
            complete(ScaleState(++sequence, value, observed, observed, ScaleStatus.READY, false))
        }
        override fun read(expected: ProjectionContext, complete: (ScaleState?) -> Unit) =
            complete(ScaleState(++sequence, requested, observed, LayoutScale.target(requested), ScaleStatus.OBSERVED, false))
    }
    // END APPSCALE_UI_BACKEND_FIXTURE
}
