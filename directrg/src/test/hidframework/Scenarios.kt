package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.PriorityQueue

private fun equal(expected: Any?, actual: Any?, reason: String) {
    check(expected == actual) { "$reason: expected <$expected>, actual <$actual>" }
}

private val visibleHidRoles = listOf(HidAction.TAP, HidAction.SCROLL_DOWN, HidAction.SCROLL_UP, HidAction.FUNCTION)

private class Rig(initialRecord: String? = null, save: Boolean = true) {
    data class Timer(val deadline: Long, val order: Int, val callback: () -> Unit, var canceled: Boolean = false)
    data class Contact(val owner: Any, val event: PadGestureRecognizer.Event)
    data class Cancel(val owner: Any, val time: Long)
    data class Scroll(val pixels: Int, val point: PointerPosition, val time: Long)
    var now = 1000L
    var context: Any? = "local-bindings-view"
    var record = initialRecord
    var writable = save
    val writes = mutableListOf<String>()
    var panel: HidBindingsPanel? = null
    var backs = 0
    var changed = 0
    var onChanged: () -> Unit = {}
    val android = Context()
    val root = LinearLayout(android).apply {
        hostVisibleRect = Rect(50, 80, 1050, 2080)
        hostGlobalOffsetX = 50
        hostGlobalOffsetY = 80
    }
    val timers = mutableListOf<Timer>()
    private val queue = PriorityQueue<Timer>(compareBy<Timer> { it.deadline }.thenBy { it.order })
    val contacts = mutableListOf<Contact>()
    val cancels = mutableListOf<Cancel>()
    val scrolls = mutableListOf<Scroll>()
    val functions = mutableListOf<com.code2hack.eyeprojector.protocol.FunctionIntentKind>()
    var point = PointerPosition(100f, 200f, true)
    var targetReads = 0
    var positionReads = 0
    var connectionVisible = true
    var receivedCalls = 0
    var onReceived: (HidKeyInput.Event) -> Unit = {}
    var receivedRoute: ((HidKeyInput.Event) -> Unit)? = null
    var captureTarget: (HidDecisionTrace.Event?) -> HidAction? = { trace -> panel?.actionAt(point, root, trace) }
    val bindings = HidBindings({ record }) { writes.add(it); if (writable) record = it; writable }
    val input = HidKeyInput(bindings, { now }, { context }, { at, callback ->
        val timer = Timer(at, timers.size, callback)
        timers.add(timer); queue.add(timer)
        val cancel: () -> Unit = { timer.canceled = true }
        cancel
    }, { positionReads++; point }, { owner, event -> contacts.add(Contact(owner, event)) },
        { owner, at -> cancels.add(Cancel(owner, at)) },
        { pixels, position, at -> scrolls.add(Scroll(pixels, position, at)) },
        { functions.add(it.kind) }, { changed++; panel?.refresh(); onChanged() }, { trace -> targetReads++; captureTarget(trace) })
    val adapter = AndroidHidInput(android, input) { event ->
        receivedCalls++
        val route = receivedRoute
        if (route != null) {
            onReceived(event)
            route(event)
        } else if (panel?.visibility == View.VISIBLE && connectionVisible) {
            onReceived(event)
            panel?.received(event)
        }
    }
    val device = InputDevice(41, "actual:descriptor:17", 0x1209, 0x0017, "Actual HID peripheral")

    fun showPanel(): HidBindingsPanel = HidBindingsPanel(android, bindings, input) { backs++ }.also {
        panel = it
        root.addView(it)
        it.hostVisibleRect = Rect(350, 1080, 950, 1670)
        it.buttons.forEachIndexed { index, button ->
            button.hostVisibleRect = Rect(350, 1080 + index * 100, 950, 1170 + index * 100)
        }
    }
    fun hover(action: HidAction): Boolean {
        if (panel == null) showPanel()
        point = PointerPosition(400f, 1040f + action.ordinal * 100, true)
        return true
    }
    fun leaveRows() { point = PointerPosition(100f, 200f, true) }
    fun binding(device: InputDevice = this.device, key: Int = 195, scan: Int = 700): HidBinding = HidBinding(
        HidButton(HidDeviceIdentity(device.descriptor, device.vendorId, device.productId), key, scan),
        device.name, KeyEvent.keyCodeToString(key),
    )
    fun assign(action: HidAction, key: Int = 195, scan: Int = 700, device: InputDevice = this.device) {
        check(bindings.assign(action, binding(device, key, scan)))
    }
    fun edge(action: Int, at: Long = now, down: Long = at, key: Int = 195, scan: Int = 700,
             repeat: Int = 0, flags: Int = 0, device: InputDevice? = this.device,
             attachmentId: Int = device?.id ?: -1): Boolean {
        now = at
        return adapter.accept(KeyEvent(device, action, key, scan, down, at, repeat, flags, attachmentId))
    }
    fun advance(to: Long) {
        check(to >= now)
        while (queue.peek()?.deadline?.let { it <= to } == true) {
            val timer = queue.remove()
            if (!timer.canceled) {
                now = maxOf(now, timer.deadline)
                timer.canceled = true
                timer.callback()
            }
        }
        now = to
    }
    fun noOutput() {
        check(contacts.isEmpty() && cancels.isEmpty() && scrolls.isEmpty() && functions.isEmpty())
    }
    val message get() = (panel!!.children[1] as TextView).text.toString()
    val receivedText get() = (panel!!.children[2] as TextView).text.toString()
}

private fun descendants(view: android.view.View): List<android.view.View> =
    listOf(view) + if (view is LinearLayout) view.children.flatMap(::descendants) else emptyList()

private fun resetControl(panel: HidBindingsPanel): Button =
    checkNotNull(panel.buttons.singleOrNull { it.text.toString() == "Reset all HID bindings" }) {
        "missing single visible Reset all HID bindings control"
    }

// Recorded USB379 input inventory and saved binding facts, replayed only as host metadata.
private val nativePad379 = InputDevice(3, "e2d539294d58ad2f9f9b376dfde7ee6310670d2b", 0, 0,
    "ROKID,PSOC-TP-R", isExternal = false)
private val nativePower379 = InputDevice(2, "fb60d4f4370f5dbe8267b63d38dea852987571ab", 0, 0,
    "qpnp_pon", isExternal = false)
private val virtual379 = InputDevice(-1, "a718a782d34bc767f4689c232d64d527998ea7fd", 0, 0,
    "Virtual", isExternal = false, isVirtual = true)
private val joyCon379 = InputDevice(5, "970d9eedc8314011eae0438faa2afda1d6b2b5c5", 1406, 8199,
    "Nintendo Switch Right Joy-Con", isExternal = true)
private val joyConButtons379 = linkedMapOf(
    HidAction.TAP to Pair(103, 311), HidAction.SCROLL_DOWN to Pair(96, 304), HidAction.SCROLL_UP to Pair(99, 307),
)

private fun actual379Record(): String {
    val r = Rig()
    for ((action, codes) in joyConButtons379) {
        val label = when (action) {
            HidAction.TAP -> "KEYCODE_BUTTON_R1"
            HidAction.SCROLL_DOWN -> "KEYCODE_BUTTON_A"
            HidAction.SCROLL_UP -> "KEYCODE_BUTTON_X"
            else -> error("unexpected Joy-Con assignment")
        }
        check(r.bindings.assign(action, r.binding(joyCon379, codes.first, codes.second).copy(keyLabel = label)))
    }
    check(r.bindings.assign(HidAction.FUNCTION,
        r.binding(nativePad379, 83, 204).copy(keyLabel = "KEYCODE_NOTIFICATION")))
    return checkNotNull(r.record)
}

private fun assertJoyCon379Preserved(r: Rig) {
    val restored = HidBindings({ r.record }) { error("readback does not mutate saved bindings") }
    for ((action, codes) in joyConButtons379) {
        val current = checkNotNull(r.bindings.binding(action)) { "$action Joy-Con assignment was removed" }
        equal(HidButton(HidDeviceIdentity(joyCon379.descriptor, 1406, 8199), codes.first, codes.second),
            current.button, "$action retains actual USB379 peripheral identity")
        equal(current, restored.binding(action), "$action retains persisted assignment")
    }
}

private fun capturesActualFactsAndConsumesWholeGesture() {
    val r = Rig()
    val panel = r.showPanel()
    check(r.hover(visibleHidRoles[0]))
    equal(null, r.input.selected, "hover alone does not start capture")
    check(!panel.buttons[0].performClick()) { "assignment row has no sticky click selection" }
    check(r.edge(KeyEvent.ACTION_DOWN, key = 194, scan = 813))
    equal(HidAction.TAP, r.input.selected, "actual DOWN captures the hovered Tap row")
    check(panel.buttons[0].isSelected)
    equal("Release the external HID button for Tap.", r.message, "active capture instruction names the pinned action")
    check(r.bindings.binding(HidAction.TAP) == null)
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, down = 1000, key = 194, scan = 813,
        repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS))
    check(r.edge(KeyEvent.ACTION_UP, at = 1200, down = 1000, key = 194, scan = 813,
        device = r.device.copy(name = "Release display metadata")))
    equal(r.binding(key = 194, scan = 813), r.bindings.binding(HidAction.TAP), "actual DOWN facts saved")
    check(r.record != null)
    equal(null, r.input.selected, "complete release exits capture")
    equal("Tap: Actual HID peripheral: HOST_KEYCODE_194 (key 194, scan 813)",
        panel.buttons[0].text.toString(), "saved assignment display")
    check(!panel.buttons[0].isSelected)
    r.advance(5000)
    r.noOutput()
    check(r.timers.isEmpty())
    val restored = HidBindings({ r.record }) { error("readback must not write") }
    equal(r.binding(key = 194, scan = 813), restored.binding(HidAction.TAP), "saved identity readback")
}

private fun repeatsAndLongFlagStayOneHeldContact() {
    val r = Rig()
    r.assign(HidAction.TAP)
    check(r.edge(KeyEvent.ACTION_DOWN))
    val repeat = KeyEvent(r.device, KeyEvent.ACTION_DOWN, 195, 700, 1000, 1100, 1, KeyEvent.FLAG_LONG_PRESS)
    check(repeat.isLongPress)
    r.now = repeat.eventTime
    check(r.adapter.accept(repeat))
    // Some senders repeat the long flag with repeatCount == 0; same DOWN identity still owns it.
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, down = 1000, flags = KeyEvent.FLAG_LONG_PRESS))
    r.advance(30000)
    equal(listOf(PadGestureRecognizer.Phase.DOWN), r.contacts.map { it.event.phase }, "held reports")
    check(r.cancels.isEmpty())
    check(r.edge(KeyEvent.ACTION_UP, at = 30100, down = 1000))
    equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
        r.contacts.map { it.event.phase }, "actual final release")
    check(r.contacts[0].owner === r.contacts[1].owner)
    equal(listOf(1000L, 30100L), r.contacts.map { it.event.timeMs }, "actual event times")
    check(r.contacts.all { it.event.downTimeMs == 1000L && it.event.key == PadGestureRecognizer.Key.HELD_CONTACT })
    check(r.cancels.isEmpty() && r.scrolls.isEmpty() && r.functions.isEmpty() && r.timers.isEmpty())
    // Unbound and unavailable-device input remains available to Activity's existing dispatch.
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 30200, key = 4, scan = 158))
    check(!r.edge(KeyEvent.ACTION_UP, at = 30220, down = 30200, device = null))
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 30300, device = r.device.copy(descriptor = "other peripheral")))
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 30300, scan = 701))
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 30300, device = r.device.copy(vendorId = 99)))
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 30300, device = r.device.copy(productId = 99)))
}

