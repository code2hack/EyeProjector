package com.code2hack.eyeprojector.directhost.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Handler
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.scale.AppScalePreferences
import com.code2hack.eyeprojector.protocol.*

/** Actual combined owners; canonical bootstrap/IPC/render observations below are explicit host API models. */
private data class CrossFixture(val context: Context, val sessions: PhoneSessionController, val host: HostBootstrap,
    val projection: ProjectionContext)
private val manager = AppWidgetManager.manager
private var cases = 0
private var failures = 0
private fun drain() = Handler.drain()
private fun command(sessions: PhoneSessionController, operation: SessionControl.Operation) {
    PhoneSessionController::class.java.getDeclaredMethod("command", SessionControl.Request::class.java)
        .apply { isAccessible = true }.invoke(sessions, SessionControl.Request(operation, sessions.state().epoch))
    drain()
}
private fun fixture(name: String): CrossFixture {
    manager.beforeUpdate = null; manager.failUpdates = false; manager.failIds = emptySet()
    Activity.instances.toList().forEach { it.finish() }; Activity.instances.clear()
    PhoneWidgetUpdates.disabled(); drain()
    PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    Context.root = java.nio.file.Files.createTempDirectory("widget-scale-cross-").toFile()
    manager.ids = intArrayOf(41, 42); manager.updates.clear(); manager.updateCounts.clear(); Activity.launches.clear()
    HostBootstrap.current = HostBootstrap()
    val context = Context()
    val sessions = PhoneSessionController.get(context)
    drain()
    // Deliberately modeled canonical service observation, not a Shizuku/Binder or device availability result.
    val projection = ProjectionContext("cross-$name", "media-$name")
    sessions.bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STREAMING",
        sessionEpoch = projection.ownerEpoch, projectionToken = projection.projectionToken))
    drain()
    command(sessions, SessionControl.Operation.RENDERED)
    check(sessions.state().phase == SessionPhase.STREAMING)
    return CrossFixture(context, sessions, sessions.bootstrap, projection)
}
private fun launchResult(call: HostBootstrap.DisplayCall, status: ProjectionLaunchStatus = ProjectionLaunchStatus.STARTED) =
    ProjectionDisplayState(requestId = 71, generation = "modeled-${call.expected.projectionToken}", displayId = 47,
        requestedPackage = call.packageName, status = status,
        commandExit = if (status == ProjectionLaunchStatus.TIMED_OUT) 124 else 0,
        foregroundPackage = call.packageName, context = call.expected)
private fun crossCase(name: String, body: () -> Unit) {
    cases++
    try { body(); println("PASS $name") }
    catch (error: Throwable) { failures++; println("FAIL $name: $error"); error.printStackTrace() }
    finally {
        manager.beforeUpdate = null
        Activity.instances.toList().forEach { it.finish() }
        drain()
    }
}

