package com.code2hack.eyeprojector.directhost.hid

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Environment
import android.content.Intent
import android.os.MainQueue
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.ExtractedHostHid
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private fun equal(expected: Any?, actual: Any?, reason: String) {
    check(expected == actual) { "$reason: expected <$expected>, actual <$actual>" }
}

/** Explicit ready local port keeps isolated controller/lifecycle tests independent of absent native setup. */
private fun routedRuntime():PhoneHidRuntime {
 val runtime=PhoneHidRuntime.get(Context())
 runtime.controller.activation=object:PhoneHidActivation {
  override fun needsWake()=false
  override fun request(cause:ActivationCause)=Unit
  override fun capture()=PhoneHidCapture(PhoneHidDisposition.USABLE,
   HidWakeRequest(PhoneHidDisposition.USABLE,PhoneSessionController.fixture.captureHidAuthority()),
   PhoneSessionController.fixture::capturePhoneHidRoute)
 }
 return runtime
}

private val peripheral = InputDevice(41, "phone-fixture:descriptor", 0x1209, 0x0017, "External Phone fixture")
private fun binding(device: InputDevice = peripheral, key: Int = 195, scan: Int = 700) = PhoneHidBinding(
    PhoneHidButton(PhoneHidDeviceIdentity(device.descriptor, device.vendorId, device.productId), key, scan),
    device.name, KeyEvent.keyCodeToString(key))
private fun event(at: Long, down: Long = at, phase: PhoneHidPhase = PhoneHidPhase.DOWN,
                  device: InputDevice = peripheral, key: Int = 195, scan: Int = 700, repeat: Int = 0) =
    PhoneHidController.Event(device.id, binding(device, key, scan), phase, down, at, repeat)

private class Rig(activation: PhoneHidActivation = PhoneHidActivation.Unwired) {
    data class Call(val kind: String, val route: PhoneHidRoute?, val edge: PhoneHidEdge?)
    val calls = mutableListOf<Call>()
    val writes = mutableListOf<String>()
    var record: String? = null
    var writable = true
    var clock = 1000L
    var onChanged: () -> Unit = {}
    var onCapture: (PhoneHidEdge) -> Unit = {}
    var onSubmit: (PhoneHidEdge) -> Unit = {}
    var onCancel: () -> Unit = {}
    val jobs = mutableListOf<Pair<() -> Unit, () -> Unit>>()
    val bindings = PhoneHidBindings({ record }) { writes.add(it); if (writable) record = it; writable }
    private var next = 0
    val input = PhoneHidController(bindings, object : PhoneHidRoutes {
        override fun capture(down: PhoneHidEdge): PhoneHidRoute {
            val route = PhoneHidRoute(++next)
            calls.add(Call("capture", route, down)); onCapture(down); return route
        }
        override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) { calls.add(Call("submit", route, edge)); onSubmit(edge) }
        override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) { calls.add(Call("cancel", route, null)); onCancel() }
    }, { operation, complete -> jobs.add(Pair(operation, complete)) }, { clock }, { onChanged() }, activation)
    fun flush() { while (jobs.isNotEmpty()) { val (operation, complete) = jobs.removeAt(0); operation(); complete() } }
    fun once(action: () -> Unit) { onChanged = { onChanged = {}; action() } }
    fun seed(action: PhoneHidAction, key: Int = 195, scan: Int = 700) { check(bindings.assign(action, binding(key = key, scan = scan))) }
}

private fun capturePublishesOwnershipBeforeMatchingUpFeedback() {
    val r = Rig(); r.input.openPage(); r.input.select(PhoneHidAction.TAP)
    r.once { check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP))) }
    check(r.input.accept(event(1000)))
    equal(false, r.input.capturing, "matching reentrant UP completes the admitted actual DOWN")
    equal(1, r.input.saving, "one deferred persistence operation")
    r.flush()
    equal(binding(), r.bindings.binding(PhoneHidAction.TAP), "actual DOWN facts save after matching reentrant UP")
    equal(1, r.writes.size, "feedback cannot create a second save")
    equal(PhoneHidAction.TAP, r.input.selected, "Phone row stays selected for replacement")
    check(r.calls.isEmpty())
}

private fun newerCaptureCompletionAndPageChangeSupersedeOuterFeedback() {
    val r = Rig(); r.input.openPage(); r.input.select(PhoneHidAction.TAP)
    r.once {
        r.input.select(PhoneHidAction.SCROLL_UP)
        check(r.input.accept(event(1100, key = 196, scan = 701)))
        check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP, key = 196, scan = 701)))
    }
    check(r.input.accept(event(1000)))
    check(r.input.accept(event(1200, 1000, PhoneHidPhase.UP)))
    r.flush()
    equal(null, r.bindings.binding(PhoneHidAction.TAP), "older outer capture is never revived")
    equal(binding(key = 196, scan = 701), r.bindings.binding(PhoneHidAction.SCROLL_UP), "newer completed capture alone persists")
    equal(1, r.writes.size, "older late UP cannot enqueue persistence")
    r.input.select(PhoneHidAction.FUNCTION)
    r.once { r.input.closePage() }
    check(r.input.accept(event(1300, key = 197, scan = 702)))
    check(r.input.accept(event(1320, 1300, PhoneHidPhase.UP, key = 197, scan = 702)))
    r.flush()
    equal(null, r.bindings.binding(PhoneHidAction.FUNCTION), "closing page during received feedback retires capture")
    check(!r.input.pageVisible && r.calls.isEmpty())
    val canceled = Rig(); canceled.input.openPage(); canceled.input.select(PhoneHidAction.TAP)
    canceled.once {
        canceled.input.select(PhoneHidAction.SCROLL_DOWN)
        check(canceled.input.accept(event(1100, key = 196, scan = 701)))
        check(canceled.input.accept(event(1110, 1100, PhoneHidPhase.CANCEL, key = 196, scan = 701)))
    }
    check(canceled.input.accept(event(1000)))
    check(canceled.input.accept(event(1120, 1000, PhoneHidPhase.UP)))
    check(canceled.input.accept(event(1130, 1100, PhoneHidPhase.UP, key = 196, scan = 701)))
    canceled.flush()
    equal(false, canceled.input.capturing, "newer canceled capture cannot revive the outer capture")
    check(canceled.writes.isEmpty() && canceled.calls.isEmpty())
}