private fun canceledAndUnsupportedAndroidEventsDoNotReleaseIntoTargets() {
    for (canceled in listOf(true, false)) {
        val r = Rig()
        r.assign(HidAction.TAP)
        check(r.edge(KeyEvent.ACTION_DOWN))
        check(r.edge(if (canceled) KeyEvent.ACTION_UP else KeyEvent.ACTION_MULTIPLE,
            at = 1020, down = 1000, flags = if (canceled) KeyEvent.FLAG_CANCELED else 0))
        equal(1, r.contacts.size, "only real DOWN reaches contact callback")
        equal(1, r.cancels.size, "Android cancel/unsupported phase retires owned contact")
        check(r.contacts.single().owner === r.cancels.single().owner)
        check(r.edge(KeyEvent.ACTION_UP, at = 1030, down = 1000))
        equal(1, r.contacts.size, "late release stays orphaned")
    }
}

private fun listenerLifecycleCancelsAndReconnectRetainsBindings() {
    val r = Rig()
    r.assign(HidAction.TAP)
    r.assign(HidAction.FUNCTION, key = 196, scan = 701)
    val stored = r.record
    val manager = r.android.inputManager
    r.adapter.start(); r.adapter.start()
    equal(1, manager.registrations, "idempotent start")
    equal(1, manager.listenerCount, "one registered listener")
    check(manager.lastHandler!!.looper === Looper.getMainLooper())
    check(r.edge(KeyEvent.ACTION_DOWN))
    manager.add(r.device.id)
    check(r.cancels.isEmpty())
    manager.change(r.device.id)
    equal(1, r.cancels.size, "changed attachment cancels held route")
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
    equal(1, r.contacts.size, "changed attachment cannot finish old press")
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 196, scan = 701))
    val stale = r.timers.toList()
    manager.remove(r.device.id)
    stale.forEach { it.callback() }
    r.advance(2000)
    check(r.functions.isEmpty())
    equal(stored, r.record, "listener changes retain persisted bindings")
    val reconnect = r.device.copy(id = 93, name = "Same peripheral after reconnect")
    manager.add(reconnect.id)
    check(r.edge(KeyEvent.ACTION_UP, at = 2010, down = 1000, device = reconnect))
    equal(1, r.contacts.size, "new attachment cannot release old hold")
    check(r.edge(KeyEvent.ACTION_DOWN, at = 2100, device = reconnect))
    check(r.edge(KeyEvent.ACTION_UP, at = 2120, down = 2100, device = reconnect))
    equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
        r.contacts.map { it.event.phase }, "same persisted identity accepts a fresh reconnect press")
    check(r.contacts[1].owner === r.contacts[2].owner)
    check(r.contacts[0].owner !== r.contacts[1].owner)
    check(r.edge(KeyEvent.ACTION_DOWN, at = 2200, device = reconnect))
    r.adapter.stop(); r.adapter.stop()
    equal(2, r.cancels.size, "stop cancels current held route only once")
    equal(1, manager.unregistrations, "idempotent stop")
    equal(0, manager.listenerCount, "listener released")
    r.adapter.start()
    equal(2, manager.registrations, "restart registers fresh listener")
    r.adapter.stop()
    equal(2, manager.unregistrations, "restart listener released")
    equal(stored, r.record, "stop does not erase saved assignments")
}

private fun actualFunctionAndScrollCallbacksUseAndroidEdges() {
    for (presses in 1..3) {
        val r = Rig()
        r.assign(HidAction.FUNCTION)
        repeat(presses) { index ->
            val down = 1000L + index * 100
            check(r.edge(KeyEvent.ACTION_DOWN, at = down))
            check(r.edge(KeyEvent.ACTION_DOWN, at = down + 5, down = down, repeat = 1,
                flags = KeyEvent.FLAG_LONG_PRESS))
            check(r.edge(KeyEvent.ACTION_UP, at = down + 20, down = down))
            equal(List(index + 1) { com.code2hack.eyeprojector.protocol.FunctionIntentKind.ACTIVATE }, r.functions,
                "each actual Function UP completes one activation")
        }
        r.advance(r.now + 500)
        equal(List(presses) { com.code2hack.eyeprojector.protocol.FunctionIntentKind.ACTIVATE }, r.functions,
            "multiple Function edges retain every activation")
        check(r.contacts.isEmpty() && r.cancels.isEmpty() && r.scrolls.isEmpty())
    }
    val r = Rig()
    r.assign(HidAction.SCROLL_DOWN)
    r.assign(HidAction.SCROLL_UP, key = 196, scan = 701)
    check(r.edge(KeyEvent.ACTION_DOWN))
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, down = 1000, repeat = 2, flags = KeyEvent.FLAG_LONG_PRESS))
    check(r.scrolls.isEmpty())
    check(r.edge(KeyEvent.ACTION_UP, at = 1200, down = 1000))
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1300, key = 196, scan = 701))
    check(r.edge(KeyEvent.ACTION_UP, at = 1320, down = 1300, key = 196, scan = 701))
    equal(listOf(Rig.Scroll(160, r.point, 1200), Rig.Scroll(-160, r.point, 1320)), r.scrolls, "scroll callback facts")
    check(r.contacts.isEmpty() && r.cancels.isEmpty() && r.functions.isEmpty() && r.timers.isEmpty())
}

private fun panelShowsFourAssignmentsWithExistingBlackLightControls() {
    val r = Rig()
    val panel = r.showPanel()
    equal(LinearLayout.VERTICAL, panel.orientation, "vertical controls")
    equal(Color.BLACK, panel.backgroundColor, "black panel")
    equal("HID key bindings", (panel.children[0] as TextView).text.toString(), "panel title")
    equal(22f, (panel.children[0] as TextView).textSize, "panel title size")
    equal(listOf("Tap: Unassigned", "Scroll down: Unassigned", "Scroll up: Unassigned", "Function button: Unassigned",
        "Reset all HID bindings", "Back"), panel.buttons.map { it.text.toString() }, "visible assignment and reset labels")
    equal(6, panel.buttons.size, "four assignments, one reset and Back registered")
    for (deferred in listOf(HidAction.BACK_HOME, HidAction.LOCK)) {
        r.hover(deferred)
        equal(null, panel.actionAt(r.point, r.root), "$deferred has no assignment row before V03-06")
    }
    check(panel.buttons.none { it.text.toString().startsWith("Back/Home:") || it.text.toString().startsWith("Lock:") })
    equal(panel.buttons, panel.children.drop(3), "all six controls follow title, instructions and reserved received status")
    val received = panel.children[2] as TextView
    equal("", received.text.toString(), "received status starts empty")
    equal(1, received.maxLines, "received status reserves a single line")
    val receivedLayout = checkNotNull(panel.childLayouts[received])
    equal(-1, receivedLayout.width, "received status is full width")
    check(receivedLayout.height > 0) { "received status has a fixed positive height" }
    check(panel.buttons.none { it.text.toString().startsWith("Clear ") }) { "per-action Clear controls removed" }
    equal("Point at an action, then press and release an external HID button.", r.message, "initial hover learning instruction")
    equal(descendants(panel).filterIsInstance<Button>().toSet(), panel.buttons.toSet(),
        "every interactive button registered for existing local hit testing")
    equal(panel.buttons.size, panel.buttons.toSet().size, "interactive controls registered once")
    for (child in descendants(panel).filterIsInstance<TextView>()) equal(Color.WHITE, child.textColor, "light text")
    for (button in panel.buttons) {
        equal(false, button.isAllCaps, "normal-case readable control text")
        val layout = checkNotNull(panel.childLayouts[button]) { "full-width button layout missing" }
        equal(-1, layout.width, "full-width control")
        equal(-2, layout.height, "content-height control")
        val background = button.background as GradientDrawable
        equal(Color.BLACK, background.fillColor, "black control")
        equal(1, background.strokeWidth, "control border width")
        equal(Color.WHITE, background.strokeColor, "light control border")
        equal(96, button.minimumHeight, "48dp minimum at host density two")
    }
    for (action in visibleHidRoles) {
        check(!panel.buttons[action.ordinal].performClick()) { "assignment row does not latch capture on click" }
        check(r.hover(action))
        equal(null, r.input.selected, "hover alone remains unselected")
        val at = 1100L + action.ordinal * 100
        check(r.edge(KeyEvent.ACTION_DOWN, at = at, key = 210 + action.ordinal, scan = 800 + action.ordinal))
        equal(action, r.input.selected, "actual DOWN captures hovered action")
        equal(listOf(action.ordinal), panel.buttons.indices.filter { panel.buttons[it].isSelected }, "selected capture display")
        check(r.edge(KeyEvent.ACTION_UP, at = at + 20, down = at, key = 210 + action.ordinal, scan = 800 + action.ordinal))
        equal(null, r.input.selected, "actual UP ends capture")
    }
    check(panel.buttons.last().performClick())
    equal(1, r.backs, "Back forwards callback")
    r.noOutput()
}

private fun panelSavedMoveAndErrorsRefreshFromActualStore() {
    val r = Rig()
    val panel = r.showPanel()
    r.hover(visibleHidRoles[0])
    check(r.edge(KeyEvent.ACTION_DOWN))
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
    r.hover(visibleHidRoles[1])
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100))
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100))
    equal(null, r.bindings.binding(HidAction.TAP), "one button moves out of former assignment")
    equal(r.binding(), r.bindings.binding(HidAction.SCROLL_DOWN), "moved assignment stored")
    equal("Tap: Unassigned", panel.buttons[0].text.toString(), "former UI assignment clears")
    equal("Scroll down: Actual HID peripheral: HOST_KEYCODE_195 (key 195, scan 700)",
        panel.buttons[1].text.toString(), "moved assignment display")
    val restarted = Rig(r.record)
    val restartedPanel = restarted.showPanel()
    equal(panel.buttons.map { it.text.toString() }, restartedPanel.buttons.map { it.text.toString() }, "restart display readback")
    r.noOutput()

    val malformed = Rig("malformed existing bindings")
    malformed.showPanel()
    equal("Saved HID bindings could not be read. Assign a button or reset all bindings to replace them.", malformed.message, "read error display")
    val failed = Rig(save = false)
    val failedPanel = failed.showPanel()
    failed.hover(visibleHidRoles[3])
    check(failed.edge(KeyEvent.ACTION_DOWN))
    check(failed.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
    equal("HID bindings could not be saved. Try again.", failed.message, "save error display")
    equal("Function button: Unassigned", failedPanel.buttons[3].text.toString(), "failed assignment is not displayed as saved")
    equal(null, failed.record, "failed write leaves persistence unchanged")
    failed.noOutput()
}

