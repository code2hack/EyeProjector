package com.code2hack.eyeprojector.directhost.commonharness

import android.content.Context
import android.content.HarnessFiles
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.HostActivity
import com.code2hack.eyeprojector.directhost.HostBootstrap
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.SessionController
import com.code2hack.eyeprojector.directhost.apps.ChosenApp
import com.code2hack.eyeprojector.directhost.apps.ChosenAppsPanel
import com.code2hack.eyeprojector.protocol.SessionPhase
import java.nio.file.Files

private class PhoneActivityFixture : AutoCloseable {
    val context = ProjectionContext("phone-owner", "phone-media")
    private val directory = Files.createTempDirectory("eyeprojector-phone-feedback-").toFile()
    val bootstrap = HostBootstrap(context)
    val phone: PhoneSessionController
    private val activities = mutableListOf<HostActivity>()
    init {
        HarnessFiles.directory = directory
        HarnessFiles.preferences.clear()
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        HostBootstrap.fixture = bootstrap
        phone = PhoneSessionController.get(Context(directory))
        // Actual restore deliberately reports CONNECTING until a real controller
        // RENDERED transition. Drive that pure semantic entry, never a phase double.
        check(phone.state().phase == SessionPhase.CONNECTING)
        val controller = PhoneSessionController::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }.get(phone) as SessionController
        check(controller.receiverRendered(context.ownerEpoch).phase == SessionPhase.STREAMING)
    }
    fun createActivity(): HostActivity {
        val activity = HostActivity().apply { onCreate(null); onStart() }
        activities.add(activity)
        val menu = field<ChosenAppsPanel>(activity, "chosenApps")
        check(menu.onAppSelected != null && menu.onAppScale != null) { "Actual Host observer did not bind connected callbacks" }
        check(field<ProjectionContext>(activity, "launchContext") == context)
        check(field<PhoneSessionController>(activity, "sessions") === phone)
        return activity
    }
    fun retire(activity: HostActivity) {
        activity.onStop(); activity.onDestroy(); activities.remove(activity)
    }
    override fun close() {
        activities.toList().forEach(::retire)
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        directory.deleteRecursively()
    }
}

private inline fun <reified T> field(activity: HostActivity, name: String): T =
    HostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

private fun feedback(activity: HostActivity): String = field<TextView>(activity, "launcherStatus").text.toString()

/** Invoke real Activity handlers; widget doubles never reproduce their feedback logic. */
private fun choose(activity: HostActivity, method: String, packageName: String, label: String, context: ProjectionContext) {
    HostActivity::class.java.getDeclaredMethod(method, ChosenApp::class.java, ProjectionContext::class.java)
        .apply { isAccessible = true }.invoke(activity, ChosenApp(packageName, label), context)
}

fun runPhoneActivityScenarios() {
    PhoneActivityFixture().use { f ->
        val activity = f.createActivity()
        choose(activity, "launchChosenApp", "phone.tap.a", "Tap A", f.context)
        val lateA = f.bootstrap.pending.removeFirst()
        check(feedback(activity) == "resource:1:Tap A")
        choose(activity, "scaleChosenApp", "phone.scale.b", "Scale B", f.context)
        val pendingB = feedback(activity)
        check(pendingB == "resource:1:Scale B")
        val targetB = f.phone.appScales.state()!!.target
        check(targetB.packageName == "phone.scale.b")
        val panelIntent = activity.startedActivities.single()
        check(panelIntent.getStringExtra("app-scale-intent") == targetB.id)
        check(panelIntent.getStringExtra("app-scale-package") == "phone.scale.b")
        lateA.complete(null)
        check(feedback(activity) == pendingB) { "Tap A's stale null overwrote Scale B feedback" }
        f.bootstrap.finishAll()
        check(feedback(activity) == "resource:2:Scale B")
        check(f.phone.appScales.state()?.target == targetB)
        check(f.bootstrap.stops == 0)
    }
    println("PASS actual Phone tap A -> Scale B retains B feedback when A returns stale null")

    PhoneActivityFixture().use { f ->
        val activity = f.createActivity()
        choose(activity, "scaleChosenApp", "phone.scale.a", "Scale A", f.context)
        val lateA = f.bootstrap.pending.removeFirst()
        choose(activity, "launchChosenApp", "phone.tap.b", "Tap B", f.context)
        val pendingB = feedback(activity)
        check(pendingB == "resource:1:Tap B")
        lateA.complete(null)
        check(feedback(activity) == pendingB) { "Scale A's stale null overwrote Tap B opening feedback" }
        f.bootstrap.finishAll()
        check(feedback(activity) == "resource:2:Tap B")
        check(f.phone.appScales.state()?.target?.packageName == "phone.tap.b")
        check(f.bootstrap.stops == 0)
    }
    println("PASS actual Phone Scale A -> tap B retains B feedback when A returns stale null")

    PhoneActivityFixture().use { f ->
        val first = f.createActivity()
        choose(first, "launchChosenApp", "phone.old.activity", "Old Activity", f.context)
        val lateOld = f.bootstrap.pending.removeFirst()
        val retainedTarget = f.phone.appScales.state()!!.target
        f.retire(first)
        val recreated = f.createActivity()
        check(f.phone.appScales.state()?.target == retainedTarget) { "Recreation lost the app-scoped target" }
        choose(recreated, "scaleChosenApp", "phone.new.activity", "Recreated", f.context)
        val newFeedback = feedback(recreated)
        check(newFeedback == "resource:1:Recreated")
        val newTarget = f.phone.appScales.state()!!.target
        lateOld.complete(null)
        check(feedback(recreated) == newFeedback) { "Retired Activity's callback changed recreated feedback" }
        f.bootstrap.finishAll()
        check(feedback(recreated) == "resource:2:Recreated")
        check(f.phone.appScales.state()?.target == newTarget)
        check(f.phone.state().phase == SessionPhase.STREAMING && f.bootstrap.stops == 0)
    }
    println("PASS actual Phone Activity recreation retains session owner and isolates old feedback")
}