private fun normalDownRetainsMatchingTerminalThroughFeedbackAndRouteConstruction() {
    val r = Rig(); r.seed(PhoneHidAction.TAP); r.input.openPage()
    r.once { check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP))) }
    check(r.input.accept(event(1000)))
    equal(listOf("capture", "submit"), r.calls.map { it.kind }, "route capture submits DOWN once, followed by terminal UP")
    equal(listOf(PhoneHidPhase.DOWN, PhoneHidPhase.UP), r.calls.map { it.edge!!.phase }, "actual edge order")
    equal(listOf(Pair(1000L, 1000L), Pair(1000L, 1020L)), r.calls.map { Pair(it.edge!!.downTimeMs, it.edge.eventTimeMs) }, "real times")
    check(r.calls[0].route === r.calls[1].route)
    val s = Rig(); s.seed(PhoneHidAction.TAP)
    s.onCapture = { s.onCapture = {}; check(s.input.accept(event(1020, 1000, PhoneHidPhase.UP))) }
    check(s.input.accept(event(1000)))
    equal(listOf("capture", "submit"), s.calls.map { it.kind }, "terminal during route construction is retained")
    for (retirement in listOf("missing", "changed", "removed")) {
        val retired = Rig(); retired.seed(PhoneHidAction.TAP); retired.input.openPage()
        retired.once {
            check(retired.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
            when (retirement) {
                "missing" -> check(retired.input.consumeUnavailable(41, 195, 700, 1000, PhoneHidPhase.UP))
                "changed" -> check(retired.input.consumeChanged(41, 195, 700, 1000, PhoneHidPhase.UP,
                    PhoneHidDeviceIdentity("changed:before:route", 1, 2)))
                else -> retired.input.deviceRemoved(41)
            }
        }
        check(retired.input.accept(event(1000)))
        check(retired.calls.isEmpty()) { "$retirement must retire constructing ownership after a reentrant terminal" }
    }
}

private fun newerCompletedSharedTapPreventsOlderOuterRouteAndFunctionRemainsIndependent() {
    val r = Rig(); r.seed(PhoneHidAction.TAP); r.input.openPage()
    r.once {
        // An actual binding replacement can occur while feedback is published.
        r.seed(PhoneHidAction.TAP, 196, 701)
        check(r.input.accept(event(1100, key = 196, scan = 701)))
        check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP, key = 196, scan = 701)))
    }
    check(r.input.accept(event(1000)))
    check(r.input.accept(event(1200, 1000, PhoneHidPhase.UP)))
    equal(listOf("capture", "submit"), r.calls.map { it.kind }, "newer completed shared TAP supersedes older pending DOWN")
    check(r.calls.all { it.edge!!.keyCode == 196 })
    for (nested in listOf(PhoneHidAction.FUNCTION, PhoneHidAction.SCROLL_DOWN)) {
        val independent = Rig(); independent.seed(PhoneHidAction.TAP); independent.seed(nested, 196, 701)
        independent.input.openPage()
        independent.once {
            check(independent.input.accept(event(1100, key = 196, scan = 701)))
            check(independent.input.accept(event(1120, 1100, PhoneHidPhase.UP, key = 196, scan = 701)))
        }
        check(independent.input.accept(event(1000)))
        check(independent.input.accept(event(1200, 1000, PhoneHidPhase.UP)))
        equal(listOf(nested, nested, PhoneHidAction.TAP, PhoneHidAction.TAP),
            independent.calls.map { it.edge!!.action }, "independent $nested cannot blanket-retire the TAP admission")
        check(independent.calls[0].route === independent.calls[1].route)
        check(independent.calls[2].route === independent.calls[3].route)
        check(independent.calls[0].route !== independent.calls[2].route)
    }
}

private fun cancelDuringNormalFeedbackAndPendingCaptureSaveNeverReplay() {
    val r = Rig(); r.seed(PhoneHidAction.TAP); r.input.openPage()
    r.once { r.input.cancelInput() }
    check(r.input.accept(event(1000)))
    check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
    check(r.calls.isEmpty()) { "canceled pending DOWN must never capture a route" }
    r.input.select(PhoneHidAction.FUNCTION)
    check(r.input.accept(event(1100, key = 196, scan = 701)))
    check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP, key = 196, scan = 701)))
    equal(1, r.input.saving, "asynchronous save remains explicit")
    r.input.closePage(); r.writable = false; r.flush()
    equal(null, r.bindings.binding(PhoneHidAction.FUNCTION), "failed queued save keeps effective assignments")
    equal("HID bindings could not be saved. Try again.", r.bindings.error, "queued failure remains truthful")
    check(r.calls.isEmpty())
}

private fun resetQueuesBeforeCancelCallbackCompletesNewerAssignment() {
    val r = Rig(); r.seed(PhoneHidAction.TAP)
    check(r.input.accept(event(1000)))
    r.input.openPage()
    // Keep a route active with a visible page and no selected learning row.
    check(r.input.accept(event(1100)))
    r.onCancel = {
        r.onCancel = {}
        r.input.select(PhoneHidAction.SCROLL_DOWN)
        check(r.input.accept(event(1200, key = 196, scan = 701)))
        check(r.input.accept(event(1220, 1200, PhoneHidPhase.UP, key = 196, scan = 701)))
    }
    r.input.resetAll()
    equal(2, r.jobs.size, "reset write precedes callback-completed newer assignment write")
    r.flush()
    equal(null, r.bindings.binding(PhoneHidAction.TAP), "reset removes prior mapping")
    equal(binding(key = 196, scan = 701), r.bindings.binding(PhoneHidAction.SCROLL_DOWN), "newer completed assignment survives outer reset")
    check(r.input.accept(event(1300, 1100, PhoneHidPhase.UP)))
    equal(3, r.writes.size, "seed plus reset plus newer save; old late UP does not write")
}

private fun awaitSaved(runtime: PhoneHidRuntime) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (runtime.controller.saving != 0 && System.nanoTime() < until) { MainQueue.drain(); Thread.sleep(1) }
    MainQueue.drain()
    equal(0, runtime.controller.saving, "actual writer completion returns through explicit main queue")
}

private fun serviceEdge(service: PhoneHidKeyService, at: Long, down: Long = at, action: Int = KeyEvent.ACTION_DOWN,
                        device: InputDevice? = peripheral, key: Int = 195, scan: Int = 700,
                        repeat: Int = 0, flags: Int = 0, attachment: Int = device?.id ?: 41): Boolean {
    SystemClock.now = at
    return service.onKeyEvent(KeyEvent(device, action, key, scan, down, at, repeat, flags, attachment))
}

private fun rows(panel: PhoneHidPanel) = panel.children.filterIsInstance<Button>().filter {
    it.text.toString().startsWith("Tap:") || it.text.toString().startsWith("Scroll down:") ||
        it.text.toString().startsWith("Scroll up:") || it.text.toString().startsWith("Function button:")
}
private fun text(panel: PhoneHidPanel) = panel.children.filterIsInstance<TextView>().map { it.text.toString() }