private fun attachmentCancellationRetainsCapturedUnboundReleaseOwnership() {
    for (remove in listOf(false, true)) {
        val r = Rig()
        val panel = r.showPanel()
        r.adapter.start()
        r.hover(visibleHidRoles[0])
        // An otherwise unbound Back key is owned by the capture through its complete
        // gesture. A queued release after cancellation must not escape to Activity Back.
        check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
        if (remove) r.android.inputManager.remove(r.device.id) else r.android.inputManager.change(r.device.id)
        equal(null, r.input.selected, "attachment cancellation retires capture selection")
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1010, down = 1000, key = 4, scan = 158, repeat = 1,
            flags = KeyEvent.FLAG_LONG_PRESS)) { "canceled capture must still consume its queued repeated DOWN after attachment ${if (remove) "removal" else "change"}" }
        check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158)) {
            "canceled capture must consume its queued unbound Back release after attachment ${if (remove) "removal" else "change"}"
        }
        equal(null, r.bindings.binding(HidAction.TAP), "canceled capture does not assign")
        r.noOutput()
        r.adapter.stop()
    }
    val fresh = Rig()
    val panel = fresh.showPanel()
    fresh.adapter.start()
    fresh.hover(visibleHidRoles[0])
    check(fresh.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    fresh.android.inputManager.remove(fresh.device.id)
    // Removal can lose the old UP entirely. A fresh real DOWN on a reused runtime ID
    // must still begin and complete a new capture of the same persistent button.
    val reattached = fresh.device.copy(name = "Reattached peripheral, reused Android ID")
    fresh.android.inputManager.add(reattached.id)
    fresh.hover(visibleHidRoles[2])
    check(fresh.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158, device = reattached))
    check(fresh.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158, device = reattached))
    equal(fresh.binding(reattached, 4, 158), fresh.bindings.binding(HidAction.SCROLL_UP), "reused attachment admits a fresh capture")
    equal(null, fresh.input.selected, "fresh reused-ID capture completes")
    fresh.noOutput()
    fresh.adapter.stop()
}

private fun missingDeviceAfterRemovalConsumesOnlyActualCapturedEdges() {
    val r = Rig()
    val panel = r.showPanel()
    r.adapter.start()
    r.hover(visibleHidRoles[0])
    check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    r.android.inputManager.remove(r.device.id)
    // Android lookup may return no InputDevice while the queued event still carries
    // the original runtime device ID/key/scan/downTime. No descriptor is supplied here.
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1010, down = 1000, key = 4, scan = 158, repeat = 1,
        flags = KeyEvent.FLAG_LONG_PRESS, device = null, attachmentId = r.device.id))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1011, down = 1000, key = 5, scan = 158,
        device = null, attachmentId = r.device.id))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1012, down = 1000, key = 4, scan = 159,
        device = null, attachmentId = r.device.id))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1013, down = 1000, key = 4, scan = 158,
        device = null, attachmentId = r.device.id + 1))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1014, down = 999, key = 4, scan = 158,
        device = null, attachmentId = r.device.id))
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158,
        device = null, attachmentId = r.device.id))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1021, down = 1000, key = 4, scan = 158,
        device = null, attachmentId = r.device.id))
    equal(null, r.bindings.binding(HidAction.TAP), "removed capture never assigns invented device facts")
    r.noOutput()

    // The same attachment ID can be reused. Only a new event with actual device facts
    // begins capture and later ordinary Function dispatch.
    val reattached = r.device.copy(name = "Actual device after readdition")
    r.android.inputManager.add(reattached.id)
    r.hover(visibleHidRoles[3])
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1050, key = 4, scan = 158,
        device = null, attachmentId = reattached.id))
    equal(null, r.input.selected, "unowned missing-device DOWN cannot begin capture")
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158, device = reattached))
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158, device = reattached))
    equal(r.binding(reattached, 4, 158), r.bindings.binding(HidAction.FUNCTION), "fresh readdition facts bind")
    r.noOutput()
    r.leaveRows()
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, key = 4, scan = 158, device = reattached))
    check(r.edge(KeyEvent.ACTION_UP, at = 1220, down = 1200, key = 4, scan = 158, device = reattached))
    r.advance(1720)
    equal(listOf(com.code2hack.eyeprojector.protocol.FunctionIntentKind.ACTIVATE), r.functions, "fresh readdition dispatch works")
    check(r.contacts.isEmpty() && r.cancels.isEmpty() && r.scrolls.isEmpty())
    r.adapter.stop()
}

private fun missingDeviceBeforeListenerCancelsCapturedOwnerWithoutTargetUp() {
    val held = Rig()
    held.assign(HidAction.TAP)
    check(held.edge(KeyEvent.ACTION_DOWN))
    val owner = held.contacts.single().owner
    check(held.input.owns(owner))
    // No InputManager callback has run yet. The captured DOWN facts identify the
    // real existing lease; loss of lookup cancels it instead of inventing a release.
    check(held.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000,
        device = null, attachmentId = held.device.id))
    equal(listOf(PadGestureRecognizer.Phase.DOWN), held.contacts.map { it.event.phase }, "missing-device release never reaches target UP")
    equal(listOf(Rig.Cancel(owner, 1020)), held.cancels, "same owner is canceled at current uptime")
    check(!held.input.owns(owner))
    check(!held.edge(KeyEvent.ACTION_DOWN, at = 1100, device = null, attachmentId = held.device.id))
    check(!held.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, device = null, attachmentId = held.device.id))
    equal(1, held.contacts.size, "missing-device fresh edges cannot admit a held route")
    check(held.scrolls.isEmpty() && held.functions.isEmpty())

    val captured = Rig()
    captured.hover(visibleHidRoles[0])
    check(captured.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    check(captured.edge(KeyEvent.ACTION_DOWN, at = 1010, down = 1000, key = 4, scan = 158, repeat = 1,
        device = null, attachmentId = captured.device.id))
    equal(null, captured.input.selected, "missing device cancels existing capture")
    check(captured.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158,
        device = null, attachmentId = captured.device.id))
    equal(null, captured.bindings.binding(HidAction.TAP), "incomplete missing-device capture is not saved")
    captured.noOutput()
}

private fun changedDescriptorDoesNotDisownCapturedOldRelease() {
    val r = Rig()
    val panel = r.showPanel()
    r.adapter.start()
    r.hover(visibleHidRoles[0])
    check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    r.android.inputManager.change(r.device.id)
    val changed = r.device.copy(descriptor = "actually:changed:descriptor", vendorId = 99, productId = 100,
        name = "Actual changed device facts")
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158, device = changed))
    equal(null, r.bindings.binding(HidAction.TAP), "old canceled release cannot capture new descriptor")
    r.noOutput()
    r.hover(visibleHidRoles[0])
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158, device = changed))
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158, device = changed))
    equal(r.binding(changed, 4, 158), r.bindings.binding(HidAction.TAP), "fresh DOWN captures actual changed facts")
    r.noOutput()
    r.adapter.stop()
}

private fun changedFactsBeforeListenerRetireOnlyTheCapturedGesture(
    changeFacts: (InputDevice) -> InputDevice,
    changedEdge: String,
) {
    val r = Rig()
    val panel = r.showPanel()
    r.adapter.start()
    r.hover(visibleHidRoles[0])
    // These actual runtime facts belong to an otherwise unbound Back press.
    check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    val changed = changeFacts(r.device)
    // Deliberately do not invoke InputManager.change/remove first. Android can
    // supply changed persistent facts on the queued event before its listener runs.
    val consumed = when (changedEdge) {
        "UP" -> r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158, device = changed)
        "repeat" -> r.edge(KeyEvent.ACTION_DOWN, at = 1010, down = 1000, key = 4, scan = 158,
            repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS, device = changed)
        "canceled UP" -> r.edge(KeyEvent.ACTION_UP, at = 1010, down = 1000, key = 4, scan = 158,
            flags = KeyEvent.FLAG_CANCELED, device = changed)
        "unsupported phase" -> r.edge(KeyEvent.ACTION_MULTIPLE, at = 1010, down = 1000, key = 4, scan = 158,
            device = changed)
        else -> error("unknown changed-facts edge $changedEdge")
    }
    check(consumed) { "active captured Back $changedEdge must be consumed before the changed-device listener" }
    equal(null, r.input.selected, "changed persistent facts retire active capture selection")
    equal(null, r.record, "changed-facts edge must not save original or changed identity")
    check(HidAction.entries.all { r.bindings.binding(it) == null })
    if (changedEdge != "UP") {
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1015, down = 1000, key = 4, scan = 158,
            repeat = 2, flags = KeyEvent.FLAG_LONG_PRESS, device = changed)) {
            "retired capture still owns its changed-facts repeat"
        }
        check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158, device = changed)) {
            "retired capture still owns its changed-facts final release"
        }
    }
    r.advance(1050)
    r.noOutput()
    equal(null, r.record, "retired capture never emits a delayed assignment")

    // The same Android attachment can now present actual new persistent facts.
    // A new real DOWN, after the user selects capture again, owns those new facts.
    r.hover(visibleHidRoles[3])
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158, device = changed))
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158, device = changed))
    equal(r.binding(changed, 4, 158), r.bindings.binding(HidAction.FUNCTION), "fresh DOWN captures actual changed facts")
    equal(null, r.bindings.binding(HidAction.TAP), "retired original capture stays unassigned")
    equal(null, r.input.selected, "fresh changed-facts capture completes")
    r.noOutput()
    r.leaveRows()
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, key = 4, scan = 158, device = changed))
    check(r.edge(KeyEvent.ACTION_UP, at = 1220, down = 1200, key = 4, scan = 158, device = changed))
    r.advance(1720)
    equal(listOf(com.code2hack.eyeprojector.protocol.FunctionIntentKind.ACTIVATE), r.functions, "fresh changed-facts binding dispatches")
    check(r.contacts.isEmpty() && r.cancels.isEmpty() && r.scrolls.isEmpty())
    r.adapter.stop()
}

private fun changedFactsUnrelatedRuntimeEdgesDoNotRetireCapture() {
    data class Unrelated(val key: Int = 4, val scan: Int = 158, val down: Long = 1000,
                         val attachment: Int = 41, val phase: Int = KeyEvent.ACTION_UP,
                         val repeat: Int = 0)
    val cases = linkedMapOf(
        "key" to Unrelated(key = 5),
        "scan" to Unrelated(scan = 159),
        "downTime" to Unrelated(down = 999),
        "attachment" to Unrelated(attachment = 42),
    )
    for ((reason, edge) in cases) {
        val r = Rig()
        r.hover(visibleHidRoles[0])
        check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
        val changed = r.device.copy(id = edge.attachment, descriptor = "unrelated:changed:descriptor",
            vendorId = 99, productId = 100)
        check(!r.edge(edge.phase, at = 1010, down = edge.down, key = edge.key, scan = edge.scan,
            repeat = edge.repeat, device = changed, attachmentId = edge.attachment)) {
            "$reason mismatch with changed facts must retain unrelated event fallthrough"
        }
        equal(HidAction.TAP, r.input.selected, "$reason mismatch must not retire the actual capture")
        equal(null, r.record, "$reason mismatch cannot assign either identity")
        r.noOutput()
        check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158))
        equal(r.binding(key = 4, scan = 158), r.bindings.binding(HidAction.TAP),
            "$reason mismatch preserves original captured DOWN until its actual UP")
        equal(null, r.input.selected, "original release completes after $reason mismatch")
        r.noOutput()
    }
}

