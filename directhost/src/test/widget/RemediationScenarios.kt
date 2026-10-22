package com.code2hack.eyeprojector.directhost.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Handler
import android.widget.Button
import android.widget.LinearLayout
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.protocol.*

private var cases = 0
private var failures = 0
private val context = Context()
private val manager = AppWidgetManager.manager
private fun drain() = Handler.drain()
private fun reset(status: HostBootstrap.Status = HostBootstrap.Status(HostBootstrap.Setup.UNAVAILABLE, "UNKNOWN")): PhoneSessionController {
    manager.failUpdates = false; manager.failIds = emptySet()
    Activity.instances.toList().forEach { it.finish() }; Activity.instances.clear()
    PhoneWidgetUpdates.disabled(); drain()
    PhoneSessionController::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    Context.root = java.nio.file.Files.createTempDirectory("widget-r19-remediation-").toFile()
    manager.ids = intArrayOf(41, 42); manager.updates.clear(); manager.updateCounts.clear(); Activity.launches.clear()
    HostBootstrap.current = HostBootstrap().apply { value = status }
    return PhoneSessionController.get(context)
}
private fun case(name: String, run: () -> Unit) {
    cases++
    try { run(); println("PASS $name") }
    catch (error: Throwable) { failures++; println("FAIL $name: $error"); error.printStackTrace() }
    finally {
        manager.failUpdates = false; manager.failIds = emptySet()
        Activity.instances.toList().forEach { it.finish() }; drain()
    }
}
private fun stop(owner: String) = PhoneWidgetIntents.pending(context, 41, PhoneWidgetAction(PhoneWidgetCommand.STOP, owner))
private fun publish(c: PhoneSessionController, owner: String, setup: HostBootstrap.Setup = HostBootstrap.Setup.AVAILABLE) {
    c.bootstrap.publish(HostBootstrap.Status(setup, "STREAMING", sessionEpoch = owner, projectionToken = "token-$owner")); drain()
}
private fun command(c: PhoneSessionController, op: SessionControl.Operation) {
    PhoneSessionController::class.java.getDeclaredMethod("command", SessionControl.Request::class.java).apply { isAccessible = true }
        .invoke(c, SessionControl.Request(op, c.state().epoch)); drain()
}
private fun observers(c: PhoneSessionController) = (PhoneSessionController::class.java.getDeclaredField("stateObservers")
    .apply { isAccessible = true }.get(c) as Set<*>).size
private fun knownOwner(name: String): PhoneSessionController = reset().also { publish(it, name) }
private fun panelToken() = manager.updates.getValue(41).intents.getValue(R.id.phone_widget_panel)
private fun statusLaunched() = Activity.launches.lastOrNull()?.type == HostActivity::class.java

