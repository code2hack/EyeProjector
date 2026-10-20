package com.code2hack.eyeprojector.directhost

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import com.code2hack.eyeprojector.directhost.apps.*

/** Run only in a Manager-granted window with the current backend's recorded display ID. */
object ProjectedHomeChecks {
    fun run(driver: Instrumentation, context: Context, arguments: Bundle) {
        check(arguments.containsKey("projectionDisplayId"))
        val displayId = arguments.getInt("projectionDisplayId")
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val activity = driver.startActivitySync(Intent(context, ProjectedHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        val fixture = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("instrumentation_projected_$name", mode)
        }
        val store = ChosenApps.open(fixture)
        val catalog = object : AppCatalog {
            var visible = true
            override fun launchableApps() = listOf(InstalledApp("example.grid", "Grid"))
            override fun find(packageName: String) = launchableApps().firstOrNull { visible && it.packageName == packageName }
        }
        lateinit var home: ProjectedHomeView
        fun grid(view: View): GridLayout? {
            if (view is GridLayout) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) grid(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            driver.runOnMainSync {
                check(activity.display?.displayId == displayId)
                store.read().forEach { store.remove(it.packageName) }
                home = ProjectedHomeView(fixture, store, catalog)
                activity.setContentView(home)
            }
            driver.waitForIdleSync()
            driver.runOnMainSync { ChosenApps.open(fixture).add(InstalledApp("example.grid", "Grid")) }
            driver.waitForIdleSync()
            driver.runOnMainSync {
                check(grid(home)!!.childCount == 1) // No +, management row or taskbar in projected UI.
                var selected: ChosenApp? = null
                home.onAppSelected = { selected = it }
                home.findViewWithTag<View>("example.grid").performClick()
                check(selected == ChosenApp("example.grid", "Grid"))
                catalog.visible = false; home.refresh()
                check(!home.findViewWithTag<View>("example.grid").isClickable)
                check(home.findViewWithTag<View>("example.grid").contentDescription.toString().contains("Unavailable"))
                check(store.read().size == 1)
                catalog.visible = true; home.refresh()
                check(home.findViewWithTag<View>("example.grid").isClickable)
                ChosenApps.open(fixture).remove("example.grid")
            }
            driver.waitForIdleSync()
            driver.runOnMainSync { check(grid(home)!!.childCount == 0) }
            check(ChosenApps.open(context).read().none { it.packageName == "example.grid" })
        } finally { driver.runOnMainSync { activity.finish() } }
    }
}