private fun changedFactsReleaseStaysConsumedWhenCancelCallbackStartsFreshCapture() {
    for (missingDevice in listOf(false, true)) {
        val reason = if (missingDevice) "missing device" else "changed descriptor"
        val r = Rig()
        val panel = r.showPanel()
        r.hover(visibleHidRoles[0])
        check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
        var nestedDownConsumed: Boolean? = null
        r.onChanged = {
            // The old capture's cancellation synchronously publishes a selection
            // change. A newer capture can start in that callback on the same route.
            r.onChanged = {}
            check(r.hover(visibleHidRoles[2]))
            // Use the original actual device facts, key and scan. This fresh DOWN
            // replaces the old 1000 tombstone rather than creating a different key.
            nestedDownConsumed = r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158)
        }
        val device = if (missingDevice) null else r.device.copy(descriptor = "actual:changed:descriptor")
        check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158,
            device = device, attachmentId = r.device.id)) {
            "$reason: an already matched captured release stays consumed after a nested fresh capture replaces its tombstone"
        }
        equal(true, nestedDownConsumed, "$reason cancel callback admits the fresh actual DOWN")
        equal(HidAction.SCROLL_UP, r.input.selected, "$reason: newer nested capture survives old release retirement")
        equal(null, r.bindings.binding(HidAction.TAP), "$reason: retired original capture is not assigned")
        equal(null, r.record, "$reason: old release assigns neither identity")
        r.noOutput()
        check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158))
        equal(r.binding(key = 4, scan = 158), r.bindings.binding(HidAction.SCROLL_UP),
            "$reason: fresh nested capture retains its DOWN facts through its own actual UP")
        equal(null, r.bindings.binding(HidAction.TAP), "$reason: old capture remains unassigned after nested completion")
        equal(null, r.input.selected, "$reason: nested fresh capture completes")
        r.advance(2000)
        r.noOutput()
    }
}

private fun localSettingsPanelPreservesControlsAndOpensHidBindings() {
    val previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
    try {
        val values = mutableMapOf("pointer.x.tenths" to "35", "pointer.y.tenths" to "12",
            "edge.pixels-per-second" to "530", "edge.enabled" to "false")
        var writable = true
        val settings = LocalInputSettings(values::get) { key, value ->
            if (writable) { values[key] = value; true } else false
        }
        var changes = 0
        var hidEntrances = 0
        val panel = LocalInputSettingsPanel(Context(), settings, { hidEntrances++ }, { changes++ })
        equal(LinearLayout.VERTICAL, panel.orientation, "settings remains vertical")
        equal(Color.BLACK, panel.backgroundColor, "settings remains black")
        equal(5, panel.children.size, "three numeric rows, existing toggle and HID entrance")
        equal(8, panel.buttons.size, "six stepped controls, toggle and HID entrance registered")
        val rows = panel.children.take(3).map { it as LinearLayout }
        fun numbers() = rows.map { (it.children[1] as TextView).text.toString() }
        equal(listOf("Pointer X 3.5×", "Pointer Y 1.2×", "Scroll 530 nominal px/s"), numbers(), "actual stored settings values")
        for (row in rows) {
            equal(Gravity.CENTER, row.gravity, "existing row gravity")
            val number = row.children[1] as TextView
            equal(Gravity.CENTER, number.gravity, "existing numeric label gravity")
            equal(Color.WHITE, number.textColor, "existing numeric label light text")
            val params = row.childLayouts.getValue(number)
            equal(listOf(0, -2, 1f), listOf(params.width, params.height, params.weight), "numeric weighted row layout")
        }
        equal(listOf("Decrease Pointer X", "Increase Pointer X", "Decrease Pointer Y", "Increase Pointer Y",
            "Decrease Scroll", "Increase Scroll"), panel.buttons.take(6).map { it.contentDescription.toString() },
            "existing stepped-control descriptions")
        val toggle = panel.buttons[6]
        equal("Head-motion scrolling: Off", toggle.text.toString(), "existing toggle label")
        equal("Off", toggle.stateDescription.toString(), "existing toggle state description")
        val entrance = panel.buttons[7]
        equal("HID key bindings", entrance.text.toString(), "actual HID entrance label")
        val treeButtons = rows.flatMap { it.children.filterIsInstance<Button>() } +
            panel.children.drop(3).map { it as Button }
        equal(treeButtons, panel.buttons, "every actual settings button registered for local-pointer routing")
        for (button in panel.buttons) {
            val background = button.background as GradientDrawable
            equal(Color.BLACK, background.fillColor, "settings button black background")
            equal(Color.WHITE, background.strokeColor, "settings button light border")
            equal(1, background.strokeWidth, "settings button border width")
            equal(96, button.minimumWidth, "settings button existing minimum width")
            equal(96, button.minimumHeight, "settings button existing minimum height")
            val colors = checkNotNull(button.textColors)
            check(colors.states[0].contentEquals(intArrayOf(android.R.attr.state_enabled)))
            check(colors.states[1].isEmpty())
            check(colors.colors.contentEquals(intArrayOf(Color.WHITE, Color.LTGRAY)))
        }

        check(panel.buttons[0].performClick())
        check(panel.buttons[3].performClick())
        check(panel.buttons[5].performClick())
        check(toggle.performClick())
        equal(listOf(34, 13, 540, true), listOf(settings.pointerXTenths, settings.pointerYTenths,
            settings.scrollSpeed, settings.headScrollEnabled), "existing independent settings updates")
        equal(listOf("Pointer X 3.4×", "Pointer Y 1.3×", "Scroll 540 nominal px/s"), numbers(), "existing labels refresh after change")
        equal("Head-motion scrolling: On", toggle.text.toString(), "existing toggle refresh")
        equal("On", toggle.stateDescription.toString(), "existing toggle state refresh")
        equal(4, changes, "one existing change callback per setting action")
        equal(mapOf("pointer.x.tenths" to "34", "pointer.y.tenths" to "13",
            "edge.pixels-per-second" to "540", "edge.enabled" to "true"), values.toMap(), "existing settings writes")
        val beforeEntrance = values.toMap()
        check(entrance.performClick())
        equal(1, hidEntrances, "HID entrance invokes supplied callback")
        equal(4, changes, "HID entrance does not invoke settings-change callback")
        equal(beforeEntrance, values.toMap(), "HID entrance preserves settings")

        writable = false
        check(panel.buttons[1].performClick())
        check(toggle.performClick())
        equal(34, settings.pointerXTenths, "failed pointer write retains effective value")
        check(settings.headScrollEnabled)
        equal("Pointer X 3.4×", numbers()[0], "failed pointer write retains label")
        equal("Head-motion scrolling: On", toggle.text.toString(), "failed toggle write retains label")
        equal(beforeEntrance, values.toMap(), "failed settings writes preserve persisted values")
        equal("Settings could not be saved. Try again.", settings.error, "existing settings error")
        equal(6, changes, "existing failure callback behavior preserved")

        for (upper in listOf(false, true)) {
            val extremes = mutableMapOf("pointer.x.tenths" to if (upper) "60" else "1",
                "pointer.y.tenths" to if (upper) "1" else "60",
                "edge.pixels-per-second" to if (upper) "1200" else "10", "edge.enabled" to "true")
            val bounded = LocalInputSettings(extremes::get) { key, value -> extremes[key] = value; true }
            var boundedChanges = 0
            val boundsPanel = LocalInputSettingsPanel(Context(), bounded, {}, { boundedChanges++ })
            equal(if (upper) listOf(true, false, false, true, true, false) else listOf(false, true, true, false, false, true),
                boundsPanel.buttons.take(6).map { it.isEnabled }, "existing numeric controls disabled at exact bounds")
            // Click enabled neighbors, leaving the disabled controls untouched as a real
            // pointer route would. performClick here is only the listener API boundary.
            for (index in if (upper) listOf(0, 3, 4) else listOf(1, 2, 5)) check(boundsPanel.buttons[index].performClick())
            equal(if (upper) listOf(59, 2, 1190) else listOf(2, 59, 20),
                listOf(bounded.pointerXTenths, bounded.pointerYTenths, bounded.scrollSpeed), "existing step sizes next to bounds")
            check(boundsPanel.buttons.take(6).all { it.isEnabled })
            equal(3, boundedChanges, "existing bounded changes notify once")
        }
    } finally {
        Locale.setDefault(previousLocale)
    }
}

private fun visibleSingleResetUnassignsAllActionsAndSurvivesReload() {
    val r = Rig()
    HidAction.entries.forEach { r.assign(it, key = 190 + it.ordinal, scan = 700 + it.ordinal) }
    val panel = r.showPanel()
    val writesBefore = r.writes.size
    val changedBefore = r.changed
    val control = resetControl(panel)
    equal(4, panel.buttons.indexOf(control), "single reset follows the four assignments")
    check(control.isEnabled && control.performClick())
    equal(writesBefore + 1, r.writes.size, "all four assignments reset in one store write")
    equal(changedBefore + 1, r.changed, "reset publishes one refresh through HID input")
    val restored = HidBindings({ r.record }) { error("reload must not write") }
    for (action in HidAction.entries) {
        equal(null, r.bindings.binding(action), "Reset removes $action")
        equal(null, restored.binding(action), "Reset survives $action reload")
        if (action in visibleHidRoles) check(panel.buttons[action.ordinal].text.toString().endsWith(": Unassigned"))
    }
    equal(null, restored.error, "reset record reloads without parse error")
    equal(null, r.input.selected, "Reset retires capture selection")
    // Repeated Reset is a deliberate single empty-map write, including an already empty record.
    val emptyRecord = r.record
    check(control.performClick())
    equal(writesBefore + 2, r.writes.size, "repeated empty Reset still writes exactly once")
    equal(emptyRecord, r.record, "empty reset preserves the existing record format")
    // Every ordinary assignment remains usable after all bindings are removed.
    for (action in visibleHidRoles) {
        check(r.hover(visibleHidRoles[action.ordinal]))
        val at = 1100L + action.ordinal * 100
        check(r.edge(KeyEvent.ACTION_DOWN, at = at, key = 210 + action.ordinal, scan = 800 + action.ordinal))
        check(r.edge(KeyEvent.ACTION_UP, at = at + 20, down = at, key = 210 + action.ordinal, scan = 800 + action.ordinal))
        equal(r.binding(key = 210 + action.ordinal, scan = 800 + action.ordinal), r.bindings.binding(action),
            "reset $action remains assignable")
    }
    r.noOutput()
}

