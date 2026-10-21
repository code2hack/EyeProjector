package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.PriorityQueue

private fun equal(expected: Any?, actual: Any?, reason: String) {
    check(expected == actual) { "$reason: expected <$expected>, actual <$actual>" }
}

private class Rig(initialRecord: String? = null, save: Boolean = true) {
    data class Timer(val deadline: Long, val order: Int, val callback: () -> Unit, var canceled: Boolean = false)
    data class Contact(val owner: Any, val event: PadGestureRecognizer.Event)
    data class Cancel(val owner: Any, val time: Long)
    data class Scroll(val pixels: Int, val point: PointerPosition, val time: Long)
    var now = 1000L
    var context: Any? = "local-bindings-view"
    var record = initialRecord
    var panel: HidBindingsPanel? = null
    var backs = 0
    var changed = 0
    var onChanged: () -> Unit = {}
    val android = Context()
    val timers = mutableListOf<Timer>()
    private val queue = PriorityQueue<Timer>(compareBy<Timer> { it.deadline }.thenBy { it.order })
    val contacts = mutableListOf<Contact>()
    val cancels = mutableListOf<Cancel>()
    val scrolls = mutableListOf<Scroll>()
    val functions = mutableListOf<FunctionGestureRecognizer.Action>()
    val point = PointerPosition(100f, 200f, true)
    val bindings = HidBindings({ record }) { if (save) record = it; save }
    val input = HidKeyInput(bindings, { now }, { context }, { at, callback ->
        val timer = Timer(at, timers.size, callback)
        timers.add(timer); queue.add(timer)
        val cancel: () -> Unit = { timer.canceled = true }
        cancel
    }, { point }, { owner, event -> contacts.add(Contact(owner, event)) },
        { owner, at -> cancels.add(Cancel(owner, at)) },
        { pixels, position, at -> scrolls.add(Scroll(pixels, position, at)) },
        { functions.add(it) }, { changed++; panel?.refresh(); onChanged() })
    val adapter = AndroidHidInput(android, input)
    val device = InputDevice(41, "actual:descriptor:17", 0x1209, 0x0017, "Actual HID peripheral")

    fun showPanel(): HidBindingsPanel = HidBindingsPanel(android, bindings, input) { backs++ }.also { panel = it }
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
}

private fun capturesActualFactsAndConsumesWholeGesture() {
    val r = Rig()
    val panel = r.showPanel()
    check(panel.buttons[0].performClick())
    equal(HidAction.TAP, r.input.selected, "Tap button begins capture")
    check(panel.buttons[0].isSelected)
    equal("Press and release a button for Tap.", r.message, "capture instruction")
    check(r.edge(KeyEvent.ACTION_DOWN, key = 194, scan = 813))
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
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 196, scan = 701))
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
            check(r.functions.isEmpty())
        }
        val deadline = r.now + 500
        r.advance(deadline - 1)
        check(r.functions.isEmpty())
        r.advance(deadline)
        equal(listOf(FunctionGestureRecognizer.Action.entries[presses - 1]), r.functions, "actual Function classification")
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
    equal(7, panel.children.size, "title, message, four assignments and Back")
    equal("HID key bindings", (panel.children[0] as TextView).text.toString(), "panel title")
    equal(22f, (panel.children[0] as TextView).textSize, "panel title size")
    equal(listOf("Tap: Unassigned", "Scroll down: Unassigned", "Scroll up: Unassigned", "Function button: Unassigned", "Back"),
        panel.buttons.map { it.text.toString() }, "visible assignment labels")
    equal("Select a control, then press and release its HID button.", r.message, "initial instruction")
    equal(panel.children.drop(2), panel.buttons, "every interactive button registered for existing local hit testing")
    for (child in panel.children) equal(Color.WHITE, (child as TextView).textColor, "light text")
    for (button in panel.buttons) {
        val background = button.background as GradientDrawable
        equal(Color.BLACK, background.fillColor, "black control")
        equal(1, background.strokeWidth, "control border width")
        equal(Color.WHITE, background.strokeColor, "light control border")
        equal(96, button.minimumHeight, "48dp minimum at host density two")
    }
    for (action in HidAction.entries) {
        check(panel.buttons[action.ordinal].performClick())
        equal(action, r.input.selected, "assignment button begins its own capture")
        equal(listOf(action.ordinal), panel.buttons.indices.filter { panel.buttons[it].isSelected }, "selected capture display")
    }
    check(panel.buttons.last().performClick())
    equal(1, r.backs, "Back forwards callback")
    r.noOutput()
}

