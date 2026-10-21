package com.code2hack.eyeprojector.directhost.commonharness

import android.content.Context
import android.content.HarnessFiles
import android.content.Intent
import com.code2hack.eyeprojector.directhost.HostBootstrap
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectedHomeActivity
import com.code2hack.eyeprojector.directhost.ProjectedHomeView
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.ProjectionDisplayState
import com.code2hack.eyeprojector.directhost.apps.ChosenApp
import com.code2hack.eyeprojector.directhost.scale.AppScalePreferences
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.ScaleState
import com.code2hack.eyeprojector.protocol.ScaleStatus
import java.nio.file.Files

private class Fixture(initialTokenPublished: Boolean = true) : AutoCloseable {
    val context = ProjectionContext("owner-a", "media-a")
    private val directory = Files.createTempDirectory("eyeprojector-common-launch-").toFile()
    val android = Context(directory)
    val bootstrap = HostBootstrap(context, if (initialTokenPublished) context else ProjectionContext(context.ownerEpoch))
    val profiles get() = AppScalePreferences.open(android)
    val phone: PhoneSessionController
    init {
        HarnessFiles.directory = directory
        HarnessFiles.preferences.clear()
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        HostBootstrap.fixture = bootstrap
        phone = PhoneSessionController.get(android)
    }
    fun home(expected: ProjectionContext): Pair<ProjectedHomeActivity, ProjectedHomeView> {
        val activity = ProjectedHomeActivity().apply { setIntent(homeIntent(expected)); onCreate(null) }
        val view = ProjectedHomeActivity::class.java.getDeclaredField("home").apply { isAccessible = true }.get(activity) as ProjectedHomeView
        return activity to view
    }
    override fun close() {
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        directory.deleteRecursively()
    }
}

private fun homeIntent(expected: ProjectionContext) = Intent()
    .putExtra(ProjectionContext.OWNER_EXTRA, expected.ownerEpoch)
    .putExtra(ProjectionContext.TOKEN_EXTRA, expected.projectionToken)

private fun closeEnough(expected: Double, actual: Double?) {
    check(actual != null && kotlin.math.abs(expected - actual) < 1e-10) { "Expected $expected, got $actual" }
}

private fun scenario(name: String, initialTokenPublished: Boolean = true, action: (Fixture) -> Unit) {
    Fixture(initialTokenPublished).use { action(it) }
    println("PASS $name")
}