private fun actualPanelServiceAndAsyncPersistenceRetainStickySelectionAndSingleDownRoutes() {
    val context = Context(); val runtime = routedRuntime(); val input = runtime.controller
    val service = PhoneHidKeyService(); service.onServiceConnected()
    val panel = PhoneHidPanel(context, runtime) {}; panel.open()
    try {
        val buttons = rows(panel)
        equal(4, buttons.size, "actual panel has all four assignment rows")
        equal(1, panel.children.filterIsInstance<Button>().count { it.text == "Reset all HID bindings" }, "one reset only")
        check(panel.children.filterIsInstance<Button>().all { !it.isAllCaps })
        val status = panel.children.filterIsInstance<TextView>().single { it.contentDescription == "Last received external HID key" }
        equal("", status.text.toString(), "status starts empty on page open")
        equal(1, status.maxLines, "status is single line")
        equal(64, checkNotNull(panel.childLayouts[status]).height, "fixed received-line height")
        PhoneSessionController.fixture.clear()
        check(!serviceEdge(service, 1800, key = 194, scan = 799))
        equal("HOST_KEYCODE_194", status.text.toString(), "no selected row still reports eligible unbound DOWN with false consumption")
        check(!serviceEdge(service, 1820, 1800, KeyEvent.ACTION_UP, key = 194, scan = 799))
        val excluded = listOf(peripheral.copy(isExternal = false), peripheral.copy(isVirtual = true),
            peripheral.copy(name = "ROKID,PSOC-TP-R"), peripheral.copy(name = "qpnp_pon"))
        excluded.forEachIndexed { index, device ->
            check(!serviceEdge(service, 1850L + index * 10, device = device))
            equal("HOST_KEYCODE_194", status.text.toString(), "excluded internal/virtual/Rokid input cannot alter received line")
        }
        check(PhoneSessionController.fixture.calls.isEmpty())
        buttons[0].performClick()
        check(serviceEdge(service, 2000))
        check(serviceEdge(service, 2020, 2000, KeyEvent.ACTION_UP))
        awaitSaved(runtime)
        equal(binding(), input.bindings.binding(PhoneHidAction.TAP), "actual service DOWN identity persisted by actual writer")
        equal(PhoneHidAction.TAP, input.selected, "selection stays for immediate repeated replacement")
        check(serviceEdge(service, 2100, key = 196, scan = 701))
        check(serviceEdge(service, 2120, 2100, KeyEvent.ACTION_UP, key = 196, scan = 701))
        awaitSaved(runtime)
        equal(binding(key = 196, scan = 701), input.bindings.binding(PhoneHidAction.TAP), "same row accepts another actual key")
        check(serviceEdge(service, 2200, key = 196, scan = 701))
        check(serviceEdge(service, 2205, 2200, key = 196, scan = 701))
        equal("HOST_KEYCODE_196 x2", status.text.toString(), "repeat0 duplicate same actual DOWN does not increment status")
        check(serviceEdge(service, 2210, 2200, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS, key = 196, scan = 701))
        check(serviceEdge(service, 2220, 2200, KeyEvent.ACTION_UP, key = 196, scan = 701))
        awaitSaved(runtime)
        equal("HOST_KEYCODE_196 x2", status.text.toString(), "distinct physical press increments; held repeat does not")
        buttons[1].performClick()
        check(serviceEdge(service, 2230, key = 196, scan = 701))
        check(serviceEdge(service, 2250, 2230, KeyEvent.ACTION_UP, key = 196, scan = 701))
        awaitSaved(runtime)
        equal(null, input.bindings.binding(PhoneHidAction.TAP), "moving same actual key removes former TAP assignment")
        equal(binding(key = 196, scan = 701), input.bindings.binding(PhoneHidAction.SCROLL_DOWN), "same actual key moves to newly selected row")
        equal("Tap: Unassigned", buttons[0].text.toString(), "former row truthfully displays Unassigned")
        check(buttons[1].text.toString().startsWith("Scroll down: External Phone fixture: HOST_KEYCODE_196"))
        buttons[0].performClick()
        check(serviceEdge(service, 2260, key = 196, scan = 701))
        check(serviceEdge(service, 2280, 2260, KeyEvent.ACTION_UP, key = 196, scan = 701))
        awaitSaved(runtime)
        val saved = Context.preferences.getValue("phone_hid_bindings")
        equal(input.bindings.binding(PhoneHidAction.TAP), PhoneHidBindings({ saved.getString("bindings", null) }) { false }.binding(PhoneHidAction.TAP), "Phone-private readback")
        panel.close()
        PhoneSessionController.fixture.clear()
        check(serviceEdge(service, 2300, key = 196, scan = 701))
        check(serviceEdge(service, 2310, 2300, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS, key = 196, scan = 701))
        check(serviceEdge(service, 2320, 2300, KeyEvent.ACTION_UP, key = 196, scan = 701))
        val calls = PhoneSessionController.fixture.calls
        equal(listOf("capture", "submit"), calls.map { it.kind }, "actual runtime facade captures/submits DOWN exactly once")
        equal(listOf(PhoneHidPhase.DOWN, PhoneHidPhase.UP), calls.map { it.edge!!.phase }, "actual edge phases")
        check(calls.all { it.edge!!.action == PhoneHidAction.TAP })
        check(calls[0].route === calls[1].route)
    } finally { panel.close(); service.onDestroy() }
}

private fun actualServiceCorrelatesChangedMissingAndRetiredReleasesBeforeMetadata() {
    val runtime = routedRuntime(); val input = runtime.controller
    input.closePage(); check(input.bindings.assign(PhoneHidAction.TAP, binding()))
    val service = PhoneHidKeyService(); service.onServiceConnected()
    try {
        for ((at, changed) in listOf(Pair(3000L, true), Pair(3100L, false))) {
            PhoneSessionController.fixture.clear()
            check(serviceEdge(service, at))
            val device = if (changed) peripheral.copy(descriptor = "changed:actual:descriptor") else null
            check(serviceEdge(service, at + 20, at, KeyEvent.ACTION_UP, device = device))
            equal(listOf("capture", "cancel"), PhoneSessionController.fixture.calls.map { it.kind }, "changed/missing facts cancel captured route without target UP")
        }
        PhoneSessionController.fixture.clear()
        check(serviceEdge(service, 3200))
        Environment.inputManager.remove(peripheral.id)
        check(serviceEdge(service, 3210, 3200, repeat = 1, device = null))
        check(serviceEdge(service, 3220, 3200, KeyEvent.ACTION_UP, device = null))
        equal(listOf("capture", "cancel"), PhoneSessionController.fixture.calls.map { it.kind }, "removed attachment owns its late raw release")
        check(!serviceEdge(service, 3230, 3200, KeyEvent.ACTION_UP, device = null, key = 196, scan = 701))
        check(serviceEdge(service, 3300))
        check(serviceEdge(service, 3320, 3300, KeyEvent.ACTION_UP))
        equal(listOf("capture", "cancel", "capture", "submit"), PhoneSessionController.fixture.calls.map { it.kind }, "fresh actual press on reused attachment works")
    } finally { service.onDestroy() }
}