private fun resetCancelsHeldCaptureAndPendingFunctionWorkEvenWhenSaveFails() {
    for (writable in listOf(true, false)) {
        val held = Rig()
        held.assign(HidAction.TAP)
        val heldPanel = held.showPanel()
        check(held.edge(KeyEvent.ACTION_DOWN))
        held.writable = writable
        val heldBefore = held.record
        val attempts = held.writes.size
        check(resetControl(heldPanel).performClick())
        equal(attempts + 1, held.writes.size, "held Reset makes one write attempt save=$writable")
        equal(1, held.cancels.size, "Reset cancels the existing held owner save=$writable")
        check(held.contacts.single().owner === held.cancels.single().owner)
        equal(1000L, held.contacts.single().event.downTimeMs, "held DOWN retains actual timestamp")
        equal(1000L, held.cancels.single().time, "Reset cancellation uses current monotonic time")
        check(held.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
        equal(1, held.contacts.size, "reset-held release cannot send target UP save=$writable")
        equal(1, held.cancels.size, "old release adds no duplicate cancellation")
        equal(if (writable) null else held.binding(), held.bindings.binding(HidAction.TAP), "effective held binding reflects store result")
        if (!writable) equal(heldBefore, held.record, "failed Reset retains held record")

        val capture = Rig()
        val capturePanel = capture.showPanel()
        check(capture.hover(visibleHidRoles[0]))
        check(capture.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
        capture.writable = writable
        check(resetControl(capturePanel).performClick())
        equal(null, capture.input.selected, "Reset retires active capture save=$writable")
        check(capture.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158))
        check(HidAction.entries.all { capture.bindings.binding(it) == null })
        equal(1, capture.writes.size, "capture Reset writes once; retired release never persists")
        capture.noOutput()

        val function = Rig()
        function.assign(HidAction.FUNCTION)
        val functionPanel = function.showPanel()
        check(function.edge(KeyEvent.ACTION_DOWN))
        val staleTimers = function.timers.toList()
        val functionBefore = function.record
        function.writable = writable
        check(resetControl(functionPanel).performClick())
        staleTimers.forEach { it.callback() }
        function.advance(3000)
        function.noOutput()
        equal(if (writable) null else function.binding(), function.bindings.binding(HidAction.FUNCTION),
            "pending Function effective binding reflects store result")
        if (!writable) equal(functionBefore, function.record, "failed Reset retains pending Function record")
    }
}

private fun failedResetRetainsEveryStoredAssignmentAndReportsErrorUntilRetry() {
    val r = Rig()
    HidAction.entries.forEach { r.assign(it, key = 190 + it.ordinal, scan = 700 + it.ordinal) }
    val panel = r.showPanel()
    val before = r.record
    val assignments = HidAction.entries.associateWith { r.bindings.binding(it) }
    r.writable = false
    check(r.hover(visibleHidRoles[0]))
    check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    val attempts = r.writes.size
    check(resetControl(panel).performClick())
    equal(attempts + 1, r.writes.size, "failed Reset still makes only one atomic write attempt")
    equal(null, r.input.selected, "failed Reset still retires the in-progress capture")
    equal(before, r.record, "failed Reset preserves persisted bytes")
    val restored = HidBindings({ r.record }) { error("reload must not write") }
    for (action in HidAction.entries) {
        equal(assignments[action], r.bindings.binding(action), "failed Reset preserves effective $action")
        equal(assignments[action], restored.binding(action), "failed Reset preserves reloaded $action")
        if (action in visibleHidRoles) check(!panel.buttons[action.ordinal].text.toString().endsWith(": Unassigned"))
    }
    equal("HID bindings could not be saved. Try again.", r.message, "failed Reset displays truthful store error")
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158))
    r.noOutput()
    r.writable = true
    check(resetControl(panel).performClick())
    equal(attempts + 2, r.writes.size, "successful retry uses one further write")
    for (action in HidAction.entries) {
        equal(null, r.bindings.binding(action), "successful retry removes $action")
        equal(null, HidBindings({ r.record }) { false }.binding(action), "successful retry persists $action removal")
    }
    check(!r.message.contains("could not be saved"))
}

private fun resetRepairsMalformedAndEmptyStorageWithoutFabricatingAssignments() {
    val emptyRecord = Rig().also { check(resetControl(it.showPanel()).performClick()) }.record
    for (initial in listOf(null, emptyRecord, "malformed existing bindings")) {
        for (writable in listOf(true, false)) {
            val r = Rig(initialRecord = initial, save = writable)
            val panel = r.showPanel()
            if (initial == "malformed existing bindings") check(r.message.contains("could not be read"))
            check(resetControl(panel).performClick())
            equal(1, r.writes.size, "empty/malformed Reset attempts one replacement write")
            equal(null, r.input.selected, "empty/malformed Reset has no selected capture")
            check(HidAction.entries.all { r.bindings.binding(it) == null })
            if (writable) {
                equal(emptyRecord, r.record, "Reset writes the same valid existing-format empty map")
                equal(null, r.bindings.error, "successful Reset clears prior read error")
                val reloaded = HidBindings({ r.record }) { error("reload must not write") }
                equal(null, reloaded.error, "repaired storage reloads without error")
                check(HidAction.entries.all { reloaded.binding(it) == null })
                check(!r.message.contains("could not"))
            } else {
                equal(initial, r.record, "failed repair preserves previous storage bytes")
                equal("HID bindings could not be saved. Try again.", r.message, "failed repair reports save error")
                if (initial == "malformed existing bindings")
                    check(HidBindings({ r.record }) { false }.error?.contains("could not be read") == true)
            }
            r.noOutput()
        }
    }
}

private fun excludedDeviceCannotLearnOrDispatchHid(device: InputDevice) {
    val capture = Rig()
    val panel = capture.showPanel()
    check(capture.hover(visibleHidRoles[3]))
    check(!capture.edge(KeyEvent.ACTION_DOWN, key = 83, scan = 204, device = device)) {
        "excluded ${device.name} DOWN must remain available to native dispatch"
    }
    check(!capture.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 83, scan = 204, device = device))
    equal(null, capture.bindings.binding(HidAction.FUNCTION), "excluded device is never learned")
    equal(null, capture.record, "excluded capture does not create a saved binding")
    capture.advance(3000)
    capture.noOutput()

    // Replaying legacy saved bytes is distinct from trying to learn a new assignment.
    val seeded = Rig()
    seeded.assign(HidAction.FUNCTION, key = 83, scan = 204, device = device)
    val saved = Rig(seeded.record)
    check(!saved.edge(KeyEvent.ACTION_DOWN, key = 83, scan = 204, device = device))
    check(!saved.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 83, scan = 204, device = device))
    saved.advance(3000)
    saved.noOutput()
}

private fun startupRepairRemovesOnlyTheRecordedNativeFunctionAndPreservesJoyCon() {
    val original = actual379Record()
    val r = Rig(original)
    r.android.inputManager.install(nativePad379)
    r.android.inputManager.install(nativePower379)
    r.android.inputManager.install(virtual379)
    r.android.inputManager.install(joyCon379)
    r.adapter.start()
    equal(null, r.bindings.binding(HidAction.FUNCTION), "startup repairs actual saved native key83 Function binding")
    check(r.record != original)
    equal(null, HidBindings({ r.record }) { false }.binding(HidAction.FUNCTION), "native repair persists")
    assertJoyCon379Preserved(r)
    r.adapter.stop()
    r.noOutput()

    // A fresh adapter/store must retain the repair, not recreate the native assignment.
    val restarted = Rig(r.record)
    restarted.android.inputManager.install(nativePad379)
    restarted.android.inputManager.install(joyCon379)
    restarted.adapter.start()
    equal(r.record, restarted.record, "repaired record is stable on restart")
    assertJoyCon379Preserved(restarted)
    restarted.adapter.stop()
}

private fun listenerRepairBlocksNativeAndPreservesAllThreeJoyConActions() {
    for (change in listOf(false, true)) {
        val r = Rig(actual379Record())
        r.adapter.start() // No excluded inventory exists until its observed listener event below.
        r.android.inputManager.install(nativePad379)
        if (change) r.android.inputManager.change(nativePad379.id) else r.android.inputManager.add(nativePad379.id)
        check(!r.edge(KeyEvent.ACTION_DOWN, key = 83, scan = 204, device = nativePad379))
        check(!r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 83, scan = 204, device = nativePad379))
        equal(null, r.bindings.binding(HidAction.FUNCTION), "observed attachment add/change repairs native mapping")
        assertJoyCon379Preserved(r)
        r.noOutput()
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 103, scan = 311, device = joyCon379))
        check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 103, scan = 311, device = joyCon379))
        equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
            r.contacts.map { it.event.phase }, "actual saved Joy-Con R1 retains held ingress")
        equal(listOf(1100L, 1120L), r.contacts.map { it.event.timeMs }, "Joy-Con R1 actual event times")
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, key = 96, scan = 304, device = joyCon379))
        check(r.edge(KeyEvent.ACTION_UP, at = 1220, down = 1200, key = 96, scan = 304, device = joyCon379))
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1300, key = 99, scan = 307, device = joyCon379))
        check(r.edge(KeyEvent.ACTION_UP, at = 1320, down = 1300, key = 99, scan = 307, device = joyCon379))
        equal(listOf(160, -160), r.scrolls.map { it.pixels }, "actual saved Joy-Con A/X retain scroll direction")
        equal(listOf(1220L, 1320L), r.scrolls.map { it.time }, "Joy-Con A/X retain actual release times")
        assertJoyCon379Preserved(r)
        check(r.functions.isEmpty() && r.timers.isEmpty())
        r.adapter.stop()
    }
}

private fun failedLegacyRepairKeepsRecordAndErrorButNeverDispatchesNativeHid() {
    for (route in listOf("startup", "added", "changed")) {
        val original = actual379Record()
        val r = Rig(original, save = false)
        val panel = r.showPanel()
        r.android.inputManager.install(joyCon379)
        if (route == "startup") {
            r.android.inputManager.install(nativePad379)
            r.adapter.start()
        } else {
            r.adapter.start()
            r.android.inputManager.install(nativePad379)
            if (route == "changed") r.android.inputManager.change(nativePad379.id)
            else r.android.inputManager.add(nativePad379.id)
        }
        check(!r.edge(KeyEvent.ACTION_DOWN, key = 83, scan = 204, device = nativePad379))
        check(!r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 83, scan = 204, device = nativePad379))
        equal(original, r.record, "failed native repair preserves persisted bytes")
        check(r.bindings.binding(HidAction.FUNCTION) != null)
        equal("HID bindings could not be saved. Try again.", r.bindings.error, "failed native repair reports error")
        panel.refresh()
        equal(r.bindings.error, r.message, "failed repair error remains visible")
        check(!panel.buttons[3].text.toString().endsWith(": Unassigned"))
        assertJoyCon379Preserved(r)
        r.advance(2000)
        r.noOutput()
        // Save failure must not disable unrelated real external mappings.
        check(r.edge(KeyEvent.ACTION_DOWN, at = 2100, key = 103, scan = 311, device = joyCon379))
        check(r.edge(KeyEvent.ACTION_UP, at = 2120, down = 2100, key = 103, scan = 311, device = joyCon379))
        equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
            r.contacts.map { it.event.phase }, "Joy-Con remains usable despite repair failure")
        equal(original, r.record, "external dispatch does not silently rewrite failed repair")
        r.adapter.stop()
    }
}

private fun receiverNativeKey83RetainsContactMarkerRoute(capturing: Boolean, saveFailure: Boolean) {
    val original = actual379Record()
    val r = Rig(original, save = !saveFailure)
    if (saveFailure) {
        r.android.inputManager.install(nativePad379)
        r.adapter.start()
        equal("HID bindings could not be saved. Try again.", r.bindings.error, "failed startup repair remains observable")
    }
    check(r.bindings.binding(HidAction.FUNCTION) != null) { "native legacy assignment must exist before the Receiver dispatch probe" }
    if (capturing) check(r.hover(visibleHidRoles[0]))
    val receiver = ExtractedReceiverKeyIngress(r.input, r.adapter)
    equal(PadGestureRecognizer.Key.CONTACT, nativePadKey(nativePad379.name, 0x101, 83),
        "exact native mapper retains key83 CONTACT")
    fun edge(action: Int, at: Long, down: Long) {
        r.now = at; SystemClock.now = at
        check(receiver.dispatchKeyEvent(KeyEvent(nativePad379, action, 83, 204, down, at, source = 0x101)))
    }
    edge(KeyEvent.ACTION_DOWN, 1000, 1000)
    edge(KeyEvent.ACTION_UP, 1020, 1000)
    equal(1L, receiver.padUps, "excluded native key83 UP reaches exact Receiver native branch")
    equal(2020L, receiver.gestures.deadline, "native CONTACT marker retains classification deadline")
    check(receiver.fallthrough.isEmpty())
    check(receiver.nativeActions.isEmpty())
    receiver.gestures.confirm(2019)
    check(receiver.nativeActions.isEmpty())
    receiver.gestures.confirm(2020)
    val action = receiver.nativeActions.single() as PadGestureRecognizer.Action.Tap
    equal(PadGestureRecognizer.Timing.CONTACT_MARKER, action.timing, "native contact marker classifier remains actual")
    equal(listOf(1000L, 1020L, 2020L), listOf(action.downTimeMs, action.upTimeMs, action.timeMs),
        "native original timestamps and arbitration remain unchanged")
    r.advance(3000)
    r.noOutput()
    assertJoyCon379Preserved(r)
    equal(103, r.bindings.binding(HidAction.TAP)?.button?.keyCode,
        "native key83 cannot replace the saved Joy-Con TAP during capture")
    equal(original, r.record, "native key dispatch itself does not rewrite the saved record")
    check(r.bindings.binding(HidAction.FUNCTION) != null) { "excluded saved mapping remains harmless before successful startup repair" }
    if (saveFailure) r.adapter.stop()
}

