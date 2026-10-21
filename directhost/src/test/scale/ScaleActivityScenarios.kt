package com.code2hack.eyeprojector.directhost.commonharness

import android.content.Context
import android.content.HarnessFiles
import android.content.Intent
import com.code2hack.eyeprojector.directhost.HostBootstrap
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.SessionController
import com.code2hack.eyeprojector.directhost.scale.AppScaleActivity
import com.code2hack.eyeprojector.directhost.scale.AppScaleTarget
import com.code2hack.eyeprojector.directhost.scale.ScalePanelView
import com.code2hack.eyeprojector.protocol.SessionPhase
import java.nio.file.Files

private class ScaleActivityFixture : AutoCloseable {
    val context = ProjectionContext("panel-owner", "panel-media")
    private val directory = Files.createTempDirectory("eyeprojector-scale-activity-").toFile()
    val android = Context(directory)
    var bootstrap = HostBootstrap(context)
        private set
    lateinit var phone: PhoneSessionController
        private set
    private val panels = mutableListOf<AppScaleActivity>()
    init {
        HarnessFiles.directory = directory
        HarnessFiles.preferences.clear()
        bindNewPhoneOwner()
    }
    private fun bindNewPhoneOwner() {
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        HostBootstrap.fixture = bootstrap
        phone = PhoneSessionController.get(android)
        val controller = PhoneSessionController::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }.get(phone) as SessionController
        check(controller.receiverRendered(context.ownerEpoch).phase == SessionPhase.STREAMING)
    }
    fun losePhoneOwner() {
        check(panels.isEmpty()) { "Model owner loss only when no panel Activity is active" }
        // A new controlled facade retains the live projection identity, while the
        // new actual Phone owner has no in-memory app target. No OS process is killed.
        bootstrap = HostBootstrap(context)
        bindNewPhoneOwner()
    }
    fun choose(packageName: String = "panel.app"): AppScaleTarget {
        val target = phone.launchAppForScale(packageName, context) {}
        bootstrap.finishAll()
        return target
    }
    fun open(intent: Intent): Pair<AppScaleActivity, ScalePanelView> {
        val activity = AppScaleActivity().apply { setIntent(intent); onCreate(null); onStart() }
        panels.add(activity)
        val panel = AppScaleActivity::class.java.getDeclaredField("panel")
            .apply { isAccessible = true }.get(activity) as ScalePanelView
        return activity to panel
    }
    fun stop(activity: AppScaleActivity) {
        activity.onStop(); activity.onDestroy(); panels.remove(activity)
    }
    override fun close() {
        panels.toList().forEach(::stop)
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        directory.deleteRecursively()
    }
}

fun runScaleActivityScenarios() {
    ScaleActivityFixture().use { f ->
        val target = f.choose()
        val intent = AppScaleActivity.intent(f.android, target)
        check(intent.component == AppScaleActivity::class.java.name)
        val (oldActivity, oldPanel) = f.open(intent)
        f.bootstrap.finishAll()
        val (newActivity, newPanel) = f.open(intent)
        // New onStart intentionally precedes old onStop. The old Activity must not
        // detach the new Activity's owner or admit its own stale gesture callback.
        f.stop(oldActivity)
        val beforeOldGesture = f.bootstrap.history.size
        oldPanel.pinch(2.0)
        check(f.bootstrap.history.size == beforeOldGesture)
        newPanel.pinch(1.25)
        f.bootstrap.finishAll()
        check(f.bootstrap.requests().map { it.value } == listOf(1.0, 1.25))
        check(newPanel.latest?.target == target && newPanel.latest?.canPinch == true)
        check(f.phone.appScales.state()?.target == target)
        check(f.phone.state().phase == SessionPhase.STREAMING && f.bootstrap.stops == 0)
        check(!newActivity.isFinishing)
    }
    println("PASS actual Scale Activity recreation keeps new owner when old onStop arrives late")

    ScaleActivityFixture().use { f ->
        val target = f.choose()
        val (activity, panel) = f.open(AppScaleActivity.intent(f.android, target))
        f.bootstrap.finishAll()
        check(panel.latest?.target == target)
        panel.close()
        check(activity.finishCalls == 1 && activity.isFinishing)
        check(f.phone.appScales.state()?.target == target)
        check(f.phone.state().phase == SessionPhase.STREAMING && f.bootstrap.stops == 0)
        f.stop(activity) // Model the platform lifecycle after the actual finish callback.
        val beforeStaleGesture = f.bootstrap.history.size
        panel.pinch(1.5)
        check(f.bootstrap.history.size == beforeStaleGesture)
        check(f.phone.appScales.state()?.target == target && f.bootstrap.stops == 0)
    }
    println("PASS actual Scale close callback invokes finish and preserves projection after onStop")

    ScaleActivityFixture().use { f ->
        val retired = f.choose("panel.old")
        val retiredIntent = AppScaleActivity.intent(f.android, retired)
        val current = f.choose("panel.new")
        val before = f.bootstrap.history.size
        val (staleActivity, stalePanel) = f.open(retiredIntent)
        check(stalePanel.renders.isNotEmpty() && stalePanel.latest == null)
        stalePanel.pinch(1.5)
        check(f.bootstrap.history.size == before && f.bootstrap.pending.isEmpty())
        check(f.phone.appScales.state()?.target == current)
        check(staleActivity.startedActivities.isEmpty())
        f.stop(staleActivity)
        val wrongPackage = AppScaleActivity.intent(f.android, current).putExtra("app-scale-package", "different.package")
        val (_, mismatchedPanel) = f.open(wrongPackage)
        check(mismatchedPanel.renders.isNotEmpty() && mismatchedPanel.latest == null)
        check(f.bootstrap.history.size == before && f.bootstrap.pending.isEmpty())
        check(f.phone.appScales.state()?.target == current && f.bootstrap.stops == 0)
    }
    println("PASS actual Scale Activity rejects stale target and mismatched package without relaunch")

    ScaleActivityFixture().use { f ->
        val previous = f.choose()
        val retainedIntent = AppScaleActivity.intent(f.android, previous)
        f.losePhoneOwner()
        check(f.phone.appScales.state() == null)
        val (activity, panel) = f.open(retainedIntent)
        check(panel.renders.isNotEmpty() && panel.latest == null)
        panel.pinch(1.5)
        check(f.bootstrap.history.isEmpty() && f.bootstrap.pending.isEmpty())
        check(activity.startedActivities.isEmpty() && f.phone.appScales.state() == null)
        panel.close()
        check(activity.finishCalls == 1 && f.bootstrap.stops == 0)
        check(f.phone.state().phase == SessionPhase.STREAMING)
    }
    println("PASS actual Scale Activity with process-lost target stays unavailable without relaunch")
}
