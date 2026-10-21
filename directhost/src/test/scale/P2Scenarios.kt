package com.code2hack.eyeprojector.directhost.commonharness

import android.content.Context
import android.content.HarnessFiles
import com.code2hack.eyeprojector.directhost.HostBootstrap
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.ProjectionDisplayState
import com.code2hack.eyeprojector.directhost.ProjectionLaunchStatus
import com.code2hack.eyeprojector.directhost.SessionController
import com.code2hack.eyeprojector.directhost.scale.AppScaleActivity
import com.code2hack.eyeprojector.directhost.scale.AppScalePreferences
import com.code2hack.eyeprojector.directhost.scale.AppScaleTarget
import com.code2hack.eyeprojector.directhost.scale.ScalePanelView
import com.code2hack.eyeprojector.protocol.SessionPhase
import java.nio.file.Files

/**
 * EP12-R1/R2 were independently found by Reviewer-EyeProjector-12 at df807790.
 * The two matching-foreground status cases and automatic-poll-held Close interval
 * below extend that review's focused-checks-02 reproductions into worker regression
 * assertions. The extra ordering/ownership cases are worker remediation coverage.
 * Controlled callbacks are request-return/observation evidence, never applied ACKs.
 */
private class P2Fixture : AutoCloseable {
    val context = ProjectionContext("p2-owner", "p2-media")
    private val directory = Files.createTempDirectory("eyeprojector-p2-").toFile()
    val android = Context(directory)
    val bootstrap = HostBootstrap(context)
    val profiles get() = AppScalePreferences.open(android)
    val phone: PhoneSessionController
    private val activities = mutableListOf<AppScaleActivity>()

    init {
        HarnessFiles.directory = directory
        HarnessFiles.preferences.clear()
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        HostBootstrap.fixture = bootstrap
        phone = PhoneSessionController.get(android)
        val controller = PhoneSessionController::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }.get(phone) as SessionController
        check(controller.receiverRendered(context.ownerEpoch).phase == SessionPhase.STREAMING)
    }

    fun choose(packageName: String = "p2.panel"): AppScaleTarget {
        val target = phone.launchAppForScale(packageName, context) {}
        bootstrap.finishAll()
        return target
    }

    fun open(target: AppScaleTarget): Pair<AppScaleActivity, ScalePanelView> {
        val activity = AppScaleActivity().apply {
            setIntent(AppScaleActivity.intent(android, target)); onCreate(null); onStart()
        }
        activities.add(activity)
        val panel = AppScaleActivity::class.java.getDeclaredField("panel")
            .apply { isAccessible = true }.get(activity) as ScalePanelView
        return activity to panel
    }

    fun stop(activity: AppScaleActivity) {
        activity.onStop(); activity.onDestroy(); activities.remove(activity)
    }

    fun assertProjectionLive(target: AppScaleTarget) {
        check(phone.appScales.state()?.target == target)
        check(phone.state().phase == SessionPhase.STREAMING)
        check(bootstrap.stops == 0) { "Panel Close must not stop or replace the session" }
    }

    override fun close() {
        activities.toList().forEach(::stop)
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        directory.deleteRecursively()
    }
}

private fun assertP2Number(expected: Double, actual: Double?) {
    check(actual != null && kotlin.math.abs(expected - actual) < 1e-10) { "Expected $expected, got $actual" }
}

/** Observe the inert Handler's scheduled queue; do not execute invented timer turns. */
private fun hasScheduledP2Tick(activity: AppScaleActivity): Boolean {
    val handler = AppScaleActivity::class.java.getDeclaredField("main").apply { isAccessible = true }.get(activity)
    val tick = AppScaleActivity::class.java.getDeclaredField("tick").apply { isAccessible = true }.get(activity)
    val callbacks = handler.javaClass.getDeclaredField("delayed").apply { isAccessible = true }.get(handler) as Set<*>
    return tick in callbacks
}

private fun matchingP2Launch(packageName: String, context: ProjectionContext, status: ProjectionLaunchStatus) =
    ProjectionDisplayState(displayId = 77, requestedPackage = packageName, foregroundPackage = packageName,
        context = context, status = status)

