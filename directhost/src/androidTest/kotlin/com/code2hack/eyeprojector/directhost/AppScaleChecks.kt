package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.apps.*
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
        var chosen: ChosenApp? = null
        var factor = 1.0
        var closed = false
        val isolated = fixture(activity)
        val apps = ChosenApps.open(isolated)
        val notifications = AppNotificationPreferences.open(isolated)
        val scales = AppScalePreferences.open(isolated)
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
                menu = ChosenAppsPanel(isolated, apps, catalog)
                activity.setContentView(menu)
                menu.findViewWithTag<View>(app.packageName).performLongClick()
            }
            driver.waitForIdleSync()
            check(!hasText(driver, "Scale")) // Disconnected: Remove and notifications.
            check(hasText(driver, "Remove"))
            click(driver, "Glasses notifications: On")
            check(!notifications.isEnabled(app.packageName))
            check(apps.read().single().packageName == app.packageName)
            driver.runOnMainSync { menu.findViewWithTag<View>(app.packageName).performLongClick() }
            driver.waitForIdleSync()
            click(driver, "Remove")
            check(apps.read().isEmpty())
            driver.runOnMainSync {
                apps.add(app); menu.refresh()
                menu.onAppScale = { chosen = it }
                menu.findViewWithTag<View>(app.packageName).performLongClick()
            }
            driver.waitForIdleSync()
            check(hasText(driver, "Remove") && hasText(driver, "Scale"))
            click(driver, "Glasses notifications: Off")
            check(notifications.isEnabled(app.packageName))
            check(chosen == null) // The notification row must not dispatch Scale.
            check(scales.read(app.packageName) == 1.5)
            check(apps.read().single().packageName == app.packageName)
            driver.runOnMainSync { menu.findViewWithTag<View>(app.packageName).performLongClick() }
            driver.waitForIdleSync(); click(driver, "Scale")
            driver.runOnMainSync {
                check(chosen?.packageName == app.packageName)
                menu.findViewWithTag<View>(app.packageName).performLongClick()
                menu.onAppScale = null // A disconnected context dismisses an open Scale menu.
            }
            driver.waitForIdleSync(); check(!hasText(driver, "Scale"))
            driver.runOnMainSync {
                menu.onAppScale = { chosen = it }; catalog.available = false; menu.refresh()
                menu.findViewWithTag<View>(app.packageName).performLongClick()
            }
            driver.waitForIdleSync(); check(!hasText(driver, "Scale"))
            check(hasText(driver, "Glasses notifications: On")); click(driver, "Remove")
            driver.runOnMainSync {
                panel = ScalePanelView(activity, { factor *= it }, { closed = true })
                activity.setContentView(panel)
                val target = AppScaleTarget("ui-fixture", app.packageName, ProjectionContext("owner", "token"))
                panel.render(AppScalePanelState(target, scale = ScaleState(9, 2.0, Geometry(480, 640), Geometry(240, 320), ScaleStatus.PENDING, false),
                    requested = 2.0, detail = "PENDING", canPinch = true))
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
    private fun hasText(driver: Instrumentation, text: String): Boolean = driver.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(text).orEmpty().any { it.text?.toString() == text }
    private fun click(driver: Instrumentation, text: String) {
        val nodes = driver.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
        for (node in nodes) {
            if (node.text?.toString() != text) continue
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { driver.waitForIdleSync(); return }
        }
        error("Missing UI action: $text")
    }
}