private fun actualNativeExclusionUnknownKeysAndUnavailableTargetsNeverReplay() {
    val runtime = routedRuntime(); val input = runtime.controller
    input.closePage(); input.cancelInput(); check(input.bindings.assign(PhoneHidAction.TAP, binding()))
    val service = PhoneHidKeyService(); service.onServiceConnected()
    try {
        PhoneSessionController.fixture.clear()
        val native = listOf(peripheral.copy(isExternal = false), peripheral.copy(isVirtual = true),
            peripheral.copy(name = "ROKID,PSOC-TP-R"), peripheral.copy(name = "qpnp_pon"))
        for ((index, device) in native.withIndex()) {
            check(!serviceEdge(service, 4000L + index * 100, device = device))
            check(!serviceEdge(service, 4020L + index * 100, 4000L + index * 100, KeyEvent.ACTION_UP, device = device))
        }
        check(PhoneSessionController.fixture.calls.isEmpty())
        check(!serviceEdge(service, 4400, key = 199, scan = 799))
        check(!serviceEdge(service, 4420, 4400, KeyEvent.ACTION_UP, key = 199, scan = 799))
        PhoneSessionController.fixture.targetAvailable = false
        check(serviceEdge(service, 4500))
        PhoneSessionController.fixture.targetAvailable = true
        check(serviceEdge(service, 4520, 4500, KeyEvent.ACTION_UP))
        equal(listOf("capture"), PhoneSessionController.fixture.calls.map { it.kind }, "bound no-target DOWN is consumed and its UP does not re-resolve")
        check(serviceEdge(service, 4600)); check(serviceEdge(service, 4620, 4600, KeyEvent.ACTION_UP))
        equal(listOf("capture", "capture", "submit"), PhoneSessionController.fixture.calls.map { it.kind }, "only a fresh actual DOWN may use a new target")
    } finally { service.onDestroy() }
}

private fun actualSettingsObservationResetFailureAndLifecycleAreTruthful() {
    val context = Context(); val runtime = PhoneHidRuntime.get(context)
    val reads = Environment.accessibility.reads
    Environment.accessibility.enabled.clear()
    runtime.refresh(); check(!runtime.enabled())
    repeat(3) { runtime.enabled() }
    equal(reads + 1, Environment.accessibility.reads, "enabled() is cached; refresh alone queries settings")
    val enabled = AccessibilityServiceInfo().apply {
        resolveInfo.serviceInfo.packageName = context.packageName
        resolveInfo.serviceInfo.name = PhoneHidKeyService::class.java.name
    }
    Environment.accessibility.enabled.add(enabled)
    runtime.refresh(); check(runtime.enabled())
    check(runtime.openSettings())
    equal("android.settings.ACCESSIBILITY_SETTINGS", context.startedActivities.last().action, "settings intent uses actual Android settings route")
    val host = ExtractedHostHid()
    val panel = host.panelForTest()
    host.openForTest(true)
    equal(View.GONE, host.controlsForTest().visibility, "actual Host helper hides ordinary controls")
    equal(View.VISIBLE, panel.visibility, "actual Host helper opens HID panel")
    val service = PhoneHidKeyService(); service.onServiceConnected()
    try {
        check(runtime.controller.connected)
        rows(panel)[3].performClick()
        check(serviceEdge(service, 5000, key = 198, scan = 798))
        check(serviceEdge(service, 5020, 5000, KeyEvent.ACTION_UP, key = 198, scan = 798))
        awaitSaved(runtime)
        val prefs = Context.preferences.getValue("phone_hid_bindings")
        val old = prefs.getString("bindings", null)
        prefs.writable = false
        panel.children.filterIsInstance<Button>().single { it.text == "Reset all HID bindings" }.performClick()
        awaitSaved(runtime)
        equal(old, prefs.getString("bindings", null), "failed reset preserves persisted Phone bindings")
        check(text(panel).contains("HID bindings could not be saved. Try again."))
        prefs.writable = true
        panel.children.filterIsInstance<Button>().single { it.text == "Reset all HID bindings" }.performClick()
        awaitSaved(runtime)
        check(PhoneHidAction.entries.all { runtime.controller.bindings.binding(it) == null })
        host.onBackPressed()
        equal(View.GONE, panel.visibility, "actual local Back closes HID page")
        equal(View.VISIBLE, host.controlsForTest().visibility, "actual local Back restores ordinary Phone controls")
        host.onBackPressed(); equal(1, host.backs, "outside HID page Back reaches the actual superclass boundary")
        host.openForTest(true); host.onStop()
        check(!runtime.controller.pageVisible)
        host.onResume(); check(runtime.controller.pageVisible)
        host.onDestroy(); check(!runtime.controller.pageVisible)
        service.onUnbind(Intent())
        check(!runtime.controller.connected)
        check(!serviceEdge(service, 5100))
        equal(0, Environment.inputManager.listenerCount, "service unbind unregisters exact listener")
    } finally { service.onDestroy(); host.onDestroy() }
}

private fun ordinaryHostStopDoesNotCancelOperationalHidAndInterruptRetiresOldOwner() {
    val runtime = routedRuntime(); val input = runtime.controller
    input.closePage(); check(input.bindings.assign(PhoneHidAction.TAP, binding()))
    val host = ExtractedHostHid(); val service = PhoneHidKeyService(); service.onServiceConnected()
    try {
        PhoneSessionController.fixture.clear()
        check(serviceEdge(service, 6000)); host.onStop()
        check(serviceEdge(service, 6020, 6000, KeyEvent.ACTION_UP))
        equal(listOf("capture", "submit"), PhoneSessionController.fixture.calls.map { it.kind }, "ordinary hidden panel stop does not own operational hold")
        check(serviceEdge(service, 6100)); service.onInterrupt()
        check(serviceEdge(service, 6120, 6100, KeyEvent.ACTION_UP))
        equal(listOf("capture", "submit", "capture", "cancel"), PhoneSessionController.fixture.calls.map { it.kind }, "interrupt cancels old route and consumes old UP")
    } finally { service.onDestroy(); host.onDestroy() }
}

