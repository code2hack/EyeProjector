package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase

/** Capturing a route submits the original DOWN once. The returned route is never resolved again. */
internal interface PhoneHidRoutes {
    fun capture(down: PhoneHidEdge): PhoneHidRoute?
    fun submit(route: PhoneHidRoute, edge: PhoneHidEdge)
    fun cancel(route: PhoneHidRoute, eventTimeMs: Long)
}

/** One serialized ingress; persistence runs on a separate single writer and publishes completion here. */
internal class PhoneHidController(
    val bindings: PhoneHidBindings,
    private val routes: PhoneHidRoutes,
    private val persist: (operation: () -> Unit, complete: () -> Unit) -> Unit,
    private val now: () -> Long,
    private val changed: () -> Unit = {},
) {
    data class Event(
        val attachmentId: Int, val binding: PhoneHidBinding, val phase: PhoneHidPhase,
        val downTimeMs: Long, val eventTimeMs: Long, val repeatCount: Int = 0,
    )
    private data class Raw(val attachment: Int, val key: Int, val scan: Int, val down: Long)
    private data class RawKey(val attachment: Int, val key: Int, val scan: Int)
    private data class Decision(val down: Long, val handled: Boolean)
    private data class Key(val attachment: Int, val button: PhoneHidButton)
    private class Capture(val event: Event, val action: PhoneHidAction)
    private class Press(val event: Event, val action: PhoneHidAction, val admission: Long) {
        var route: PhoneHidRoute? = null
        var terminal: Event? = null
        var canceled = false
    }
    private var capture: Capture? = null
    private val presses = mutableMapOf<Raw, Press>()
    private val admissions = mutableMapOf<Key, Press>()
    private var tapAdmission: Press? = null
    // UP ends physical ownership; the latest submitted Function route still owns quiet recognition.
    private var releasedFunction: Press? = null
    private var nextAdmission = 0L
    private val constructing = mutableSetOf<Press>()
    private val retired = mutableSetOf<Raw>()
    private val observedDowns = mutableSetOf<Raw>()
    // Keep the last decision per physical button and every still-open passed gesture.
    // Map publication, page changes and cancellation cannot adopt a DOWN sent to the foreground.
    private val lastDowns = mutableMapOf<RawKey, Decision>()
    private val passed = mutableSetOf<Raw>()
    private var receivedKey: Pair<Int, PhoneHidButton>? = null
    private var receivedCount = 0L
    var receivedText = ""; private set
    var selected: PhoneHidAction? = null; private set
    var pageVisible = false; private set
    var connected = false; private set
    var saving = 0; private set
    val capturing: Boolean get() = capture != null

    fun openPage() {
        pageVisible = true
        selected = null
        observedDowns.clear(); receivedKey = null; receivedCount = 0; receivedText = ""
        cancelInput()
        publish()
    }

    fun closePage() {
        if (!pageVisible) return // An unrelated Activity stop does not own operational HID input.
        pageVisible = false
        selected = null
        cancelInput()
        publish()
    }

    fun select(action: PhoneHidAction) {
        if (!pageVisible) return
        selected = action // New selection is visible before old-route cancellation callbacks.
        cancelInput()
        publish()
    }

    fun connection(value: Boolean) {
        connected = value
        if (!value) { selected = null; cancelInput() }
        publish()
    }

    fun resetAll() {
        selected = null
        val old = retireInput()
        // Queue this reset before an old-route callback can complete a newer assignment.
        save { bindings.resetAll() }
        old.forEach(::cancel)
    }

    /** Raw correlation precedes device lookup, eligibility and persistent identity reconstruction. */
    fun consumeRetired(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase): Boolean {
        val raw = Raw(device, key, scan, down)
        if (raw !in retired) return false
        if (phase == PhoneHidPhase.UP) retired.remove(raw)
        return true
    }

    /** A match means return false at ingress, before rebuilding possibly changed device facts. */
    fun consumePassed(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase): Boolean {
        val raw = Raw(device, key, scan, down)
        if (raw !in passed && decision(raw) != false) return false
        if (phase != PhoneHidPhase.DOWN) passed.remove(raw)
        return true
    }

    fun consumeUnavailable(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, repeatCount: Int = 0): Boolean {
        val raw = Raw(device, key, scan, down)
        if (consumePassed(device, key, scan, down, phase)) return false
        val oldCapture = capture?.takeIf { raw(it.event) == raw }
        val press = presses.remove(raw) ?: constructing.firstOrNull { raw(it.event) == raw }
        if (oldCapture == null && press == null) {
            if (consumeRetired(device, key, scan, down, phase)) return true
            decision(raw)?.let { return it }
            if (phase == PhoneHidPhase.DOWN && repeatCount == 0) remember(raw, false)
            return false
        }
        if (oldCapture != null) capture = null
        retired.add(raw)
        if (press != null) cancel(press)
        consumeRetired(device, key, scan, down, phase)
        publish()
        return true
    }

    fun consumeChanged(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase,
                       identity: PhoneHidDeviceIdentity): Boolean {
        val raw = Raw(device, key, scan, down)
        val old = capture?.event?.takeIf { raw(it) == raw } ?: presses[raw]?.event
            ?: constructing.firstOrNull { raw(it.event) == raw }?.event ?: return false
        if (old.binding.button.device == identity) return false
        return consumeUnavailable(device, key, scan, down, phase)
    }

    fun deviceRemoved(device: Int) {
        capture?.takeIf { it.event.attachmentId == device }?.let {
            retired.add(raw(it.event)); capture = null
        }
        val old = (presses.values + constructing + listOfNotNull(releasedFunction))
            .filter { it.event.attachmentId == device }.distinct()
        presses.keys.removeAll { it.attachment == device }
        admissions.keys.removeAll { it.attachment == device }
        if (tapAdmission?.event?.attachmentId == device) tapAdmission = null
        if (releasedFunction?.event?.attachmentId == device) releasedFunction = null
        old.forEach { retired.add(raw(it.event)); it.canceled = true }
        old.forEach(::cancel)
        publish()
    }

    fun accept(event: Event): Boolean {
        val raw = raw(event)
        if (consumeRetired(raw.attachment, raw.key, raw.scan, raw.down, event.phase)) return true
        if (consumePassed(raw.attachment, raw.key, raw.scan, raw.down, event.phase)) return false
        val captured = capture
        if (captured != null && raw(captured.event) == raw) {
            if (event.phase != PhoneHidPhase.DOWN) {
                capture = null // A save/publication callback cannot revive this physical capture.
                if (event.phase == PhoneHidPhase.UP && event.eventTimeMs >= event.downTimeMs)
                    save { bindings.assign(captured.action, captured.event.binding) }
                else { retired.add(raw); publish() }
            }
            return true
        }
        val old = presses[raw]
        if (event.phase != PhoneHidPhase.DOWN) {
            if (old == null) return decision(raw) == true
            presses.remove(raw)
            retired.add(raw)
            old.terminal = event
            if (event.phase == PhoneHidPhase.CANCEL || event.eventTimeMs < event.downTimeMs) cancel(old)
            else old.route?.let { rememberReleasedFunction(old); submit(it, edge(old, event)) }
            return true
        }
        if (old != null) return true
        if (event.repeatCount != 0) return decision(raw) == true
        val key = Key(event.attachmentId, event.binding.button)
        decision(raw)?.let { return it }
        val action = bindings.action(event.binding.button)
        val target = selected.takeIf { pageVisible }
        remember(raw, target != null || action != null) // Before any received/cancel/output callback.
        if (target != null) {
            val previous = capture
            // Publish DOWN ownership before received feedback can reenter with its UP/newer DOWN.
            capture = Capture(event, target)
            previous?.let { retired.add(raw(it.event)) }
            received(event)
            publish()
            return true
        }
        if (action == null) { received(event); publish(); return false }
        // Replace this same physical key's old incomplete press, preserving every old release.
        val previous = presses.filter { (_, p) -> sameButton(p.event, event) }.values.toList()
        val press = Press(event, action, ++nextAdmission)
        previous.forEach { presses.remove(raw(it.event)); retired.add(raw(it.event)) }
        presses[raw] = press
        admissions[key] = press
        if (action == PhoneHidAction.TAP) tapAdmission = press
        constructing.add(press)
        received(event)
        publish()
        previous.forEach(::cancel)
        fun current() = admissions[key] === press && !press.canceled &&
            (action != PhoneHidAction.TAP || tapAdmission === press)
        if (!current()) {
            constructing.remove(press)
            if (presses[raw] === press) presses.remove(raw)
            if (admissions[key] === press) admissions.remove(key)
            retired.add(raw)
            return true
        }
        val route = try { routes.capture(edge(press, event)) } catch (_: Exception) { null }
        press.route = route
        constructing.remove(press)
        if (route != null) {
            if (!current()) cancel(press)
            else press.terminal?.let { rememberReleasedFunction(press); submit(route, edge(press, it)) }
        }
        if (admissions[key] === press) admissions.remove(key)
        return true // Bound ownership is independent of projection availability/admission.
    }

    fun cancelInput() {
        retireInput().forEach(::cancel)
    }

    private fun retireInput(): List<Press> {
        capture?.let { retired.add(raw(it.event)) }
        capture = null
        val old = (presses.values + constructing + listOfNotNull(releasedFunction)).distinct()
        presses.clear()
        admissions.clear(); tapAdmission = null
        releasedFunction = null
        old.forEach { retired.add(raw(it.event)); it.canceled = true }
        return old
    }

    private fun cancel(press: Press) {
        press.canceled = true
        if (releasedFunction === press) releasedFunction = null
        val route = press.route ?: return
        press.route = null
        try { routes.cancel(route, now()) } catch (_: Exception) { /* Ownership stays retired. */ }
    }
    private fun submit(route: PhoneHidRoute, event: PhoneHidEdge) {
        try { routes.submit(route, event) } catch (_: Exception) { /* A failed route is never retargeted. */ }
    }
    private fun rememberReleasedFunction(press: Press) {
        if (press.action == PhoneHidAction.FUNCTION && press.route != null &&
            (releasedFunction?.admission ?: Long.MIN_VALUE) < press.admission) releasedFunction = press
    }
    private fun save(operation: () -> Unit) {
        saving++
        persist(operation) { saving--; publish() }
        publish()
    }
    private fun publish() { try { changed() } catch (_: Exception) { /* Feedback cannot alter input ownership. */ } }
    private fun received(event: Event) {
        val raw = raw(event)
        if (!pageVisible || !observedDowns.add(raw)) return
        val key = Pair(event.attachmentId, event.binding.button)
        receivedCount = if (receivedKey == key) receivedCount + 1 else 1
        receivedKey = key
        receivedText = if (receivedCount == 1L) event.binding.keyLabel else "${event.binding.keyLabel} x$receivedCount"
    }
    private fun decision(raw: Raw): Boolean? = lastDowns[RawKey(raw.attachment, raw.key, raw.scan)]
        ?.takeIf { it.down == raw.down }?.handled
    private fun remember(raw: Raw, handled: Boolean) {
        lastDowns[RawKey(raw.attachment, raw.key, raw.scan)] = Decision(raw.down, handled)
        if (!handled) passed.add(raw)
    }
    private fun raw(event: Event) = Raw(event.attachmentId, event.binding.button.keyCode, event.binding.button.scanCode, event.downTimeMs)
    private fun sameButton(a: Event, b: Event) = a.attachmentId == b.attachmentId && a.binding.button == b.binding.button
    private fun edge(press: Press, event: Event) = PhoneHidEdge(press.action, event.phase, event.attachmentId,
        event.binding.button.keyCode, event.binding.button.scanCode, event.downTimeMs, event.eventTimeMs, event.repeatCount)
}