fun main() {
    case("R19-01 real default sentinel/replay/dedup waits, then same recovered owner stops once") {
        val c = reset(); val sentinel = c.bootstrap.status(); val base = observers(c)
        check(sentinel == HostBootstrap.Status(HostBootstrap.Setup.UNAVAILABLE, "UNKNOWN"))
        val a = stop("same-owner").send(); drain()
        val duplicate: () -> Unit = {}
        c.observeState(duplicate); c.state(); c.pairing.changed(); c.bootstrap.publish(sentinel.copy()); drain(); c.removeStateObserver(duplicate)
        check(c.bootstrap.status() === sentinel)
        val waiting = !a.finished && c.bootstrap.stops == 0 && Activity.launches.isEmpty()
        publish(c, "same-owner")
        check(waiting) { "Initial UNAVAILABLE/UNKNOWN ended Stop before canonical reconciliation" }
        check(a.finished && c.bootstrap.stops == 1 && observers(c) == base)
        c.pairing.changed(); drain(); check(c.bootstrap.stops == 1)
    }
    case("R19-01 original AVAILABLE-preseeded control still recovers exact owner") {
        val c = reset(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "UNKNOWN"))
        val a = stop("control-owner").send(); check(!a.finished)
        publish(c, "control-owner"); check(a.finished && c.bootstrap.stops == 1)
    }
    case("R19-01 replacement stays untouched after actual initial sentinel") {
        val c = reset(); val a = stop("old-owner").send(); drain(); val waited = !a.finished
        publish(c, "replacement-owner")
        check(waited && a.finished && c.bootstrap.stops == 0 && statusLaunched())
        check(c.state().epoch == "replacement-owner")
    }
    case("R19-01 unchanged unavailable sentinel retains working explicit status action") {
        val c = reset(); val a = stop("unresolved-owner").send(); drain()
        check(!a.finished)
        c.bootstrap.refresh(); c.bootstrap.publish(c.bootstrap.status().copy()); drain()
        check(!a.finished && c.bootstrap.stops == 0)
        (a.content as LinearLayout).children.filterIsInstance<Button>().single().performClick()
        check(a.finished && statusLaunched()); val before = c.bootstrap.stops
        publish(c, "unresolved-owner"); check(c.bootstrap.stops == before)
    }
    case("R19-01 distinct observed setup failures route status without Stop") {
        for (setup in listOf(HostBootstrap.Setup.MISSING, HostBootstrap.Setup.AUTHORIZATION_REQUIRED)) {
            val c = reset(); val a = stop("setup-owner").send(); drain(); val waited = !a.finished
            c.bootstrap.publish(HostBootstrap.Status(setup, "UNKNOWN", "OBSERVED_SETUP_FAILURE")); drain()
            check(waited && a.finished && c.bootstrap.stops == 0 && statusLaunched())
        }
    }
    case("R19-01 missing pairing remains immediately actionable") {
        val c = reset(); c.store.paired = false
        val a = stop("missing-pair").send(); drain()
        check(a.finished && c.bootstrap.stops == 0 && statusLaunched())
    }
    case("R19-01 finish before reconciliation cannot later dispatch") {
        val c = reset(); val a = stop("canceled-owner").send(); drain(); val waited = !a.finished
        a.finish(); publish(c, "canceled-owner")
        check(waited && c.bootstrap.stops == 0)
    }
    case("R19-01 duplicate pending Activities stop only their one recovered owner") {
        val c = reset(); val first = stop("duplicate-owner").send(); val second = stop("duplicate-owner").send(); drain()
        val waiting = !first.finished && !second.finished
        publish(c, "duplicate-owner"); c.pairing.changed(); drain()
        check(waiting && first.finished && second.finished && c.bootstrap.stops == 1)
    }
    case("accepted old inactive START still routes status without acquiring a new epoch") {
        val c = reset()
        val a = PhoneWidgetIntents.pending(context, 41, PhoneWidgetAction(PhoneWidgetCommand.START, "old-inactive-epoch")).send(); drain()
        c.bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STOPPED")); drain()
        check(a.finished && statusLaunched() && c.bootstrap.starts.isEmpty())
    }
    case("R19-02 missing setup disables Panel off and delivered old token opens status without OFF") {
        val c = knownOwner("panel-setup"); command(c, SessionControl.Operation.RENDERED); val token = panelToken()
        c.bootstrap.publish(c.bootstrap.status().copy(setup = HostBootstrap.Setup.MISSING)); drain()
        val enabled = manager.updates.getValue(41).enabled.getValue(R.id.phone_widget_panel)
        val a = token.send(); drain()
        check(!enabled && a.finished && statusLaunched() && c.bootstrap.panelRequests.isEmpty())
    }
    case("R19-02 absent/unreadable pairing delivers status without OFF") {
        for (unreadable in listOf(false, true)) {
            val c = knownOwner("panel-pair"); command(c, SessionControl.Operation.RENDERED); val token = panelToken()
            if (unreadable) c.store.unreadable = true else c.store.paired = false
            c.pairing.changed(); drain()
            val enabled = manager.updates.getValue(41).enabled.getValue(R.id.phone_widget_panel)
            val a = token.send(); drain()
            check(!enabled && a.finished && statusLaunched() && c.bootstrap.panelRequests.isEmpty())
        }
    }
    case("R19-02 available original projection remains eligible; stale context remains untouched") {
        val c = knownOwner("panel-valid"); command(c, SessionControl.Operation.RENDERED)
        val token = panelToken(); token.send(); drain()
        check(c.bootstrap.panelRequests.single().first == ProjectionContext("panel-valid", "token-panel-valid"))
        c.bootstrap.publish(c.bootstrap.status().copy(projectionToken = "replacement-token")); drain()
        token.send(); drain(); check(c.bootstrap.panelRequests.size == 1 && statusLaunched())
    }
    case("R19-02 existing Stop still runs through setup loss") {
        val c = knownOwner("stop-setup"); c.bootstrap.publish(c.bootstrap.status().copy(setup = HostBootstrap.Setup.MISSING)); drain()
        val a = stop("stop-setup").send(); drain()
        check(a.finished && c.bootstrap.stops == 1 && statusLaunched())
    }
    case("R19-03 delayed render failure completes Stop and removes Activity observer") {
        val c = reset(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "UNKNOWN")); val base = observers(c)
        val a = stop("render-delayed").send(); check(!a.finished)
        manager.failUpdates = true
        publish(c, "render-delayed")
        check(c.bootstrap.stops == 1 && a.finished && observers(c) == base)
        c.pairing.changed(); drain(); check(c.bootstrap.stops == 1)
    }
    case("R19-03 initial render failure cannot escape onCreate or replay command") {
        val c = knownOwner("render-initial"); val base = observers(c); manager.failUpdates = true
        val entry = runCatching { stop("render-initial").send() }
        check(entry.isSuccess && entry.getOrThrow().finished && c.bootstrap.stops == 1 && observers(c) == base)
    }
    case("R19-03 one failed instance cannot block another or initial provider entry") {
        val c = knownOwner("render-many"); val before = manager.updateCounts.getValue(42)
        manager.failIds = setOf(41)
        val error = runCatching { PhoneWidgetProvider().onUpdate(context, manager, manager.ids) }.exceptionOrNull()
        check(error == null && manager.updateCounts.getValue(42) > before)
        manager.failIds = emptySet(); c.pairing.changed(); drain()
        check(manager.updates.keys.containsAll(listOf(41, 42)))
    }
    case("R19-03 dispatch failure before command completes status exactly once") {
        val c = knownOwner("dispatch-before"); val base = observers(c); c.bootstrap.failRefresh = true
        val entry = runCatching { stop("dispatch-before").send() }
        check(entry.isSuccess && entry.getOrThrow().finished && observers(c) == base && c.bootstrap.stops == 0 && statusLaunched())
        c.pairing.changed(); drain(); check(c.bootstrap.stops == 0)
    }
    case("R19-03 dispatch failure after irreversible command completes without replay") {
        val c = knownOwner("dispatch-after"); val base = observers(c); c.bootstrap.failStop = true
        val entry = runCatching { stop("dispatch-after").send() }
        check(entry.isSuccess && entry.getOrThrow().finished && observers(c) == base && c.bootstrap.stops == 1 && statusLaunched())
        c.pairing.changed(); drain(); check(c.bootstrap.stops == 1)
    }
    case("R19-04 descriptor disables periodic updates while Receiver Stop still updates all instances") {
        val c = knownOwner("event-stop"); command(c, SessionControl.Operation.STOP)
        check(manager.updates.values.all { it.text.getValue(R.id.phone_widget_status).toString().contains("Stopping") })
        check(WidgetDescriptor.updatePeriodMillis == 0L) { "New periodic widget polling remains enabled" }
    }
    println("$cases remediation behavior cases, $failures failed; actual widget/controller/ledger code, modeled Android/bootstrap/peer boundaries")
    check(failures == 0) { "$failures remediation regressions failed" }
}