private fun releasedFunctionCallerRouteRetiresAtEveryExplicitBoundary() {
    for (boundary in listOf("reset", "open", "select", "close", "disconnect", "removed", "cancel")) {
        val r = Rig(); r.seed(PhoneHidAction.FUNCTION); r.input.openPage()
        check(r.input.accept(event(1000)))
        check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
        equal(listOf("capture", "submit"), r.calls.map { it.kind }, "Function release initially submits DOWN/UP without cancellation")
        equal(listOf(Pair(1000L, 1000L), Pair(1000L, 1020L)),
            r.calls.map { Pair(it.edge!!.downTimeMs, it.edge.eventTimeMs) }, "Function caller edges retain actual press timestamps")
        r.clock = 1030
        val original = r.calls[0].route
        when (boundary) {
            "reset" -> r.input.resetAll()
            "open" -> r.input.openPage()
            "select" -> r.input.select(PhoneHidAction.TAP)
            "close" -> r.input.closePage()
            "disconnect" -> r.input.connection(false)
            "removed" -> r.input.deviceRemoved(peripheral.id)
            else -> r.input.cancelInput()
        }
        equal(listOf("capture", "submit", "cancel"), r.calls.map { it.kind }, "$boundary cancels the original released Function route")
        check(r.calls.all { it.route === original }) { "$boundary must not resolve a replacement route" }
        r.input.cancelInput()
        equal(3, r.calls.size, "$boundary retirement is idempotent")
        r.flush()
    }
    val pending = Rig(); pending.seed(PhoneHidAction.FUNCTION); pending.input.openPage()
    pending.once { check(pending.input.accept(event(1020, 1000, PhoneHidPhase.UP))) }
    check(pending.input.accept(event(1000)))
    pending.clock = 1030
    pending.input.cancelInput()
    equal(listOf("capture", "submit", "cancel"), pending.calls.map { it.kind }, "UP during feedback retains released Function ownership after route construction")
    check(pending.calls.all { it.route === pending.calls[0].route })
    val reentrant = Rig(); reentrant.seed(PhoneHidAction.FUNCTION)
    check(reentrant.input.accept(event(1000)))
    reentrant.onSubmit = { reentrant.onSubmit = {}; reentrant.clock = 1020; reentrant.input.cancelInput() }
    check(reentrant.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
    equal(listOf("capture", "submit", "cancel"), reentrant.calls.map { it.kind }, "released ownership is published before submit callback reentry")
}

private fun actualServiceFunctionSequenceKeepsOnlyLatestReleasedCallerRoute() {
    val runtime = routedRuntime(); val input = runtime.controller
    input.closePage(); input.cancelInput()
    check(input.bindings.assign(PhoneHidAction.FUNCTION, binding(key = 198, scan = 798)))
    val service = PhoneHidKeyService(); service.onServiceConnected()
    try {
        PhoneSessionController.fixture.clear()
        check(serviceEdge(service, 10000, key = 198, scan = 798))
        check(serviceEdge(service, 10020, 10000, KeyEvent.ACTION_UP, key = 198, scan = 798))
        check(serviceEdge(service, 10100, key = 198, scan = 798))
        check(serviceEdge(service, 10110, 10100, key = 198, scan = 798, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS))
        check(serviceEdge(service, 10120, 10100, KeyEvent.ACTION_UP, key = 198, scan = 798))
        val sequence = PhoneSessionController.fixture.calls
        equal(listOf("capture", "submit", "capture", "submit"), sequence.map { it.kind }, "ordinary successive Function presses add no intermediate cancellation")
        equal(listOf(PhoneHidPhase.DOWN, PhoneHidPhase.UP, PhoneHidPhase.DOWN, PhoneHidPhase.UP), sequence.map { it.edge!!.phase }, "actual Function edges reach the facade once")
        val latest = sequence[2].route
        service.onInterrupt()
        equal("cancel", sequence.last().kind, "interrupt retires caller's released Function route")
        check(sequence.last().route === latest)
        equal(1, sequence.count { it.kind == "cancel" }, "only the latest completed route is retained")

        PhoneSessionController.fixture.clear()
        val newerAttachment = peripheral.copy(id = 42)
        check(serviceEdge(service, 11000, key = 198, scan = 798))
        check(serviceEdge(service, 11100, device = newerAttachment, key = 198, scan = 798))
        check(serviceEdge(service, 11120, 11100, KeyEvent.ACTION_UP, device = newerAttachment, key = 198, scan = 798))
        val newer = PhoneSessionController.fixture.calls[1].route
        check(serviceEdge(service, 11140, 11000, KeyEvent.ACTION_UP, key = 198, scan = 798))
        val overlap = PhoneSessionController.fixture.calls
        equal(listOf("capture", "capture", "submit", "submit"), overlap.map { it.kind }, "older overlapping UP still terminates its original physical route")
        service.onInputDeviceRemoved(peripheral.id)
        equal(4, overlap.size, "old attachment removal cannot cancel the newer released marker")
        service.onInputDeviceRemoved(newerAttachment.id)
        equal(5, overlap.size, "newer attachment removal retires its retained caller route")
        equal("cancel", overlap.last().kind, "removal is cancellation")
        check(overlap.last().route === newer) { "an older later UP cannot replace the newer released marker" }
    } finally { service.onDestroy() }
}

private fun queuedAssignmentNeverClaimsAnAlreadyPassedThroughGesture() {
    for (terminal in listOf(PhoneHidPhase.UP, PhoneHidPhase.CANCEL)) {
        val r = Rig()
        r.input.openPage(); r.input.select(PhoneHidAction.TAP)
        check(r.input.accept(event(900)))
        check(r.input.accept(event(920, 900, PhoneHidPhase.UP)))
        equal(1, r.jobs.size, "completed earlier capture queues one actual store operation")
        equal(null, r.bindings.binding(PhoneHidAction.TAP), "queued save is not yet effective")
        r.input.closePage()
        check(!r.input.accept(event(1000))) { "unbound actual first DOWN must pass through" }
        r.flush()
        equal(binding(), r.bindings.binding(PhoneHidAction.TAP), "queued assignment publishes while pass-through key is held")
        check(!r.input.accept(event(1020, 1000, terminal))) { "$terminal remains false for the gesture whose DOWN passed through" }
        check(!r.input.accept(event(1025, 1000))) { "same raw duplicate DOWN remains false after map publication" }
        check(!r.input.accept(event(1030, 1000, repeat = 1))) { "held repeat remains false after map publication" }
        check(r.calls.isEmpty()) { "old pass-through edges must never capture or submit a route" }
        check(!r.input.capturing)
        equal(1, r.writes.size, "only the original complete capture saved")
        check(r.input.accept(event(1100)))
        check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP)))
        equal(listOf("capture", "submit"), r.calls.map { it.kind }, "next fresh bound gesture admits DOWN exactly once")
        equal(listOf(PhoneHidPhase.DOWN, PhoneHidPhase.UP), r.calls.map { it.edge!!.phase }, "next fresh gesture has complete true ownership")
        check(r.calls[0].route === r.calls[1].route)
        equal(listOf(1100L, 1120L), r.calls.map { it.edge!!.eventTimeMs }, "only fresh actual event times reach the route")
    }
}