private fun startupRepairPreservesUnrelatedActiveExternalHoldAndCapture() {
    for (capture in listOf(false, true)) {
        val r = Rig(actual379Record())
        r.android.inputManager.install(nativePad379)
        r.android.inputManager.install(joyCon379)
        if (capture) {
            check(r.hover(visibleHidRoles[3]))
            check(r.edge(KeyEvent.ACTION_DOWN, key = 195, scan = 700))
        } else check(r.edge(KeyEvent.ACTION_DOWN, key = 103, scan = 311, device = joyCon379))
        r.adapter.start()
        equal(null, r.bindings.binding(HidAction.FUNCTION), "startup removes only the unrelated saved native Function assignment")
        check(r.cancels.isEmpty()) { "repair of another identity canceled an actual external hold/capture" }
        if (capture) {
            equal(HidAction.FUNCTION, r.input.selected, "in-progress external capture survives unrelated native repair")
            check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 195, scan = 700))
            equal(r.binding(), r.bindings.binding(HidAction.FUNCTION), "surviving capture saves its own external DOWN facts")
            r.noOutput()
        } else {
            check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 103, scan = 311, device = joyCon379))
            equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
                r.contacts.map { it.event.phase }, "unrelated repair preserves the exact external held route")
            check(r.contacts[0].owner === r.contacts[1].owner)
        }
        assertJoyCon379Preserved(r)
        r.adapter.stop()
    }
}

private fun newlyExcludedMetadataRetiresOnlyAlreadyOwnedExternalEdges() {
    val changes = listOf<(InputDevice) -> InputDevice>(
        { it.copy(isExternal = false) }, { it.copy(isVirtual = true) }, { it.copy(name = nativePad379.name) },
    )
    for (changed in changes) {
        for (capture in listOf(false, true)) {
            val r = Rig()
            if (capture) check(r.hover(visibleHidRoles[0])) else r.assign(HidAction.TAP)
            check(r.edge(KeyEvent.ACTION_DOWN))
            val excluded = changed(r.device)
            check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, device = excluded)) {
                "already owned external release remains consumed after eligibility metadata changes"
            }
            if (capture) {
                equal(null, r.input.selected, "newly excluded captured owner is retired")
                equal(null, r.record, "newly excluded captured identity is never saved")
                r.noOutput()
            } else {
                equal(1, r.contacts.size, "newly excluded final release cannot emit target UP")
                equal(1, r.cancels.size, "newly excluded existing hold is canceled")
                check(r.contacts.single().owner === r.cancels.single().owner)
            }
            check(!r.edge(KeyEvent.ACTION_DOWN, at = 1100, device = excluded)) {
                "fresh excluded input is available to the ordinary native route"
            }
        }
    }
}

private fun panelExplicitlyRequestsExternalHidButtons() {
    val r = Rig()
    val panel = r.showPanel()
    equal("Point at an action, then press and release an external HID button.", r.message,
        "idle instructions identify external-only learning")
    check(r.hover(visibleHidRoles[3]))
    equal(null, r.input.selected, "hover does not latch the Function action")
    check(r.edge(KeyEvent.ACTION_DOWN))
    equal("Release the external HID button for Function button.", r.message,
        "capture instructions identify external-only learning")
}

private fun receiverExternalSameKeyCodeRemainsEligibleForCaptureAndFunction() {
    val capture = Rig()
    val panel = capture.showPanel()
    check(capture.hover(visibleHidRoles[3]))
    val receiver = ExtractedReceiverKeyIngress(capture.input, capture.adapter)
    fun edge(r: Rig, target: ExtractedReceiverKeyIngress, action: Int, at: Long, down: Long) {
        r.now = at; SystemClock.now = at
        check(target.dispatchKeyEvent(KeyEvent(r.device, action, 83, 204, down, at)))
    }
    edge(capture, receiver, KeyEvent.ACTION_DOWN, 1000, 1000)
    edge(capture, receiver, KeyEvent.ACTION_UP, 1020, 1000)
    equal(capture.binding(key = 83, scan = 204), capture.bindings.binding(HidAction.FUNCTION),
        "an actual external device with key83 can still be learned")
    capture.noOutput()
    check(receiver.nativeActions.isEmpty() && receiver.scheduled.isEmpty() && receiver.fallthrough.isEmpty())
    equal(0L, receiver.padUps, "external key83 is owned by HID before native normalization")
    capture.leaveRows()
    edge(capture, receiver, KeyEvent.ACTION_DOWN, 1100, 1100)
    edge(capture, receiver, KeyEvent.ACTION_UP, 1120, 1100)
    capture.advance(1620)
    equal(listOf(com.code2hack.eyeprojector.protocol.FunctionIntentKind.ACTIVATE), capture.functions, "external key83 retains Function classification")
    check(receiver.nativeActions.isEmpty() && receiver.scheduled.isEmpty() && receiver.fallthrough.isEmpty())
}

private fun extractedNativeMappingRetainsRecordedNativeControls() {
    val codes = linkedMapOf(83 to PadGestureRecognizer.Key.CONTACT, 66 to PadGestureRecognizer.Key.TAP,
        291 to PadGestureRecognizer.Key.DOUBLE, 4 to PadGestureRecognizer.Key.DOUBLE,
        22 to PadGestureRecognizer.Key.FORWARD, 21 to PadGestureRecognizer.Key.BACKWARD,
        292 to PadGestureRecognizer.Key.FORWARD_ALIAS, 293 to PadGestureRecognizer.Key.BACKWARD_ALIAS,
        20 to PadGestureRecognizer.Key.FORWARD_TAIL, 19 to PadGestureRecognizer.Key.BACKWARD_TAIL)
    for ((code, expected) in codes) equal(expected, nativePadKey(nativePad379.name, 0x101, code),
        "actual native mapper retains recorded key $code")
    equal(null, nativePadKey(joyCon379.name, 0x101, 83), "external device is not renamed as native pad")
    equal(null, nativePadKey(nativePad379.name, 0, 83), "native source qualification stays actual")
}

private fun hoveredRowsUseRootOffsetsAndActualVisibleClipping() {
    val r = Rig()
    val panel = r.showPanel()
    for (action in visibleHidRoles) {
        r.hover(action)
        equal(action, panel.actionAt(r.point, r.root), "nonzero root-local offset resolves $action")
    }
    r.hover(HidAction.TAP)
    panel.buttons[0].hostVisibleRect = Rect(500, 1100, 700, 1150)
    equal(null, panel.actionAt(r.point, r.root), "clipped-off portion of row is not a target")
    r.point = PointerPosition(500f, 1040f, true)
    equal(HidAction.TAP, panel.actionAt(r.point, r.root), "visible clipped interior remains target")
    r.point = PointerPosition(650f, 1040f, true)
    equal(null, panel.actionAt(r.point, r.root), "right visible edge is outside")
    r.point = PointerPosition(500f, 1070f, true)
    equal(null, panel.actionAt(r.point, r.root), "bottom visible edge is outside")
    r.point = PointerPosition(500f, 1040f, true)
    r.root.hostVisibleRect = Rect(50, 80, 500, 2080)
    equal(null, panel.actionAt(r.point, r.root), "root clipping also excludes a row point")
}

private fun unavailableHiddenDisabledAndNonAssignmentAreasNeverLearn() {
    val r = Rig()
    val panel = r.showPanel()
    r.hover(HidAction.TAP)
    val good = r.point
    for (point in listOf(good.copy(available = false), good.copy(x = Float.NaN),
        good.copy(x = Float.POSITIVE_INFINITY), PointerPosition(100f, 200f, true),
        good.copy(y = 1440f), good.copy(y = 1540f))) {
        equal(null, panel.actionAt(point, r.root), "unavailable/outside/Reset/Back point has no assignment")
    }
    for (view in listOf(r.root, panel, panel.buttons[0])) {
        view.visibility = View.GONE
        equal(null, panel.actionAt(good, r.root), "hidden root/panel/row is not a target")
        view.visibility = View.VISIBLE
    }
    for (view in listOf(panel, panel.buttons[0])) {
        view.isEnabled = false
        equal(null, panel.actionAt(good, r.root), "disabled panel/row is not a target")
        view.isEnabled = true
    }
    r.point = good.copy(y = 1440f)
    check(!r.edge(KeyEvent.ACTION_DOWN))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
    equal(null, r.record, "external edges over Reset do not become assignments")
    r.noOutput()
}

private fun rowClicksNeverLatchAndEveryActualDownCanReplaceOrMoveBindings() {
    val r = Rig()
    val panel = r.showPanel()
    check(!panel.buttons[0].performClick())
    r.leaveRows()
    check(!r.edge(KeyEvent.ACTION_DOWN))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
    equal(null, r.record, "click followed by outside DOWN does not leave sticky selection")
    r.hover(HidAction.TAP)
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100))
    r.hover(HidAction.FUNCTION)
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100))
    equal(r.binding(), r.bindings.binding(HidAction.TAP), "A-DOWN B-UP remains pinned to A")
    equal(null, r.bindings.binding(HidAction.FUNCTION), "release hover does not retarget")
    r.hover(HidAction.TAP)
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, key = 196, scan = 701))
    check(r.edge(KeyEvent.ACTION_UP, at = 1220, down = 1200, key = 196, scan = 701))
    equal(r.binding(key = 196, scan = 701), r.bindings.binding(HidAction.TAP), "same row immediately accepts replacement")
    r.hover(HidAction.SCROLL_UP)
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1300, key = 196, scan = 701))
    check(r.edge(KeyEvent.ACTION_UP, at = 1320, down = 1300, key = 196, scan = 701))
    equal(null, r.bindings.binding(HidAction.TAP), "moving actual key unassigns former row")
    equal(r.binding(key = 196, scan = 701), r.bindings.binding(HidAction.SCROLL_UP), "actual key moves to current DOWN row")
    equal(3, r.writes.size, "only three complete physical captures persist")
    r.noOutput()
}