fun main() {
    scenario("actual Phone explicit and inferred overloads restore independent package profiles") { f ->
        check(f.profiles.write("phone.explicit", 1.5))
        check(f.profiles.write("phone.inferred", .75))
        val replies = mutableListOf<ProjectionDisplayState?>()
        f.phone.launchApp("phone.explicit", f.context) { replies.add(it) }
        check(f.bootstrap.pending.single().kind == "launch")
        f.bootstrap.finishAll()
        closeEnough(1.5, f.bootstrap.requests().single().value)
        check(f.phone.appScales.state()?.target?.packageName == "phone.explicit")
        f.phone.launchApp("phone.inferred") { replies.add(it) }
        check(f.bootstrap.pending.single().expected == f.context)
        f.bootstrap.finishAll()
        check(replies.size == 2 && replies.all { it != null && it.foregroundPackage == it.requestedPackage })
        check(f.bootstrap.requests().map { it.value } == listOf(1.5, .75))
        check(f.bootstrap.requests().map { it.foregroundAtDispatch } == listOf("phone.explicit", "phone.inferred"))
    }

    scenario("actual projected Home callback enters the same profile coordinator") { f ->
        check(f.profiles.write("glasses.selected", 1.25))
        val (_, home) = f.home(f.context)
        home.onAppSelected!!(ChosenApp("glasses.selected", "Selected"))
        check(f.phone.appScales.state()?.target?.packageName == "glasses.selected")
        f.bootstrap.finishAll()
        closeEnough(1.25, f.bootstrap.requests().single().value)
        check(f.bootstrap.requests().single().expected == f.context)
        check(home.statuses.last().startsWith("resource:2:"))
    }

    scenario("dispatched A pinch drains before same-session B launch across real Phone seam") { f ->
        check(f.profiles.write("app.b", .75))
        val a = f.phone.launchAppForScale("app.a", f.context) {}
        f.bootstrap.finishAll()
        check(f.phone.appScales.attach(a))
        f.phone.appScales.pinch(a, 1.5)
        f.bootstrap.finishUntilRequest()
        val old = f.bootstrap.pending.single()
        closeEnough(1.5, old.value)
        val replies = mutableListOf<ProjectionDisplayState?>()
        f.phone.launchApp("app.b", f.context) { replies.add(it) }
        check(f.bootstrap.pending.single() === old) { "B overlapped A's admitted scale operation" }
        f.bootstrap.finishOne()
        check(f.bootstrap.pending.single().packageName == "app.b")
        f.bootstrap.finishAll()
        check(replies.single()?.foregroundPackage == "app.b")
        check(f.bootstrap.requests().map { it.value } == listOf(1.0, 1.5, .75))
        closeEnough(1.0, f.profiles.read("app.a"))
        closeEnough(.75, f.profiles.read("app.b"))
    }

    scenario("replacement observation invalidates an outstanding old target and late payload") { f ->
        val old = f.phone.launchAppForScale("app.old", f.context) {}
        f.bootstrap.finishAll()
        check(f.phone.appScales.attach(old))
        f.phone.appScales.pinch(old, 1.5)
        f.bootstrap.finishUntilRequest()
        val late = f.bootstrap.pending.removeFirst()
        val replacement = f.context.copy(projectionToken = "media-b-on-same-backend")
        f.bootstrap.emit(replacement)
        check(f.phone.appScales.state() == null)
        f.phone.launchApp("app.new", replacement) {}
        check(f.bootstrap.pending.single().expected == replacement) { "New session waited for retired callback" }
        f.bootstrap.finishAll()
        val beforeLate = f.phone.appScales.state()
        // Deliberately deliver stale success after the new context is usable.
        late.complete(ScaleState(9, 1.5, Geometry(480, 640), null, ScaleStatus.PENDING, false))
        check(f.phone.appScales.state() == beforeLate)
        val count = f.bootstrap.history.size
        f.phone.appScales.pinch(old, 2.0)
        check(f.bootstrap.history.size == count)
        closeEnough(1.0, f.profiles.read("app.old"))
        check(f.phone.appScales.state()?.target?.context == replacement)
    }

    scenario("Home explicit token reaches actual Phone seam before first token poll", initialTokenPublished = false) { f ->
        check(f.profiles.write("home.early", 1.3))
        val (_, home) = f.home(f.context)
        home.onAppSelected!!(ChosenApp("home.early", "Early"))
        check(f.bootstrap.pending.single().expected == f.context)
        // The owner is already known while no token has yet been observed. This is
        // a lifecycle notification, not permission to bind the intent to another token.
        f.bootstrap.emit(ProjectionContext(f.context.ownerEpoch), "SESSION_STARTING", updateActual = false)
        check(f.phone.appScales.state()?.target?.context == f.context)
        f.bootstrap.finishAll()
        closeEnough(1.3, f.bootstrap.requests().single().value)
        check(home.statuses.last().startsWith("resource:2:"))
    }

    scenario("Stop while first launch is outstanding clears intention despite retained owner epoch") { f ->
        val replies = mutableListOf<ProjectionDisplayState?>()
        f.phone.launchApp("stop.pending", f.context) { replies.add(it) }
        val late = f.bootstrap.pending.removeFirst()
        f.phone.stop()
        check(f.bootstrap.stops == 1)
        check(f.phone.appScales.state() == null)
        late.complete(ProjectionDisplayState(requestedPackage = "stop.pending", foregroundPackage = "stop.pending", context = f.context))
        check(replies.size == 1 && replies.single() == null)
        check(f.bootstrap.pending.isEmpty() && f.bootstrap.requests().isEmpty())
    }

    scenario("foreground HOME change prevents saving or dispatching a stale app pinch") { f ->
        val target = f.phone.launchAppForScale("app.panel", f.context) {}
        f.bootstrap.finishAll()
        check(f.phone.appScales.attach(target))
        val before = f.bootstrap.requests().size
        f.bootstrap.foregroundPackage = "com.code2hack.eyeprojector.directhost"
        f.phone.appScales.pinch(target, 1.4)
        f.bootstrap.finishAll()
        check(f.bootstrap.requests().size == before)
        closeEnough(1.0, f.profiles.read("app.panel"))
        check(f.phone.appScales.state()?.canPinch != true)
    }

    scenario("closing drops undispatched pinch and reattaching uses actual admitted preference") { f ->
        val target = f.phone.launchAppForScale("app.close", f.context) {}
        f.bootstrap.finishAll()
        check(f.phone.appScales.attach(target))
        f.phone.appScales.poll(target)
        check(f.bootstrap.pending.isNotEmpty())
        f.phone.appScales.pinch(target, 1.25)
        val before = f.bootstrap.requests().size
        f.phone.appScales.detach(target)
        f.bootstrap.finishAll()
        check(f.bootstrap.requests().size == before)
        check(f.bootstrap.stops == 0 && f.phone.appScales.state()?.target == target)
        check(f.phone.appScales.attach(target))
        f.phone.appScales.pinch(target, 1.2)
        f.bootstrap.finishAll()
        closeEnough(1.2, f.bootstrap.requests().last().value)
        closeEnough(1.2, f.profiles.read("app.close"))
    }
    scenario("actual Home ignores old same-context feedback and callbacks from a previous Intent") { f ->
        val (activity, home) = f.home(f.context)
        val oldSelection = home.onAppSelected!!
        oldSelection(ChosenApp("a", "A"))
        val oldReply = f.bootstrap.pending.removeFirst()
        home.onAppSelected!!(ChosenApp("b", "B"))
        val openingB = home.statuses.toList()
        oldReply.complete(null)
        check(home.statuses == openingB) { "A's stale null overwrote B opening feedback" }
        f.bootstrap.finishAll()
        val openedB = home.statuses.toList()
        activity.onNewIntent(homeIntent(f.context))
        oldSelection(ChosenApp("old.intent", "Old Intent"))
        check(home.statuses == openedB && f.bootstrap.pending.isEmpty())
        home.onAppSelected!!(ChosenApp("new.intent", "New Intent"))
        f.bootstrap.finishAll()
        check(home.statuses.last().contains("New Intent"))
        val (_, recreated) = f.home(f.context)
        recreated.onAppSelected!!(ChosenApp("recreated", "Recreated"))
        f.bootstrap.finishAll()
        val recreatedFeedback = recreated.statuses.toList()
        oldSelection(ChosenApp("old.activity", "Old Activity"))
        check(recreated.statuses == recreatedFeedback)
    }
    runPhoneActivityScenarios()
    runScaleActivityScenarios()
    runP2Scenarios()
}