private fun coldOrphanEdgesStayFalseWhileActualOwnedAndRetiredEdgesStayTrue() {
    val r = Rig(); r.seed(PhoneHidAction.TAP)
    check(!r.input.accept(event(900, 880, PhoneHidPhase.UP))) { "cold bound UP has no observed handled DOWN" }
    check(!r.input.accept(event(910, 890, PhoneHidPhase.CANCEL))) { "cold bound CANCEL has no observed handled DOWN" }
    check(!r.input.accept(event(920, 900, repeat = 1))) { "cold held repeat cannot invent a handled DOWN" }
    check(r.calls.isEmpty())
    check(r.input.accept(event(1000)))
    check(r.input.accept(event(1010, 1000, repeat = 1)))
    check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
    check(r.input.accept(event(1030, 1000, PhoneHidPhase.UP))) { "an explicitly retired handled release stays consumed" }
    check(!r.input.accept(event(1040, 1030, PhoneHidPhase.UP))) { "different orphan downTime stays false" }
    check(r.input.accept(event(1100)))
    r.clock = 1110; r.input.cancelInput()
    check(r.input.accept(event(1110, 1100, PhoneHidPhase.CANCEL)))
    check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP))) { "owned canceled raw release stays true" }
    r.input.openPage(); r.input.select(PhoneHidAction.FUNCTION)
    check(r.input.accept(event(1200, key = 196, scan = 701)))
    check(r.input.accept(event(1210, 1200, repeat = 1, key = 196, scan = 701)))
    r.input.cancelInput()
    check(r.input.accept(event(1220, 1200, PhoneHidPhase.UP, key = 196, scan = 701))) { "retired capture release remains owned" }
    equal(listOf("capture", "submit", "capture", "cancel"), r.calls.map { it.kind }, "cold and capture-only edges add no target output")
    equal(0, r.jobs.size, "canceled capture does not save")
}

private fun passedThroughHoldSurvivesUiAndDeviceTransitionsWithoutLearning() {
    for (boundary in listOf("select", "open", "close", "reset", "removed", "cancel")) {
        val r = Rig(); r.input.openPage()
        check(!r.input.accept(event(1000)))
        when (boundary) {
            "select" -> r.input.select(PhoneHidAction.TAP)
            "open" -> r.input.openPage()
            "close" -> r.input.closePage()
            "reset" -> { r.input.resetAll(); r.flush() }
            "removed" -> { r.input.select(PhoneHidAction.TAP); r.input.deviceRemoved(peripheral.id) }
            else -> r.input.cancelInput()
        }
        check(!r.input.accept(event(1005, 1000))) { "$boundary cannot promote the old duplicate DOWN to handled input" }
        check(!r.input.accept(event(1010, 1000, repeat = 1))) { "$boundary cannot promote the old held repeat" }
        check(!r.input.accept(event(1020, 1000, PhoneHidPhase.UP))) { "$boundary cannot claim the old pass-through UP" }
        check(!r.input.capturing && r.calls.isEmpty()) { "$boundary cannot turn a held pass-through key into capture/output" }
        check(PhoneHidAction.entries.all { r.bindings.binding(it) == null })
        equal(if (boundary == "reset") 1 else 0, r.writes.size, "only explicit reset may write")
    }
    val runtime = routedRuntime(); val input = runtime.controller
    val service = PhoneHidKeyService(); service.onServiceConnected()
    try {
        for ((index, transition) in listOf("changed", "removed", "missing").withIndex()) {
            input.closePage(); input.openPage()
            val device = peripheral.copy(id = 71 + index, descriptor = "phone-pass-through:$transition")
            val key = 274 + index; val scan = 817 + index; val at = 20000L + index * 1000
            PhoneSessionController.fixture.clear()
            check(!serviceEdge(service, at, device = device, key = key, scan = scan))
            input.select(PhoneHidAction.TAP)
            val changed = device.copy(descriptor = "phone-pass-through:$transition:changed")
            when (transition) {
                "changed" -> service.onInputDeviceChanged(device.id)
                "removed" -> service.onInputDeviceRemoved(device.id)
            }
            val oldDevice = if (transition == "changed") changed else null
            check(!serviceEdge(service, at + 5, at, device = oldDevice, key = key, scan = scan,
                attachment = device.id)) { "$transition duplicate raw DOWN must keep pass-through disposition" }
            check(!serviceEdge(service, at + 10, at, device = oldDevice, key = key, scan = scan,
                repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS, attachment = device.id))
            check(!serviceEdge(service, at + 20, at, KeyEvent.ACTION_UP, device = changed, key = key,
                scan = scan, attachment = device.id)) { "$transition release with available changed facts remains false" }
            check(!input.capturing)
            equal(0, input.saving, "old pass-through edges cannot enqueue a learned assignment")
            check(PhoneSessionController.fixture.calls.isEmpty())
            check(serviceEdge(service, at + 100, device = changed, key = key, scan = scan))
            check(serviceEdge(service, at + 120, at + 100, KeyEvent.ACTION_UP, device = changed, key = key, scan = scan))
            awaitSaved(runtime)
            equal(binding(changed, key, scan), input.bindings.binding(PhoneHidAction.TAP), "only next fresh actual DOWN/UP may learn after $transition")
            check(PhoneSessionController.fixture.calls.isEmpty())
        }
    } finally { input.closePage(); service.onDestroy() }
}