private fun newerHoveredCaptureOwnsItsReleaseAcrossOverlapAndOldLateUp() {
    for (otherAttachment in listOf(false, true)) {
        val r = Rig()
        r.hover(HidAction.TAP)
        check(r.edge(KeyEvent.ACTION_DOWN, key = 194, scan = 700))
        r.hover(HidAction.SCROLL_DOWN)
        val newer = if (otherAttachment) r.device.copy(id = 42) else r.device
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 195, scan = 701, device = newer))
        equal(HidAction.SCROLL_DOWN, r.input.selected, "newer actual DOWN owns active capture")
        check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1000, key = 194, scan = 700))
        equal(HidAction.SCROLL_DOWN, r.input.selected, "old late UP does not complete newer capture")
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1130, down = 1100, key = 195, scan = 701,
            repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS, device = newer))
        check(r.edge(KeyEvent.ACTION_UP, at = 1140, down = 1100, key = 195, scan = 701, device = newer))
        equal(null, r.bindings.binding(HidAction.TAP), "older overlapped capture is never saved")
        equal(r.binding(newer, 195, 701), r.bindings.binding(HidAction.SCROLL_DOWN), "newer DOWN facts alone persist")
        equal(1, r.writes.size, "overlap emits one completed assignment")
        r.noOutput()
    }
}

private fun traceRows(): List<String> = Log.messages.filter { it.first == "EyeProjectorHid" }.map { it.second }

private fun traceIngress(r: Rig): ExtractedReceiverKeyIngress {
    val panel = r.panel ?: r.showPanel()
    val receiver = ExtractedReceiverKeyIngress(r.input, r.adapter)
    receiver.hidPanel = panel
    receiver.root = r.root
    r.receivedRoute = receiver::receivedForTest
    r.captureTarget = { trace ->
        receiver.pointer.position = r.point
        receiver.hoveredForTest(trace)
    }
    return receiver
}

private fun traceEdge(r: Rig, receiver: ExtractedReceiverKeyIngress, action: Int, at: Long, down: Long,
                      device: InputDevice? = r.device, repeat: Int = 0, flags: Int = 0): Boolean {
    r.now = at
    SystemClock.now = at
    return receiver.dispatchKeyEvent(KeyEvent(device, action, 194, 813, down, at, repeat, flags))
}

private fun actualPageTraceBindsRealTupleHoverGeometryCaptureAndSaveResult() {
    try {
        for (save in listOf(true, false)) {
            Log.messages.clear()
            val r = Rig()
            r.hover(HidAction.SCROLL_UP)
            val receiver = traceIngress(r)
            check(traceEdge(r, receiver, KeyEvent.ACTION_DOWN, 1000, 1000))
            val downRows = traceRows()
            check(downRows.isNotEmpty())
            val tuple = "device=41 key=194 scan=813 action=0 repeat=0 canceled=false down=1000 time=1000 "
            check(downRows.all { it.startsWith(tuple) }) { "each DOWN decision retains the immutable actual event tuple" }
            for (step in listOf("ARRIVAL", "ADAPTER_RETIRED", "DEVICE_EXTERNAL", "ADAPTER_INPUT", "HOVER_POINT",
                "HOVER_GENERATION_CHANGED", "HIT_ROOT_CLIP", "HIT_ROW_CLIP", "HIT_RESULT", "CAPTURE_BEGIN"))
                check(downRows.any { it.contains(" step=$step ") }) { "missing actual trace stage $step" }
            check(downRows.any { it.contains(" step=HOVER_POINT ") && it.contains("available=true x=400.0 y=1240.0") })
            check(downRows.any { it.contains(" step=HIT_ROOT_CLIP fact=YES ") &&
                it.contains("left=50 top=80 right=1050 bottom=2080 offsetX=50 offsetY=80") })
            check(downRows.any { it.contains(" step=HIT_ROW_CLIP fact=YES target=SCROLL_UP ") &&
                it.contains("left=350 top=1280 right=950 bottom=1370") })
            check(downRows.any { it.contains(" step=HIT_RESULT fact=YES target=SCROLL_UP ") })
            r.writable = save
            Log.messages.clear()
            check(traceEdge(r, receiver, KeyEvent.ACTION_UP, 1020, 1000))
            val upRows = traceRows()
            check(upRows.all { it.startsWith("device=41 key=194 scan=813 action=1 repeat=0 canceled=false down=1000 time=1020 ") })
            check(upRows.any { it.contains(" step=CAPTURE_MATCH fact=YES target=SCROLL_UP ") })
            check(upRows.any { it.contains(" step=CAPTURE_SAVE fact=${if (save) "YES" else "NO"} target=SCROLL_UP ") })
            equal(if (save) r.binding(key = 194, scan = 813) else null, r.bindings.binding(HidAction.SCROLL_UP),
                "trace reports the real effective save result")
            equal(1, r.writes.size, "diagnostics do not add store writes")
            equal(1, receiver.pointer.reads, "only actual capture DOWN refreshes the pointer")
            equal(1, r.targetReads, "one actual capture target evaluation")
            check((downRows + upRows).none { it.contains(r.device.name) || it.contains(r.device.descriptor) })
            r.noOutput()
        }
    } finally { Log.onWrite = null; Log.messages.clear() }
}

private fun pageOnlyTraceAndShortCircuitEligibilityDoNotInspectSkippedCallbacks() {
    try {
        val outside = Rig()
        val outsideReceiver = traceIngress(outside)
        outsideReceiver.hidPanel.visibility = View.GONE
        Log.messages.clear()
        check(!traceEdge(outside, outsideReceiver, KeyEvent.ACTION_DOWN, 1000, 1000))
        check(traceRows().isEmpty()) { "outside the HID page no HID tag is emitted" }
        equal(0, outsideReceiver.pointer.reads, "outside page short-circuits pointer publication")
        equal(0, outside.root.visibleRectReads, "outside page never evaluates geometry")
        equal(0, outside.writes.size, "outside page never writes bindings")

        for (device in listOf(nativePower379, virtual379.copy(isExternal = true))) {
            val r = Rig()
            r.hover(HidAction.TAP)
            val receiver = traceIngress(r)
            Log.messages.clear()
            check(!traceEdge(r, receiver, KeyEvent.ACTION_DOWN, 1100, 1100, device))
            val rows = traceRows()
            check(rows.any { it.contains(" step=DEVICE_EXTERNAL fact=${if (device.isExternal) "YES" else "NO"} ") })
            if (device.isExternal) check(rows.any { it.contains(" step=DEVICE_VIRTUAL fact=YES ") })
            else check(rows.none { it.contains(" step=DEVICE_VIRTUAL ") })
            check(rows.none { it.contains(" step=DEVICE_NOT_PAD ") || it.contains(" step=DEVICE_NOT_POWER ") ||
                it.contains(" step=HOVER_POINT ") || it.contains(" step=HIT_ROOT_CLIP ") })
            check(rows.any { it.contains(" step=ADAPTER_INELIGIBLE fact=YES target=UNOBSERVED available=UNOBSERVED x=UNOBSERVED y=UNOBSERVED") })
            equal(0, r.android.inputManager.deviceReads, "diagnostics do not perform an InputManager lookup")
            equal(0, r.targetReads, "excluded device never invokes capture target")
            equal(0, receiver.pointer.reads, "excluded device never refreshes pointer")
            equal(0, r.positionReads, "excluded device never admits output position")
            equal(0, r.root.visibleRectReads, "excluded device never hit-tests the root")
            equal(0, r.writes.size, "excluded device never writes store")
            r.noOutput()
        }
    } finally { Log.onWrite = null; Log.messages.clear() }
}

private fun throwingDiagnosticSinkPreservesOutputCallbacksAndOriginalFunctionalException() {
    data class Outcome(val phases: List<PadGestureRecognizer.Phase>, val times: List<Pair<Long, Long>>,
                       val targetReads: Int, val positionReads: Int, val hoverReads: Int,
                       val rootReads: Int, val rowReads: List<Int>, val writes: Int, val selected: HidAction?)
    fun held(throwing: Boolean): Outcome {
        val r = Rig()
        r.assign(HidAction.TAP, key = 194, scan = 813)
        val receiver = traceIngress(r)
        r.leaveRows()
        Log.onWrite = if (throwing) { tag, _ -> if (tag == "EyeProjectorHid") throw AssertionError("host diagnostic sink") } else null
        check(traceEdge(r, receiver, KeyEvent.ACTION_DOWN, 1000, 1000))
        check(traceEdge(r, receiver, KeyEvent.ACTION_UP, 1020, 1000))
        equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP), r.contacts.map { it.event.phase },
            "actual held outputs retain DOWN and UP")
        check(r.cancels.isEmpty() && r.functions.isEmpty() && r.scrolls.isEmpty())
        return Outcome(r.contacts.map { it.event.phase }, r.contacts.map { Pair(it.event.downTimeMs, it.event.timeMs) },
            r.targetReads, r.positionReads, receiver.pointer.reads, r.root.visibleRectReads,
            r.panel!!.buttons.map { it.visibleRectReads }, r.writes.size, r.input.selected)
    }
    try {
        val expected = held(false)
        equal(expected, held(true), "throwing diagnostic sink preserves real output and callback evaluation counts")
        for (throwing in listOf(false, true)) {
            val r = Rig()
            r.hover(HidAction.TAP)
            val receiver = traceIngress(r)
            val failure = IllegalStateException("original functional target callback")
            r.captureTarget = { throw failure }
            Log.onWrite = if (throwing) { tag, _ -> if (tag == "EyeProjectorHid") throw AssertionError("host diagnostic sink") } else null
            var caught: Throwable? = null
            try { traceEdge(r, receiver, KeyEvent.ACTION_DOWN, 1000, 1000) } catch (error: Throwable) { caught = error }
            check(caught === failure) { "diagnostics must not swallow or replace an original functional callback exception" }
            equal(1, r.targetReads, "functional target callback is evaluated once")
            equal(0, r.writes.size, "throwing functional callback does not persist")
            r.noOutput()
        }
    } finally { Log.onWrite = null; Log.messages.clear() }
}

private fun receivedStatusShowsActualExternalDownWithoutHoverOrRowShift() {
    val r = Rig()
    val panel = r.showPanel()
    traceIngress(r)
    val line = panel.children[2] as TextView
    val lineLayout = checkNotNull(panel.childLayouts[line])
    val rowOrder = panel.children.toList()
    val rowBounds = panel.buttons.map { it.hostVisibleRect?.let { bounds ->
        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
    } }
    equal("", r.receivedText, "reserved status starts empty")
    equal(1, line.maxLines, "status has one visible line")
    equal(64, lineLayout.height, "32dp fixed status height at host density two")
    equal("Last received external HID key", line.contentDescription.toString(), "received status accessible purpose")
    r.leaveRows()
    check(!r.edge(KeyEvent.ACTION_DOWN, key = 194, scan = 813))
    equal("HOST_KEYCODE_194", r.receivedText, "eligible DOWN is acknowledged without a hovered action")
    check(!r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 194, scan = 813))
    panel.refresh()
    equal("HOST_KEYCODE_194", r.receivedText, "UP and ordinary refresh preserve the last actual received key")
    equal(rowOrder, panel.children, "status update does not insert or reorder rows")
    equal(rowBounds, panel.buttons.map { it.hostVisibleRect?.let { bounds ->
        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
    } }, "status does not alter assignment hit rectangles")
    equal(64, checkNotNull(panel.childLayouts[line]).height, "status reserves the same height before and after text")
    equal(0, r.writes.size, "receipt alone does not assign or persist")
    equal(0, r.positionReads, "receipt does not sample output position")
    equal(1, r.targetReads, "only the original fresh DOWN evaluates its normal target callback")
    r.noOutput()
}

