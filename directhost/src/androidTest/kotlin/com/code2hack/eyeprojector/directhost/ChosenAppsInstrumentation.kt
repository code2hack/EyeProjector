package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.GridLayout
import com.code2hack.eyeprojector.directhost.apps.*

/** Platform-only Phone UI driver. Selection fixtures use a separate test-only private preference file. */
class ChosenAppsInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    private val fixtureContext by lazy { fixturePreferences(targetContext) }

    private fun fixturePreferences(base: Context) =
        object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("instrumentation_fixture_$name", mode)
        }

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            when (arguments.getString("operation", "ui")) {
                "ui" -> testUi()
                "projected-home" -> ProjectedHomeChecks.run(this, targetContext, arguments)
                "persist-write" -> {
                    val store = ChosenApps.open(fixtureContext)
                    store.read().forEach { store.remove(it.packageName) }
                    store.add(InstalledApp("example.restart.one", "Restart One"))
                    store.add(InstalledApp("example.restart.two", "Restart Two"))
                    // Synchronous barrier for the process-death test, on the fixture preference file only.
                    check(fixtureContext.getSharedPreferences("chosen_projection_apps", 0).edit().commit())
                }
                "persist-read" -> {
                    check(ChosenApps.open(fixtureContext).read() == listOf(
                        ChosenApp("example.restart.one", "Restart One"), ChosenApp("example.restart.two", "Restart Two"),
                    ))
                }
                else -> error("Unknown test operation")
            }
            result.putString("result", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", "FAIL")
            result.putString("failure", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun testUi() {
        val store = ChosenApps.open(fixtureContext)
        store.read().forEach { store.remove(it.packageName) }
        val catalog = object : AppCatalog {
            var available = listOf(InstalledApp("example.ui.alpha", "Alpha"), InstalledApp("example.ui.beta", "Beta"))
            var invisiblePackages = emptySet<String>()
            override fun launchableApps() = available.filter { it.packageName !in invisiblePackages }
            override fun find(packageName: String) = available.firstOrNull {
                it.packageName == packageName && it.packageName !in invisiblePackages
            }
        }
        val installed = InstalledAppCatalog(targetContext.packageManager)
        val actual = installed.launchableApps()
        check(actual.map { it.packageName }.distinct().size == actual.size)
        check(installed.find(targetContext.packageName)?.launchable == true)
        check(installed.find("example.not.installed.eyeprojector") == null)

        val activity = startActivitySync(Intent(targetContext, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var panel: ChosenAppsPanel
        fun replacePanel() {
            runOnMainSync {
                // Preserve the Activity theme/window, with the same preference namespace for store and observer.
                val panelContext = fixturePreferences(activity)
                panel = ChosenAppsPanel(panelContext, ChosenApps.open(panelContext), catalog)
                activity.setContentView(panel)
            }
            waitForIdleSync()
        }
        try {
            replacePanel()
            val otherConsumer = ChosenApps.open(fixtureContext)
            runOnMainSync { otherConsumer.add(InstalledApp("example.ui.alpha", "Alpha")) }
            waitForIdleSync()
            runOnMainSync {
                // No refresh, recreation or callback setter between this external write and assertion.
                check((0 until grid(panel).childCount).map { grid(panel).getChildAt(it).tag } ==
                    listOf(ChosenAppsPanel.ADD_TAG, "example.ui.alpha"))
                otherConsumer.remove("example.ui.alpha")
            }
            waitForIdleSync()
            runOnMainSync {
                check(grid(panel).childCount == 1)
                check(grid(panel).getChildAt(0).tag == ChosenAppsPanel.ADD_TAG)
                panel.findViewWithTag<View>(ChosenAppsPanel.ADD_TAG).performClick()
            }
            clickText("Alpha\nexample.ui.alpha")
            waitForIdleSync()
            check(store.read().map { it.packageName } == listOf("example.ui.alpha"))
            runOnMainSync { panel.findViewWithTag<View>(ChosenAppsPanel.ADD_TAG).performClick() }
            clickText("Beta\nexample.ui.beta")
            waitForIdleSync()
            check(store.read().size == 2)
            // Rebuild the consumer from persisted state; + stays first and order is retained.
            replacePanel()
            runOnMainSync {
                check((0 until grid(panel).childCount).map { grid(panel).getChildAt(it).tag } ==
                    listOf(ChosenAppsPanel.ADD_TAG, "example.ui.alpha", "example.ui.beta"))
                var selected: ChosenApp? = null
                panel.onAppSelected = { selected = it }
                panel.findViewWithTag<View>("example.ui.alpha").performClick()
                check(selected == ChosenApp("example.ui.alpha", "Alpha"))
                catalog.available = listOf(InstalledApp("example.ui.beta", "Beta", false))
                panel.refresh()
                check(panel.findViewWithTag<View>("example.ui.alpha").contentDescription.toString().contains("Unavailable"))
                check(panel.findViewWithTag<View>("example.ui.beta").contentDescription.toString().contains("Unavailable"))
                check(!panel.findViewWithTag<View>("example.ui.alpha").isClickable)
                check(!panel.findViewWithTag<View>("example.ui.beta").isClickable)
                catalog.available = listOf(InstalledApp("example.ui.alpha", "Alpha"), InstalledApp("example.ui.beta", "Beta"))
                panel.refresh()
                check(panel.findViewWithTag<View>("example.ui.alpha").contentDescription == "Alpha")
                check(panel.findViewWithTag<View>("example.ui.beta").contentDescription == "Beta")
                catalog.invisiblePackages = setOf("example.ui.alpha")
                panel.refresh()
                check(catalog.available.size == 2) // Installed but hidden by the catalog lookup.
                check(panel.findViewWithTag<View>("example.ui.alpha").contentDescription.toString().contains("Unavailable"))
                check(store.read().map { it.packageName } == listOf("example.ui.alpha", "example.ui.beta"))
                panel.findViewWithTag<View>("example.ui.alpha").performLongClick()
            }
            clickText("Remove")
            waitForIdleSync()
            check(store.read().map { it.packageName } == listOf("example.ui.beta"))
            check(catalog.available.size == 2)
            runOnMainSync { check(grid(panel).getChildAt(0).tag == ChosenAppsPanel.ADD_TAG) }
            // These fake selections never enter the Phone host's real selection or touch real apps.
            check(ChosenApps.open(targetContext).read().none { it.packageName.startsWith("example.ui.") })
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun grid(view: View): GridLayout {
        if (view is GridLayout) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is GridLayout) return child
        }
        error("Missing chosen-app grid")
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val nodes = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (node in nodes) {
                if (node.text?.toString() != text) continue
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(50)
        }
        error("No clickable UI item: $text")
    }
}
