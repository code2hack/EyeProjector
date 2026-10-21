package com.code2hack.eyeprojector.directhost.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.protocol.*

private var count = 0
private fun scenario(name: String, body: () -> Unit) { body(); count++; println("PASS $count $name") }
fun main() {
    val context = Context()
    val manager = AppWidgetManager.manager
    val provider = PhoneWidgetProvider()
    val controller = PhoneSessionController.get(context)
    val host = controller.bootstrap
    fun drain() = Handler.drain()
    fun primary(id: Int = 41) = manager.updates.getValue(id).intents.getValue(R.id.phone_widget_primary)
    fun stateText(id: Int = 41) = manager.updates.getValue(id).text.getValue(R.id.phone_widget_status).toString()
    fun command(op: SessionControl.Operation, epoch: String = controller.state().epoch): SessionState {
        val method = PhoneSessionController::class.java.getDeclaredMethod("command", SessionControl.Request::class.java)
        method.isAccessible = true
        return method.invoke(controller, SessionControl.Request(op, epoch)) as SessionState
    }
    fun backend(backend: String, epoch: String, token: String = "") {
        host.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, backend, sessionEpoch = epoch, projectionToken = token)); drain()
    }
    scenario("provider initial render is UNKNOWN until existing backend observation") {
        provider.onUpdate(context, manager, manager.ids)
        check(stateText().contains("Checking")); check(PhoneWidgetIntents.read(primary().intent)?.command == PhoneWidgetCommand.STOP)
        drain(); check(stateText().contains("Checking"))
        host.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STOPPED")); drain()
        check(stateText().contains("inactive"))
    }
    scenario("immutable explicit tokens separate widget IDs and operation/epoch/context") {
        val old = primary()
        check(old.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        check(old.intent.type == PhoneWidgetActionActivity::class.java)
        check(old !== primary(42))
        val action = PhoneWidgetIntents.read(old.intent)!!
        check(PhoneWidgetIntents.pending(context, 41, action.copy(epoch = "other")) !== old)
        check(PhoneWidgetIntents.pending(context, 41, action.copy(command = PhoneWidgetCommand.STOP)) !== old)
        check(PhoneWidgetIntents.pending(context, 41, action.copy(projection = ProjectionContext("a", "b"))) !== old)
        check(PhoneWidgetIntents.read(old.intent) == action)
    }
    scenario("real Activity entry invokes existing Start once and updates all widget instances") {
        val old = primary(); val entry = old.send(); check(entry.finished); drain()
        check(host.starts.size == 1); check(stateText().contains("Starting")); check(stateText(42) == stateText())
        old.send(); drain(); check(host.starts.size == 1 && host.stops == 0)
        check(Activity.launches.last().type == HostActivity::class.java)
    }
    val owner = controller.state().epoch
    scenario("Receiver connecting/rendered changes publish without bootstrap phase change") {
        backend("READY", owner)
        command(SessionControl.Operation.CONNECTING); drain(); check(stateText().contains("Connecting"))
        command(SessionControl.Operation.RENDERED); drain(); check(stateText().contains("Projecting"))
    }
    scenario("state listeners execute on main outside command call and one failing listener is isolated") {
        var calls = 0
        val bad: () -> Unit = { error("fixture observer failure") }
        val good: () -> Unit = { check(Looper.myLooper() == Looper.getMainLooper()); controller.state(); calls++ }
        controller.observeState(bad); controller.observeState(good); drain(); val before = calls
        Looper.onMain = false
        command(SessionControl.Operation.STATUS)
        check(calls == before)
        Looper.onMain = true; drain(); check(calls > before)
        controller.removeStateObserver(bad); controller.removeStateObserver(good)
    }
    scenario("panel Activity sends original context and displays submission as unverified") {
        backend("STREAMING", owner, "projection-one")
        command(SessionControl.Operation.RENDERED); drain()
        check(manager.updates.getValue(41).enabled[R.id.phone_widget_panel] == true)
        manager.updates.getValue(41).intents.getValue(R.id.phone_widget_panel).send(); drain()
        check(host.panelRequests.single().first == ProjectionContext(owner, "projection-one"))
        val request = host.panelRequests.single()
        request.second(PanelPowerState(7, PanelSubmission.SUBMITTED, PanelRestoration.PENDING,
            sessionActive = true, context = PanelContext(owner, "projection-one")))
        drain()
        val text = manager.updates.getValue(41).text.getValue(R.id.phone_widget_panel_status).toString()
        check(text.contains("submitted") && text.contains("unverified") && text.contains("pending"))
        host.queries.firstOrNull()?.invoke(null); drain()
        check(manager.updates.getValue(41).text.getValue(R.id.phone_widget_panel_status).toString().contains("submitted"))
    }
    scenario("app Stop publishes STOPPING and late panel reply cannot change new context") {
        controller.stop(); drain(); check(stateText().contains("Stopping"))
        check(manager.updates.getValue(41).enabled[R.id.phone_widget_panel] == false)
        backend("STOPPED", owner); check(stateText().contains("inactive"))
        val oldOff = host.panelRequests.single()
        oldOff.second(PanelPowerState(7, PanelSubmission.SUBMITTED, context = PanelContext(owner, "projection-one")))
        drain(); check(!manager.updates.getValue(41).text.getValue(R.id.phone_widget_panel_status).toString().contains("submitted"))
    }
    scenario("pairing change with unchanged backend routes existing setup and does not Start") {
        controller.store.paired = false; controller.pairing.changed(); drain()
        check(stateText().contains("setup"))
        val before = host.starts.size
        primary().send(); drain()
        check(host.starts.size == before && Activity.launches.last().type == HostActivity::class.java)
        controller.store.paired = true; controller.pairing.changed(); drain()
    }
    scenario("Shizuku missing route does not Start") {
        host.publish(host.value.copy(setup = HostBootstrap.Setup.MISSING)); drain()
        val before = host.starts.size; primary().send(); drain()
        check(host.starts.size == before && Activity.launches.last().type == HostActivity::class.java)
        host.publish(host.value.copy(setup = HostBootstrap.Setup.AVAILABLE)); drain()
    }
    scenario("Receiver failure updates every instance and never claims release") {
        primary().send(); drain(); val next = controller.state().epoch
        backend("READY", next)
        command(SessionControl.Operation.FAILED); drain()
        check(stateText().contains("failed") && stateText().contains("not yet confirmed"))
        check(stateText(42) == stateText())
        backend("STOPPED", next)
        check(stateText().contains("released"))
    }
    scenario("restore/options update all IDs without per-ID state; removal detaches; readd rebuilds") {
        manager.ids = intArrayOf(90, 91)
        provider.onRestored(context, intArrayOf(41, 42), manager.ids); drain()
        check(manager.updates.containsKey(90) && manager.updates.containsKey(91))
        provider.onAppWidgetOptionsChanged(context, manager, 90, Bundle()); drain()
        manager.ids = intArrayOf(); provider.onDeleted(context, intArrayOf(90, 91)); provider.onDisabled(context); drain()
        val size = manager.updates.size
        controller.pairing.changed(); drain(); check(manager.updates.size == size)
        manager.ids = intArrayOf(41, 42); provider.onEnabled(context); provider.onUpdate(context, manager, manager.ids); drain()
        check(stateText().contains("released"))
    }
    scenario("Activity recreation does not replay command and removed-widget token performs no command") {
        val old = primary(); val before = host.starts.size
        PhoneWidgetActionActivity().apply { intent = old.intent; onCreate(Bundle()); check(finished) }
        check(host.starts.size == before)
        manager.ids = intArrayOf(); old.send(); drain(); check(host.starts.size == before)
    }
    scenario("app-created replacement process starts UNKNOWN and adopts existing backend") {
        PhoneWidgetUpdates.disabled(); drain()
        val field = PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }; field.set(null, null)
        host.value = HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STREAMING", sessionEpoch = "recovered-owner", projectionToken = "recovered-token")
        manager.ids = intArrayOf(41, 42)
        val recovered = PhoneSessionController.get(context)
        check(recovered.state().phase == SessionPhase.UNKNOWN)
        drain()
        check(recovered.state().phase == SessionPhase.CONNECTING)
        check(stateText().contains("Connecting"))
        check(PhoneWidgetIntents.read(primary().intent)!!.epoch == "recovered-owner")
    }

    scenario("unreadable pairing uses existing setup recovery instead of crashing widget entry") {
        val current = PhoneSessionController.get(context)
        current.store.unreadable = true; current.pairing.changed(); drain()
        check(stateText().contains("setup"))
        val before = host.starts.size; primary().send(); drain()
        check(host.starts.size == before && Activity.launches.last().type == HostActivity::class.java)
        check(Activity.launches.last().flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        current.store.unreadable = false
    }
    fun newProcess(status: HostBootstrap.Status): PhoneSessionController {
        PhoneWidgetUpdates.disabled(); drain()
        PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        // Distinct process fixtures have a private durable-ledger directory; no real app state is read.
        Context.root = java.nio.file.Files.createTempDirectory("widget-recovery-").toFile()
        HostBootstrap.current = HostBootstrap().apply { value = status }
        return PhoneSessionController.get(context)
    }
    scenario("cold STOP waits for same recovered owner and duplicate Activities dispatch at most once") {
        val recovered = newProcess(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STREAMING", sessionEpoch = "same-owner", projectionToken = "same-token"))
        val token = PhoneWidgetIntents.pending(context, 41, PhoneWidgetAction(PhoneWidgetCommand.STOP, "same-owner"))
        val first = token.send(); val duplicate = token.send()
        check(!first.finished && !duplicate.finished && recovered.bootstrap.stops == 0)
        drain()
        check(first.finished && duplicate.finished && recovered.bootstrap.stops == 1)
        check(recovered.state().phase == SessionPhase.STOPPING)
        drain(); check(recovered.bootstrap.stops == 1)
    }
    scenario("cold old START and replacement STOP route status without retargeting") {
        for (op in listOf(PhoneWidgetCommand.START, PhoneWidgetCommand.STOP)) {
            val recovered = newProcess(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "UNKNOWN"))
            val token = PhoneWidgetIntents.pending(context, 41, PhoneWidgetAction(op, "old-epoch"))
            val activity = token.send(); drain(); check(!activity.finished)
            recovered.bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,
                if (op == PhoneWidgetCommand.START) "STOPPED" else "STREAMING", sessionEpoch = "replacement-owner", projectionToken = "new-token"))
            drain()
            check(activity.finished && recovered.bootstrap.starts.isEmpty() && recovered.bootstrap.stops == 0)
            check(Activity.launches.last().type == HostActivity::class.java)
        }
    }
    scenario("finishing an UNKNOWN Activity removes subscription and prevents later dispatch") {
        val recovered = newProcess(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "UNKNOWN"))
        val token = PhoneWidgetIntents.pending(context, 41, PhoneWidgetAction(PhoneWidgetCommand.STOP, "canceled-owner"))
        val activity = token.send(); drain(); check(!activity.finished)
        activity.finish()
        recovered.bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STREAMING", sessionEpoch = "canceled-owner", projectionToken = "token"))
        drain(); check(recovered.bootstrap.stops == 0)
    }
    scenario("widget initial rendering failure cannot prevent get(context).stop() from reaching existing owner") {
        val current = PhoneSessionController.get(context)
        PhoneWidgetUpdates.disabled(); drain()
        manager.failUpdates = true
        try {
            PhoneSessionController.get(context).stop(); drain()
            check(current.bootstrap.stops == 1)
        } finally { manager.failUpdates = false }
        provider.onUpdate(context, manager, manager.ids); drain()
        check(stateText().contains("Stopping"))
    }
    println("$count actual controller/widget entry cases PASS; Android framework, bootstrap and network modeled; no device/physical proof")
}