fun runP2Scenarios() {
    var passed = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, action: (P2Fixture) -> Unit) {
        var phase = "fixture"
        try {
            P2Fixture().use { fixture ->
                phase = "scenario"
                action(fixture)
                phase = "cleanup"
            }
            passed++
            println("PASS $name")
        } catch (error: Throwable) {
            failures.add(name)
            val outcome = if (phase == "scenario") "FAIL" else "ERROR"
            println("$outcome $name [$phase]: ${error.javaClass.simpleName}: ${error.message}")
            error.printStackTrace(System.out)
        }
    }

    for (status in listOf(ProjectionLaunchStatus.FAILED, ProjectionLaunchStatus.TIMED_OUT)) {
        scenario("EP12-R1 $status with matching identity and observed foreground restores saved request") { f ->
            val packageName = "p2.confirmed"
            check(f.profiles.write(packageName, 1.75))
            val replies = mutableListOf<ProjectionDisplayState?>()
            val target = f.phone.launchAppForScale(packageName, f.context) { replies.add(it) }
            val launch = f.bootstrap.pending.removeFirst()
            check(launch.kind == "launch" && launch.expected == f.context && launch.packageName == packageName)
            f.bootstrap.foregroundPackage = packageName
            val original = matchingP2Launch(packageName, f.context, status)
            launch.complete(original)
            f.bootstrap.finishAll()
            check(replies.size == 1 && replies.single() === original && replies.single()?.status == status) {
                "Restoration must preserve the original command result exactly once"
            }
            check(f.phone.appScales.state()?.launch === original)
            val request = f.bootstrap.requests().singleOrNull()
            check(request != null) { "Missing saved-profile request after $status with matching observed foreground" }
            assertP2Number(1.75, request.value)
            check(request.expected == f.context && request.foregroundAtDispatch == packageName)
            assertP2Number(1.75, f.profiles.read(packageName))
            // Controlled request return still carries the old geometry. Do not turn
            // restoration or PENDING into a claim that the wearer saw 1.75 applied.
            assertP2Number(1.0, f.phone.appScales.state()?.applied)
            check(f.phone.appScales.state()?.target == target)
        }

        for (mismatch in listOf("context", "requestedPackage", "foregroundPackage")) {
            scenario("EP12-R1 $status rejects mismatched $mismatch before profile dispatch") { f ->
                val packageName = "p2.mismatch"
                check(f.profiles.write(packageName, 1.75))
                val replies = mutableListOf<ProjectionDisplayState?>()
                f.phone.launchAppForScale(packageName, f.context) { replies.add(it) }
                val launch = f.bootstrap.pending.removeFirst()
                f.bootstrap.foregroundPackage = packageName
                val matching = matchingP2Launch(packageName, f.context, status)
                val original = when (mismatch) {
                    "context" -> matching.copy(context = f.context.copy(projectionToken = "old-media"))
                    "requestedPackage" -> matching.copy(requestedPackage = "different.request")
                    else -> matching.copy(foregroundPackage = "different.foreground")
                }
                launch.complete(original)
                f.bootstrap.finishAll()
                check(replies.size == 1 && replies.single() === original)
                check(f.bootstrap.requests().isEmpty())
                check(f.bootstrap.history.none { it.kind == "display" })
                check(f.phone.appScales.state()?.detail == "LAUNCH_UNCONFIRMED")
                assertP2Number(1.75, f.profiles.read(packageName))
            }
        }

        scenario("EP12-R1 $status still checks fresh foreground before saved-profile dispatch") { f ->
            val packageName = "p2.foreground"
            check(f.profiles.write(packageName, 1.75))
            val replies = mutableListOf<ProjectionDisplayState?>()
            f.phone.launchAppForScale(packageName, f.context) { replies.add(it) }
            val launch = f.bootstrap.pending.removeFirst()
            f.bootstrap.foregroundPackage = packageName
            val original = matchingP2Launch(packageName, f.context, status)
            launch.complete(original)
            check(f.bootstrap.pending.singleOrNull()?.kind == "display") { "Missing fresh pre-request foreground query" }
            f.bootstrap.foregroundPackage = "other.app"
            f.bootstrap.finishAll()
            check(replies.size == 1 && replies.single() === original)
            check(f.bootstrap.requests().isEmpty())
            check(f.phone.appScales.state()?.detail == "TARGET_CHANGED")
            assertP2Number(1.75, f.profiles.read(packageName))
        }
    }

    scenario("EP12-R1 late retired launch cannot restore or replace a fresh-context target") { f ->
        check(f.profiles.write("p2.old", 1.75))
        val oldReplies = mutableListOf<ProjectionDisplayState?>()
        f.phone.launchAppForScale("p2.old", f.context) { oldReplies.add(it) }
        val late = f.bootstrap.pending.removeFirst()
        val fresh = f.context.copy(projectionToken = "p2-fresh-media")
        f.bootstrap.emit(fresh)
        val target = f.phone.launchAppForScale("p2.fresh", fresh) {}
        f.bootstrap.finishAll()
        val beforeLate = f.phone.appScales.state()
        late.complete(matchingP2Launch("p2.old", f.context, ProjectionLaunchStatus.FAILED))
        check(oldReplies.size == 1 && oldReplies.single() == null)
        check(f.phone.appScales.state() == beforeLate && beforeLate?.target == target)
        check(f.bootstrap.pending.isEmpty())
        check(f.bootstrap.requests().all { it.expected == fresh })
        assertP2Number(1.75, f.profiles.read("p2.old"))
    }

    scenario("EP12-R2 Close drops queued pinch before automatic-poll callback and before onStop") { f ->
        val target = f.choose()
        val (activity, panel) = f.open(target)
        // Actual onStart posts the poll immediately. Its display callback is held;
        // the pinch has not reached any resize boundary when Close is invoked.
        check(f.bootstrap.pending.single().kind == "display")
        check(hasScheduledP2Tick(activity))
        panel.pinch(1.25)
        val requestsBeforeClose = f.bootstrap.requests().size
        panel.close()
        check(activity.finishCalls == 1 && activity.isFinishing)
        val rendersAfterClose = panel.renders.size
        // This is precisely the independent review interval: callback delivery
        // before onStop, never a modeled cancellation of an already-sent request.
        f.bootstrap.finishAll()
        check(f.bootstrap.requests().size == requestsBeforeClose) { "An unsent pinch dispatched after Close but before onStop" }
        assertP2Number(1.0, f.profiles.read(target.packageName))
        check(panel.renders.size == rendersAfterClose) { "Closed Activity kept its state subscription" }
        check(!hasScheduledP2Tick(activity)) { "Closed Activity kept its next poll tick" }
        f.assertProjectionLive(target)
        f.stop(activity) // Idempotent lifecycle fallback after the interval above.
        val beforeGesture = f.bootstrap.history.size
        panel.pinch(2.0)
        check(f.bootstrap.history.size == beforeGesture)
        f.assertProjectionLive(target)
    }

    scenario("EP12-R2 Close during dequeued pinch foreground query prevents its unsent resize") { f ->
        val target = f.choose()
        val (activity, panel) = f.open(target)
        f.bootstrap.finishAll()
        panel.pinch(1.5)
        // The pinch has left the queue but still only owns a foreground query.
        check(f.bootstrap.pending.single().kind == "display")
        val requestsBeforeClose = f.bootstrap.requests().size
        panel.close()
        val rendersAfterClose = panel.renders.size
        f.bootstrap.finishAll()
        check(f.bootstrap.requests().size == requestsBeforeClose) { "Foreground query admitted a resize after its attachment closed" }
        assertP2Number(1.0, f.profiles.read(target.packageName))
        check(panel.renders.size == rendersAfterClose && !hasScheduledP2Tick(activity))
        f.assertProjectionLive(target)
        f.stop(activity)
    }

    scenario("EP12-R2 Close retains already-dispatched request return but removes later queued pinch") { f ->
        val target = f.choose()
        val (activity, panel) = f.open(target)
        f.bootstrap.finishAll()
        panel.pinch(1.25)
        f.bootstrap.finishUntilRequest()
        val alreadyDispatched = f.bootstrap.pending.single()
        check(alreadyDispatched.kind == "request")
        assertP2Number(1.25, alreadyDispatched.value)
        panel.pinch(1.2) // Later intent would request 1.5, but has not dispatched.
        panel.close()
        val rendersAfterClose = panel.renders.size
        f.bootstrap.finishAll()
        check(f.bootstrap.requests().map { it.value } == listOf(1.0, 1.25)) { "Close admitted a later queued pinch or pretended to undo the sent request" }
        assertP2Number(1.25, f.profiles.read(target.packageName))
        assertP2Number(1.25, f.phone.appScales.state()?.requested)
        assertP2Number(1.0, f.phone.appScales.state()?.applied)
        check(panel.renders.size == rendersAfterClose && !hasScheduledP2Tick(activity))
        f.assertProjectionLive(target)
        f.stop(activity)
    }

    scenario("EP12-R2 old Activity Close and late onStop preserve recreated same-app attachment") { f ->
        val target = f.choose()
        val (oldActivity, oldPanel) = f.open(target)
        f.bootstrap.finishAll()
        val (newActivity, newPanel) = f.open(target)
        check(f.bootstrap.pending.single().kind == "display")
        oldPanel.close()
        check(oldActivity.finishCalls == 1 && !newActivity.isFinishing)
        val oldRendersAfterClose = oldPanel.renders.size
        newPanel.pinch(1.25)
        f.bootstrap.finishAll()
        assertP2Number(1.25, f.bootstrap.requests().last().value)
        assertP2Number(1.25, f.profiles.read(target.packageName))
        check(oldPanel.renders.size == oldRendersAfterClose)
        check(!hasScheduledP2Tick(oldActivity) && hasScheduledP2Tick(newActivity))
        f.stop(oldActivity)
        val beforeOldGesture = f.bootstrap.history.size
        oldPanel.pinch(2.0)
        check(f.bootstrap.history.size == beforeOldGesture)
        newPanel.pinch(1.2)
        f.bootstrap.finishAll()
        assertP2Number(1.5, f.bootstrap.requests().last().value)
        check(newPanel.latest?.target == target && newPanel.latest?.canPinch == true)
        f.assertProjectionLive(target)
    }

    println("P2 RESULT passed=$passed failed=${failures.size}")
    check(failures.isEmpty()) { "P2 regression failures: ${failures.joinToString()}" }
}

fun main(args: Array<String>) {
    check(args.isEmpty()) { "Focused P2 entrypoint accepts no arguments" }
    runP2Scenarios()
}