private fun receivedStatusCountsSeparateActualPressesAndExcludesRepeatsNativeAndHiddenPage() {
    val r = Rig()
    val panel = r.showPanel()
    val receiver = traceIngress(r)
    r.leaveRows()
    check(!r.edge(KeyEvent.ACTION_DOWN, key = 194, scan = 813))
    equal("HOST_KEYCODE_194", r.receivedText, "first separate physical press")
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1005, down = 1000, key = 194, scan = 813))
    equal("HOST_KEYCODE_194", r.receivedText, "duplicate same actual DOWN does not increment")
    val beforeRepeat = r.receivedCalls
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1010, down = 1000, key = 194, scan = 813,
        repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS))
    check(!r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 194, scan = 813))
    equal(beforeRepeat, r.receivedCalls, "held repeat and final release do not call status sink")
    for ((at, count) in listOf(Pair(1100L, 2), Pair(1200L, 3))) {
        check(!r.edge(KeyEvent.ACTION_DOWN, at = at, key = 194, scan = 813))
        equal("HOST_KEYCODE_194 x$count", r.receivedText, "fresh actual downTime increments same attachment/button")
        check(!r.edge(KeyEvent.ACTION_UP, at = at + 20, down = at, key = 194, scan = 813))
    }
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1300, key = 194, scan = 813, device = r.device.copy(id = 42)))
    equal("HOST_KEYCODE_194", r.receivedText, "different attachment restarts the counter despite same persisted button")
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1400, key = 195, scan = 814))
    equal("HOST_KEYCODE_195", r.receivedText, "different actual key restarts the counter")
    val beforeExcluded = r.receivedCalls
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1450, key = 83, scan = 204, device = nativePad379))
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1460, key = 26, scan = 116, device = nativePower379))
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1470, key = 194, scan = 813, flags = KeyEvent.FLAG_CANCELED))
    equal(beforeExcluded, r.receivedCalls, "native excluded and canceled DOWN never call received sink")
    equal("HOST_KEYCODE_195", r.receivedText, "excluded or canceled input cannot claim status")
    panel.visibility = View.GONE
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1500, key = 196, scan = 815))
    equal("HOST_KEYCODE_195", r.receivedText, "hidden HID page leaves status untouched")
    panel.visibility = View.VISIBLE
    r.connectionVisible = false
    receiver.connectionView.visibility = View.GONE
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1600, key = 197, scan = 816))
    equal("HOST_KEYCODE_195", r.receivedText, "hidden connection view leaves status untouched")
    equal(0, r.writes.size, "reception feedback never persists bindings")
    r.noOutput()
}

private fun throwingReceivedStatusSinkLeavesOriginalHeldRoutingAndCountsUntouched() {
    data class Outcome(val phases: List<PadGestureRecognizer.Phase>, val times: List<Pair<Long, Long>>,
                       val target: Int, val position: Int, val roots: Int, val rows: List<Int>, val writes: Int)
    fun run(throwing: Boolean): Outcome {
        val r = Rig()
        r.assign(HidAction.TAP)
        r.showPanel()
        traceIngress(r)
        r.leaveRows()
        if (throwing) r.onReceived = { throw AssertionError("host received status sink") }
        check(r.edge(KeyEvent.ACTION_DOWN))
        check(r.edge(KeyEvent.ACTION_DOWN, at = 1010, down = 1000, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS))
        check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
        equal(1, r.receivedCalls, "one eligible real DOWN calls the status sink")
        equal(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP), r.contacts.map { it.event.phase },
            "status failure cannot suppress or duplicate actual held edges")
        check(r.cancels.isEmpty() && r.functions.isEmpty() && r.scrolls.isEmpty())
        return Outcome(r.contacts.map { it.event.phase }, r.contacts.map { Pair(it.event.downTimeMs, it.event.timeMs) },
            r.targetReads, r.positionReads, r.root.visibleRectReads, r.panel!!.buttons.map { it.visibleRectReads }, r.writes.size)
    }
    equal(run(false), run(true), "received status exception cannot change original output or functional callback evaluations")
}

fun main() {
    val scenarios = linkedMapOf<String, () -> Unit>(
        "actual Android device/button facts and complete capture ownership" to ::capturesActualFactsAndConsumesWholeGesture,
        "repeat and long flags retain one actual held contact" to ::repeatsAndLongFlagStayOneHeldContact,
        "Android canceled and unsupported events retire the owned contact" to ::canceledAndUnsupportedAndroidEventsDoNotReleaseIntoTargets,
        "InputManager lifecycle, cancellation and fresh reconnect" to ::listenerLifecycleCancelsAndReconnectRetainsBindings,
        "actual Android Function and scroll edge callbacks" to ::actualFunctionAndScrollCallbacksUseAndroidEdges,
        "four visible bindings, black/light styling and registered buttons" to ::panelShowsFourAssignmentsWithExistingBlackLightControls,
        "saved move, restart readback and storage error display" to ::panelSavedMoveAndErrorsRefreshFromActualStore,
        "attachment cancellation retains complete captured key ownership" to ::attachmentCancellationRetainsCapturedUnboundReleaseOwnership,
        "removed capture consumes exact missing-device edges and fresh readdition works" to ::missingDeviceAfterRemovalConsumesOnlyActualCapturedEdges,
        "missing device before listener cancels captured owner without target UP" to ::missingDeviceBeforeListenerCancelsCapturedOwnerWithoutTargetUp,
        "changed descriptor cannot disown an old canceled capture release" to ::changedDescriptorDoesNotDisownCapturedOldRelease,
        "changed facts preserve unrelated key/scan/downTime/attachment and fresh DOWN fallthrough" to ::changedFactsUnrelatedRuntimeEdgesDoNotRetireCapture,
        "changed-facts and missing-device captured release stays consumed across nested fresh same-key capture" to ::changedFactsReleaseStaysConsumedWhenCancelCallbackStartsFreshCapture,
        "actual local settings preserve values, bounds and callbacks with HID entrance" to ::localSettingsPanelPreservesControlsAndOpensHidBindings,
        "single visible Reset atomically unassigns all actions, survives reload and supports reassignment" to ::visibleSingleResetUnassignsAllActionsAndSurvivesReload,
        "Reset retires held, capture and pending Function ownership even when persistence fails" to ::resetCancelsHeldCaptureAndPendingFunctionWorkEvenWhenSaveFails,
        "failed Reset retains all assignments, saved bytes and visible error until retry" to ::failedResetRetainsEveryStoredAssignmentAndReportsErrorUntilRetry,
        "Reset repairs malformed and empty storage with one truthful write" to ::resetRepairsMalformedAndEmptyStorageWithoutFabricatingAssignments,
        "startup repairs recorded native key83 Function and preserves all Joy-Con assignments" to ::startupRepairRemovesOnlyTheRecordedNativeFunctionAndPreservesJoyCon,
        "InputManager add/change repairs native binding and actual Joy-Con R1/A/X still dispatch" to ::listenerRepairBlocksNativeAndPreservesAllThreeJoyConActions,
        "failed legacy repair remains truthful while native HID is always blocked" to ::failedLegacyRepairKeepsRecordAndErrorButNeverDispatchesNativeHid,
        "unrelated legacy repair preserves an active external hold and captured gesture" to ::startupRepairPreservesUnrelatedActiveExternalHoldAndCapture,
        "changed eligibility retires already owned external edges and fresh excluded events fall through" to ::newlyExcludedMetadataRetiresOnlyAlreadyOwnedExternalEdges,
        "visible learning instructions explicitly request external HID buttons" to ::panelExplicitlyRequestsExternalHidButtons,
        "actual extracted native mapper preserves all recorded native keys and source qualification" to ::extractedNativeMappingRetainsRecordedNativeControls,
        "actual Receiver permits external key83 capture and Function dispatch" to ::receiverExternalSameKeyCodeRemainsEligibleForCaptureAndFunction,
        "hover row hit testing uses nonzero root offset and actual clipping" to ::hoveredRowsUseRootOffsetsAndActualVisibleClipping,
        "unavailable hidden disabled outside Reset and Back never target assignment" to ::unavailableHiddenDisabledAndNonAssignmentAreasNeverLearn,
        "row click is nonsticky and repeated actual DOWN replaces or moves saved key" to ::rowClicksNeverLatchAndEveryActualDownCanReplaceOrMoveBindings,
        "newer hovered capture across key or attachment overlap consumes old late UP" to ::newerHoveredCaptureOwnsItsReleaseAcrossOverlapAndOldLateUp,
        "actual HID page trace binds event tuple, hover, geometry, capture and save result" to ::actualPageTraceBindsRealTupleHoverGeometryCaptureAndSaveResult,
        "page-only trace preserves skipped eligibility and callback observations" to ::pageOnlyTraceAndShortCircuitEligibilityDoNotInspectSkippedCallbacks,
        "throwing diagnostics preserve outputs, callback counts and original functional exception" to ::throwingDiagnosticSinkPreservesOutputCallbacksAndOriginalFunctionalException,
        "received external DOWN status is visible without hover and reserves row geometry" to ::receivedStatusShowsActualExternalDownWithoutHoverOrRowShift,
        "received status counts distinct actual presses while excluding held native and hidden-page input" to ::receivedStatusCountsSeparateActualPressesAndExcludesRepeatsNativeAndHiddenPage,
        "throwing received status callback preserves original held routing and evaluation counts" to ::throwingReceivedStatusSinkLeavesOriginalHeldRoutingAndCountsUntouched,
    )
    val excluded = linkedMapOf(
        "recorded internal Rokid pad" to nativePad379,
        "recorded internal qpnp_pon" to nativePower379,
        "recorded virtual keyboard" to virtual379,
        "virtual keyboard falsely marked external" to virtual379.copy(isExternal = true),
        "Rokid pad falsely marked external" to nativePad379.copy(isExternal = true),
        "qpnp_pon falsely marked external" to nativePower379.copy(isExternal = true),
        "unrelated internal keyboard" to InputDevice(45, "host:internal:keyboard", 17, 29,
            "Internal keyboard fixture", isExternal = false),
    )
    for ((name, device) in excluded)
        scenarios["excluded $name never learns or dispatches a saved HID Function"] = {
            excludedDeviceCannotLearnOrDispatchHid(device)
        }
    for (capturing in listOf(false, true)) {
        for (saveFailure in listOf(false, true))
            scenarios["actual Receiver native key83 CONTACT capture=$capturing failedRepair=$saveFailure"] = {
                receiverNativeKey83RetainsContactMarkerRoute(capturing, saveFailure)
            }
    }
    val changedFacts = linkedMapOf<String, (InputDevice) -> InputDevice>(
        "descriptor" to { it.copy(descriptor = "actual:changed:descriptor") },
        "vendor" to { it.copy(vendorId = 99) },
        "product" to { it.copy(productId = 100) },
    )
    for ((fact, change) in changedFacts) {
        for (edge in listOf("UP", "repeat", "canceled UP", "unsupported phase")) {
            scenarios["pre-listener changed $fact captured $edge retirement and fresh binding"] = {
                changedFactsBeforeListenerRetireOnlyTheCapturedGesture(change, edge)
            }
        }
    }
    val failures = mutableListOf<String>()
    for ((name, scenario) in scenarios) {
        try {
            scenario()
            println("PASS: $name")
        } catch (failure: Throwable) {
            failures.add(name)
            System.err.println("FAIL: $name")
            failure.printStackTrace()
        }
    }
    check(failures.isEmpty()) { "${failures.size}/${scenarios.size} HID Android/panel host scenarios failed: ${failures.joinToString()}" }
    println("PASS: ${scenarios.size} HID Android/panel host scenarios. Android API doubles only; no device delivery or head-pointer proof.")
}