fun main() {
    val scenarios = linkedMapOf<String, () -> Unit>(
        "captured DOWN ownership precedes matching-UP feedback" to ::capturePublishesOwnershipBeforeMatchingUpFeedback,
        "nested completed capture and page change supersede outer feedback" to ::newerCaptureCompletionAndPageChangeSupersedeOuterFeedback,
        "normal DOWN retains terminal UP during feedback and route capture" to ::normalDownRetainsMatchingTerminalThroughFeedbackAndRouteConstruction,
        "newer completed shared TAP supersedes outer admission and Function stays independent" to ::newerCompletedSharedTapPreventsOlderOuterRouteAndFunctionRemainsIndependent,
        "feedback cancellation and deferred failed save never replay" to ::cancelDuringNormalFeedbackAndPendingCaptureSaveNeverReplay,
        "reset queues before old cancellation callback completes a newer assignment" to ::resetQueuesBeforeCancelCallbackCompletesNewerAssignment,
        "actual panel/service/runtime persist sticky replacement and capture DOWN once" to ::actualPanelServiceAndAsyncPersistenceRetainStickySelectionAndSingleDownRoutes,
        "actual service correlates changed missing and retired raw releases" to ::actualServiceCorrelatesChangedMissingAndRetiredReleasesBeforeMetadata,
        "actual service excludes native/unknown input and never replays unavailable targets" to ::actualNativeExclusionUnknownKeysAndUnavailableTargetsNeverReplay,
        "actual settings/reset failure and Host lifecycle remain truthful" to ::actualSettingsObservationResetFailureAndLifecycleAreTruthful,
        "ordinary Host stop preserves operational HID and interrupt retires owner" to ::ordinaryHostStopDoesNotCancelOperationalHidAndInterruptRetiresOldOwner,
        "released Function caller route retires at reset page selection connection removal and cancel boundaries" to ::releasedFunctionCallerRouteRetiresAtEveryExplicitBoundary,
        "actual service Function sequence keeps latest released caller route without intermediate cancel" to ::actualServiceFunctionSequenceKeepsOnlyLatestReleasedCallerRoute,
        "queued assignment never claims duplicate repeat or terminal of an earlier false DOWN" to ::queuedAssignmentNeverClaimsAnAlreadyPassedThroughGesture,
        "cold orphan edges stay false while admitted captured and retired edges remain owned" to ::coldOrphanEdgesStayFalseWhileActualOwnedAndRetiredEdgesStayTrue,
        "pass-through hold survives UI and changed removed missing-device transitions without learning" to ::passedThroughHoldSurvivesUiAndDeviceTransitionsWithoutLearning,
    )
    scenarios.putAll(activationScenarios())
    scenarios.putAll(wakeScenarios())
    val failures = mutableListOf<String>()
    try {
        scenarios.forEach { (name, run) ->
            try { run(); println("PASS: $name") }
            catch (failure: Throwable) { failures.add(name); System.err.println("FAIL: $name"); failure.printStackTrace() }
        }
    } finally {
        val runtime = routedRuntime()
        val writer = runtime.javaClass.getDeclaredField("writer").apply { isAccessible = true }.get(runtime) as ExecutorService
        writer.shutdownNow(); writer.awaitTermination(2, TimeUnit.SECONDS); MainQueue.drain()
    }
    check(failures.isEmpty()) { "${failures.size}/${scenarios.size} Phone HID host scenarios failed: ${failures.joinToString()}" }
    println("PASS: ${scenarios.size} Phone HID host scenarios; explicit Android/accessibility/session seam doubles only.")
}

private class WakePort : PhoneHidActivation {
    var needed = true
    val requests = mutableListOf<ActivationCause>()
    var onRequest: () -> Unit = {}
    override fun needsWake() = needed
    override fun request(cause: ActivationCause) { requests.add(cause); onRequest() }
}

private fun wakeOnlyOwnsBoundAndUnboundFullGestureThroughReadiness() {
    for (bound in listOf(false, true)) {
        val port = WakePort(); val r = Rig(port)
        if (bound) r.seed(PhoneHidAction.TAP)
        port.onRequest = { port.needed = false }
        check(r.input.accept(event(1000)))
        check(r.input.accept(event(1005, 1000)))
        check(r.input.accept(event(1010, 1000, repeat = 1)))
        check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
        check(r.input.accept(event(1025, 1000, PhoneHidPhase.CANCEL)))
        equal(listOf(ActivationCause.HID_PRESS), port.requests, "one request for the original physical press")
        check(r.calls.isEmpty())
        equal(bound, r.input.accept(event(1100)), "later ready input preserves ordinary assignment/pass-through")
        equal(bound, r.input.accept(event(1120, 1100, PhoneHidPhase.UP)), "later ready release preserves disposition")
        equal(if (bound) listOf("capture", "submit") else emptyList<String>(), r.calls.map { it.kind }, "wake action was never replayed")
    }
}

private fun wakeRawOwnershipSurvivesOverlappingSameKeyAndMetadataLoss() {
    val port = WakePort(); val r = Rig(port); r.seed(PhoneHidAction.TAP)
    check(r.input.accept(event(1000)))
    port.needed = false
    check(r.input.accept(event(1100)))
    // Same-key later normal DOWN overwrote lastDowns; older wake ownership remains independent.
    check(r.input.consumeUnavailable(41, 195, 700, 1000, PhoneHidPhase.DOWN, 1))
    check(r.input.consumeChanged(41, 195, 700, 1000, PhoneHidPhase.UP, PhoneHidDeviceIdentity("changed", 1, 2)))
    check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP)))
    equal(listOf("capture", "submit"), r.calls.map { it.kind }, "late old wake release leaves newer route alone")
    check(r.calls.all { it.edge!!.downTimeMs == 1100L })
    equal(1, port.requests.size, "metadata loss never requests another activation")
}

private fun wakeReentryAndCancellationNeverRestoreOldInput() {
    for (boundary in listOf("cancel", "removed", "connection", "page")) {
        val port = WakePort(); val r = Rig(port); r.seed(PhoneHidAction.TAP)
        port.onRequest = {
            port.onRequest = {}
            when (boundary) {
                "cancel" -> r.input.cancelInput()
                "removed" -> r.input.deviceRemoved(peripheral.id)
                "connection" -> r.input.connection(false)
                else -> r.input.openPage()
            }
            port.needed = false
            check(r.input.accept(event(1020, 1000, PhoneHidPhase.CANCEL)))
            check(r.input.accept(event(1100)))
            check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP)))
        }
        check(r.input.accept(event(1000)))
        check(r.input.accept(event(1130, 1000, PhoneHidPhase.UP)))
        equal(1, port.requests.size, "$boundary cannot request from old wake edges")
        equal(listOf("capture", "submit"), r.calls.map { it.kind }, "$boundary retains only newer ready input")
        check(r.calls.all { it.edge!!.downTimeMs == 1100L })
    }
    val failed = WakePort(); val r = Rig(failed); r.seed(PhoneHidAction.TAP)
    failed.onRequest = { throw IllegalStateException("local seam request failed") }
    check(r.input.accept(event(1000)))
    check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
    check(r.calls.isEmpty() && failed.requests.size == 1)
}

private fun wakeDuringStickyLearningPreservesConfigurationAndPassedGestures() {
    val port = WakePort(); val r = Rig(port)
    r.input.openPage(); r.input.select(PhoneHidAction.FUNCTION)
    port.onRequest = { port.needed = false; check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP))) }
    check(r.input.accept(event(1000)))
    r.flush()
    equal(binding(), r.bindings.binding(PhoneHidAction.FUNCTION), "disconnected learning still saves its original row")
    equal(PhoneHidAction.FUNCTION, r.input.selected, "sticky selection remains")
    check(r.calls.isEmpty() && port.requests.size == 1)

    val passed = WakePort().apply { needed = false }; val p = Rig(passed)
    check(!p.input.accept(event(2000)))
    passed.needed = true
    check(!p.input.accept(event(2005, 2000)))
    check(!p.input.accept(event(2010, 2000, repeat = 1)))
    check(!p.input.accept(event(2020, 2000, PhoneHidPhase.UP)))
    check(passed.requests.isEmpty())
    check(p.input.accept(event(2100)))
    check(p.input.accept(event(2120, 2100, PhoneHidPhase.UP)))
    equal(1, passed.requests.size, "only the next fresh DOWN may request wake")
}