private fun panelSavedMoveAndErrorsRefreshFromActualStore() {
    val r = Rig()
    val panel = r.showPanel()
    panel.buttons[0].performClick()
    check(r.edge(KeyEvent.ACTION_DOWN))
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000))
    panel.buttons[1].performClick()
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
    equal("Saved HID bindings could not be read. Assign a button to replace them.", malformed.message, "read error display")
    val failed = Rig(save = false)
    val failedPanel = failed.showPanel()
    failedPanel.buttons[3].performClick()
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
        panel.buttons[0].performClick()
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
    panel.buttons[0].performClick()
    check(fresh.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    fresh.android.inputManager.remove(fresh.device.id)
    // Removal can lose the old UP entirely. A fresh real DOWN on a reused runtime ID
    // must still begin and complete a new capture of the same persistent button.
    val reattached = fresh.device.copy(name = "Reattached peripheral, reused Android ID")
    fresh.android.inputManager.add(reattached.id)
    panel.buttons[2].performClick()
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
    panel.buttons[0].performClick()
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
    panel.buttons[3].performClick()
    check(!r.edge(KeyEvent.ACTION_DOWN, at = 1050, key = 4, scan = 158,
        device = null, attachmentId = reattached.id))
    equal(HidAction.FUNCTION, r.input.selected, "unowned missing-device DOWN cannot begin capture")
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158, device = reattached))
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158, device = reattached))
    equal(r.binding(reattached, 4, 158), r.bindings.binding(HidAction.FUNCTION), "fresh readdition facts bind")
    r.noOutput()
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, key = 4, scan = 158, device = reattached))
    check(r.edge(KeyEvent.ACTION_UP, at = 1220, down = 1200, key = 4, scan = 158, device = reattached))
    r.advance(1720)
    equal(listOf(FunctionGestureRecognizer.Action.SINGLE), r.functions, "fresh readdition dispatch works")
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
    captured.showPanel().buttons[0].performClick()
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
    panel.buttons[0].performClick()
    check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
    r.android.inputManager.change(r.device.id)
    val changed = r.device.copy(descriptor = "actually:changed:descriptor", vendorId = 99, productId = 100,
        name = "Actual changed device facts")
    check(r.edge(KeyEvent.ACTION_UP, at = 1020, down = 1000, key = 4, scan = 158, device = changed))
    equal(null, r.bindings.binding(HidAction.TAP), "old canceled release cannot capture new descriptor")
    r.noOutput()
    panel.buttons[0].performClick()
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
    panel.buttons[0].performClick()
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
    panel.buttons[3].performClick()
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1100, key = 4, scan = 158, device = changed))
    check(r.edge(KeyEvent.ACTION_UP, at = 1120, down = 1100, key = 4, scan = 158, device = changed))
    equal(r.binding(changed, 4, 158), r.bindings.binding(HidAction.FUNCTION), "fresh DOWN captures actual changed facts")
    equal(null, r.bindings.binding(HidAction.TAP), "retired original capture stays unassigned")
    equal(null, r.input.selected, "fresh changed-facts capture completes")
    r.noOutput()
    check(r.edge(KeyEvent.ACTION_DOWN, at = 1200, key = 4, scan = 158, device = changed))
    check(r.edge(KeyEvent.ACTION_UP, at = 1220, down = 1200, key = 4, scan = 158, device = changed))
    r.advance(1720)
    equal(listOf(FunctionGestureRecognizer.Action.SINGLE), r.functions, "fresh changed-facts binding dispatches")
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
        "fresh DOWN" to Unrelated(down = 1010, phase = KeyEvent.ACTION_DOWN),
    )
    for ((reason, edge) in cases) {
        val r = Rig()
        r.showPanel().buttons[0].performClick()
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
        panel.buttons[0].performClick()
        check(r.edge(KeyEvent.ACTION_DOWN, key = 4, scan = 158))
        var nestedDownConsumed: Boolean? = null
        r.onChanged = {
            // The old capture's cancellation synchronously publishes a selection
            // change. A newer capture can start in that callback on the same route.
            r.onChanged = {}
            check(panel.buttons[2].performClick())
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
    )
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