fun main() {
    crossCase("C12-19 backend observation clears scale target before actual widget render reads") {
        val f = fixture("observer-order")
        val target = f.sessions.launchAppForScale("example.cross.old", f.projection) {}
        check(f.sessions.appScales.state()?.target == target)
        data class Sample(val owner: String, val selected: ProjectionContext?)
        val samples = mutableListOf<Sample>()
        // This hook is only the modeled AppWidgetManager boundary. Actual PhoneWidgetUpdates/Renderer invoke it.
        manager.beforeUpdate = { _, _ -> samples.add(Sample(f.sessions.state().epoch,
            f.sessions.appScales.state()?.target?.context)) }
        val replacement = f.projection.copy(projectionToken = "replacement-observer-media")
        f.host.publish(f.host.value.copy(projectionToken = replacement.projectionToken))
        drain(); manager.beforeUpdate = null
        check(samples.isNotEmpty() && samples.all { it.owner == f.projection.ownerEpoch && it.selected == null })
        val rendered = manager.updates.getValue(41).intents.getValue(R.id.phone_widget_panel)
        check(PhoneWidgetIntents.read(rendered.intent)!!.projection == replacement)
        check(f.sessions.appScales.state() == null && f.host.displayCalls.single().expected == f.projection)
    }
    crossCase("C12-19 widget Stop and media replacement discard old selected-app/profile completion") {
        for (stopFirst in listOf(true, false)) {
            val f = fixture(if (stopFirst) "stop-old-launch" else "replace-old-launch")
            val profiles = AppScalePreferences.open(f.context)
            check(profiles.write("example.cross.old", .75))
            var callbacks = 0
            var reply: ProjectionDisplayState? = null
            f.sessions.launchApp("example.cross.old", f.projection) { callbacks++; reply = it }
            val oldCall = f.host.displayCalls.single()
            if (stopFirst) {
                val action = PhoneWidgetIntents.pending(f.context, 41,
                    PhoneWidgetAction(PhoneWidgetCommand.STOP, f.sessions.state().epoch)).send()
                drain()
                check(action.finished && f.host.stops == 1 && f.sessions.state().phase == SessionPhase.STOPPING)
                // Pending teardown is modeled explicitly; no STOPPED/released observation is manufactured.
                f.host.publish(f.host.value.copy(backend = "STOPPING", projectionToken = "")); drain()
            } else {
                f.host.publish(f.host.value.copy(projectionToken = "replacement-old-launch")); drain()
                f.sessions.launchApp("example.cross.fresh", f.host.status().projectionContext) {}
            }
            val selectedAfterTransition = f.sessions.appScales.state()?.target
            check(if (stopFirst) selectedAfterTransition == null else selectedAfterTransition?.packageName == "example.cross.fresh")
            val published = mutableListOf<String?>()
            val observer = f.sessions.appScales.observe { published.add(it?.target?.packageName) }
            try {
                oldCall.complete(launchResult(oldCall)); drain()
                check(callbacks == 1 && reply == null)
                check(f.sessions.appScales.state()?.target == selectedAfterTransition)
                check(published.none { it == "example.cross.old" })
                check(f.host.displayCalls.none { it.kind == "display" } && f.host.scaleCalls.isEmpty())
                check(profiles.read("example.cross.old") == .75)
                if (stopFirst) check(f.host.stops == 1 && f.sessions.state().phase == SessionPhase.STOPPING)
            } finally { observer.close() }
        }
    }
    crossCase("C12-19 context-bound panel OFF stays independent of confirmed common launch/profile restore") {
        val f = fixture("panel-launch")
        val profiles = AppScalePreferences.open(f.context)
        check(profiles.write("example.cross.scaled", 1.5))
        val panelIntent = manager.updates.getValue(41).intents.getValue(R.id.phone_widget_panel)
        check(PhoneWidgetIntents.read(panelIntent.intent)!!.projection == f.projection)
        val action = panelIntent.send(); drain()
        check(action.finished && f.host.panelRequests.single().first == f.projection)
        var callbacks = 0
        var launchReply: ProjectionDisplayState? = null
        f.sessions.launchApp("example.cross.scaled", f.projection) { callbacks++; launchReply = it }
        val launch = f.host.displayCalls.single { it.kind == "launch" }
        // #12 restores an actually matching modeled foreground even when the modeled command timed out.
        launch.complete(launchResult(launch, ProjectionLaunchStatus.TIMED_OUT)); drain()
        check(callbacks == 1 && launchReply?.status == ProjectionLaunchStatus.TIMED_OUT)
        val display = f.host.displayCalls.single { it.kind == "display" }
        display.complete(ProjectionDisplayState(displayId = 47, foregroundPackage = "example.cross.scaled",
            status = ProjectionLaunchStatus.READY, context = f.projection)); drain()
        val scale = f.host.scaleCalls.single()
        check(scale.kind == "request" && scale.expected == f.projection && scale.value == 1.5)
        check(f.host.callOrder.map { it.first } == listOf("panelOff", "launch", "display", "requestScale"))
        check(f.host.callOrder.all { it.second == f.projection })
        scale.complete(ScaleState(72, 1.5, null, LayoutScale.target(1.5), ScaleStatus.PENDING, false)); drain()
        val selected = f.sessions.appScales.state()!!
        check(selected.target.packageName == "example.cross.scaled" && selected.requested == 1.5)
        check(selected.scale?.status == ScaleStatus.PENDING && selected.applied == null && !selected.canPinch)
        check(profiles.read("example.cross.scaled") == 1.5)
        val panelReply = PanelPowerState(73, PanelSubmission.SUBMITTED, PanelRestoration.PENDING,
            PanelObservation.UNVERIFIED, context = PanelContext(f.projection.ownerEpoch, f.projection.projectionToken))
        f.host.panelRequests.single().second(panelReply); drain()
        check(panelReply.observation == PanelObservation.UNVERIFIED)
        check(manager.updates.getValue(41).text.getValue(R.id.phone_widget_panel_status).toString()
            .startsWith(f.context.getString(R.string.phone_widget_panel_submitted)))
        check(f.sessions.appScales.state() == selected && f.host.stops == 0)
    }
    println("$cases cross-feature source cases, $failures failed; actual combined controller/widget/scale owners, modeled preferences/bootstrap/render/peer APIs; no Android/device/physical proof")
    check(failures == 0) { "$failures cross-feature source cases failed" }
}