private fun actualServiceAttachmentAndWakeUseTheExplicitLocalPort() {
    val runtime = routedRuntime(); val input = runtime.controller
    val service = PhoneHidKeyService(); val port = WakePort()
    input.closePage(); input.cancelInput(); input.activation = port
    service.onServiceConnected()
    try {
        Environment.inputManager.install(peripheral)
        service.onInputDeviceAdded(peripheral.id)
        for (excluded in listOf(peripheral.copy(id = 81, isExternal = false),
            peripheral.copy(id = 82, isVirtual = true), peripheral.copy(id = 83, name = "ROKID,PSOC-TP-R"),
            peripheral.copy(id = 84, name = "qpnp_pon"))) {
            Environment.inputManager.install(excluded)
            service.onInputDeviceAdded(excluded.id)
        }
        service.onInputDeviceAdded(999)
        service.onInputDeviceChanged(peripheral.id)
        equal(listOf(ActivationCause.HID_ATTACHMENT), port.requests, "only actual eligible attachment requests activation")
        PhoneSessionController.fixture.clear()
        val key = 391; val scan = 901; val at = 65000L
        check(serviceEdge(service, at, key = key, scan = scan))
        port.needed = false
        check(serviceEdge(service, at + 10, at, device = null, key = key, scan = scan, repeat = 1, attachment = peripheral.id))
        service.onInputDeviceRemoved(peripheral.id)
        check(serviceEdge(service, at + 20, at, KeyEvent.ACTION_UP, device = null, key = key, scan = scan, attachment = peripheral.id))
        equal(listOf(ActivationCause.HID_ATTACHMENT, ActivationCause.HID_PRESS), port.requests, "missing/removed facts keep original wake disposition")
        check(!serviceEdge(service, at + 100, key = key, scan = scan))
        check(!serviceEdge(service, at + 120, at + 100, KeyEvent.ACTION_UP, key = key, scan = scan))
        check(PhoneSessionController.fixture.calls.isEmpty())
    } finally { input.activation = PhoneHidActivation.Unwired; service.onDestroy() }
}

private fun wakeScenarios(): Map<String, () -> Unit> = linkedMapOf(
    "wake-only bound/unbound DOWN owns repeat and terminal through readiness" to ::wakeOnlyOwnsBoundAndUnboundFullGestureThroughReadiness,
    "wake raw ownership survives overlapping same-key DOWN and missing/changed device facts" to ::wakeRawOwnershipSurvivesOverlappingSameKeyAndMetadataLoss,
    "wake request reentry cancellation and failure never restore old target input" to ::wakeReentryAndCancellationNeverRestoreOldInput,
    "newer wake retires older normal constructing and released Function routes" to ::newerWakeRetiresOldNormalAndConstructingOwners,
    "wake permits sticky configuration while preserving an old passed gesture" to ::wakeDuringStickyLearningPreservesConfigurationAndPassedGestures,
    "actual service attachment and raw wake ingress use an explicit unwired local integration port" to ::actualServiceAttachmentAndWakeUseTheExplicitLocalPort,
)


private fun newerWakeRetiresOldNormalAndConstructingOwners() {
    for (action in listOf(PhoneHidAction.TAP, PhoneHidAction.FUNCTION)) {
        val port = WakePort().apply { needed = false }; val r = Rig(port); r.seed(action)
        check(r.input.accept(event(1000)))
        if (action == PhoneHidAction.FUNCTION) check(r.input.accept(event(1020, 1000, PhoneHidPhase.UP)))
        port.needed = true
        check(r.input.accept(event(1100)))
        check(r.input.accept(event(1120, 1000, PhoneHidPhase.UP)))
        check(r.input.accept(event(1130, 1100, PhoneHidPhase.UP)))
        equal(if (action == PhoneHidAction.FUNCTION) listOf("capture", "submit", "cancel") else listOf("capture", "cancel"),
            r.calls.map { it.kind }, "wake supersedes exact old $action, including its quiet recognition")
        equal(1, port.requests.size, "one fresh wake request")
    }
    val port = WakePort().apply { needed = false }; val r = Rig(port); r.seed(PhoneHidAction.TAP)
    r.onCapture = {
        r.onCapture = {}
        port.needed = true
        check(r.input.accept(event(1100)))
        port.needed = false
        check(r.input.accept(event(1120, 1100, PhoneHidPhase.UP)))
    }
    check(r.input.accept(event(1000)))
    check(r.input.accept(event(1200, 1000, PhoneHidPhase.UP)))
    equal(listOf("capture", "cancel"), r.calls.map { it.kind }, "constructing original route is canceled on return, never completed after wake")
    check(r.calls[0].route === r.calls[1].route)

    val early = WakePort().apply { needed = false }; val before = Rig(early); before.seed(PhoneHidAction.TAP)
    before.input.openPage()
    before.once { early.needed = true; check(before.input.accept(event(1100))) }
    check(before.input.accept(event(1000)))
    check(before.input.accept(event(1200, 1000, PhoneHidPhase.UP)))
    check(before.calls.isEmpty()) // Newer wake retired the outer normal owner before route construction.
    equal(1, early.requests.size, "constructing owner did not restore a route")

    val original = WakePort().apply { needed = false }; val captured = Rig(original); captured.seed(PhoneHidAction.TAP)
    val replacementPort = WakePort()
    check(captured.input.accept(event(1500)))
    original.needed = true
    captured.onCancel = { captured.input.activation = replacementPort }
    check(captured.input.accept(event(1600)))
    check(captured.input.accept(event(1620, 1600, PhoneHidPhase.UP)))
    equal(1, original.requests.size, "wake retains its exact original local activation port")
    check(replacementPort.requests.isEmpty())

    for (cancelCallback in listOf("up", "stop", "newer")) {
        val reentrant = WakePort().apply { needed = false }; val s = Rig(reentrant); s.seed(PhoneHidAction.TAP)
        check(s.input.accept(event(2000)))
        reentrant.needed = true
        s.onCancel = {
            s.onCancel = {}
            check(s.input.accept(event(2120, 2100, PhoneHidPhase.UP)))
            if (cancelCallback == "stop") s.input.cancelInput()
            if (cancelCallback == "newer") {
                reentrant.needed = false
                check(s.input.accept(event(2200)))
                check(s.input.accept(event(2220, 2200, PhoneHidPhase.UP)))
            }
        }
        check(s.input.accept(event(2100)))
        check(s.calls.none { it.kind == "submit" && it.edge!!.downTimeMs == 2000L })
        equal(if (cancelCallback == "up") 1 else 0, reentrant.requests.size,
            "old cancellation $cancelCallback reentry cannot restart a retired wake request")
    }
}
