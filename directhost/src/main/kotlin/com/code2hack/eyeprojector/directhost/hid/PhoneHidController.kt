package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.directhost.activation.ActivationCause
import com.code2hack.eyeprojector.directhost.activation.PhoneHidActivation
import com.code2hack.eyeprojector.directhost.activation.PhoneHidCapture
import com.code2hack.eyeprojector.directhost.activation.PhoneHidDisposition
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase
import com.code2hack.eyeprojector.protocol.PhoneHidGesture
import com.code2hack.eyeprojector.protocol.PhoneHidGestureRecognizer

/** Capturing a route submits the original DOWN once. The returned route is never resolved again. */
internal interface PhoneHidRoutes {
    fun capture(down: PhoneHidEdge): PhoneHidRoute?
    fun capture(down: PhoneHidEdge, admission: PhoneHidCapture): PhoneHidRoute? = capture(down)
    fun capture(down: PhoneHidEdge, admission: PhoneHidCapture,
        original: com.code2hack.eyeprojector.directhost.input.PhoneHidOriginal?): PhoneHidRoute? = capture(down, admission)
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
    internal var activation: PhoneHidActivation = PhoneHidActivation.Unwired,
    private val userControl: () -> Boolean = { true },
    private val recognizeInput: () -> Boolean = { true },
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    data class Event(
        val attachmentId: Int, val binding: PhoneHidBinding, val phase: PhoneHidPhase,
        val downTimeMs: Long, val eventTimeMs: Long, val repeatCount: Int = 0,
        val pressId: Long = downTimeMs,
        val discarded: Boolean = false,
        val original: com.code2hack.eyeprojector.directhost.input.PhoneHidOriginal? = null,
    )
    private data class Raw(val attachment: Int, val key: Int, val scan: Int, val down: Long, val pressId: Long = down)
    private data class RawKey(val attachment: Int, val key: Int, val scan: Int)
    private data class Decision(val down: Long, val pressId: Long, val handled: Boolean)
    private data class Key(val attachment: Int, val button: PhoneHidButton)
    private class Capture(val event: Event, val action: PhoneHidAction)
    private class WakeAdmission(val event: Event) { var canceled = false }
    private class Press(val event: Event, val action: PhoneHidAction, val admission: Long) {
        var route: PhoneHidRoute? = null
        var terminal: Event? = null
        var canceled = false
    }
    private var capture: Capture? = null
    private val presses = mutableMapOf<Raw, Press>()
    private val admissions = mutableMapOf<Key, Press>()
    private var tapAdmission: Press? = null
    // Retain the latest released route per physical button to cancel pending complete gestures.
    private val releasedGestures = mutableMapOf<Key, Press>()
    private val learningPresses = mutableMapOf<Raw, Capture>()
    private val learning = PhoneHidGestureRecognizer<Capture>(now,
        { pageVisible && selected == it.action && capture === it }, schedule,
        { gesture, original ->
            capture = null
            learningPresses.keys.forEach(retired::add)
            learningPresses.clear()
            if (!original.action.learnsSingleOnly || gesture == PhoneHidGesture.SINGLE) {
                selected = null
                val control = original.event.original
                if (control == null || control.consumeUserControl()) save {
                    if (control == null || control.consumeUserControl())
                        bindings.assign(original.action, original.event.binding.copy(gesture = gesture))
                }
            }
            publish()
        })
    private var nextAdmission = 0L
    private val constructing = mutableSetOf<Press>()
    private val retired = mutableSetOf<Raw>()
    private val waking = mutableSetOf<Raw>()
    private val wakeAdmissions = mutableSetOf<WakeAdmission>()
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
        if (!userControl()) return
        resumePage()
    }

    /** Activity restoration is presentation housekeeping; its user navigation already gates upstream. */
    fun resumePage() {
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
        if (!userControl()) return
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
        if (!userControl()) return
        selected = null
        val old = retireInput()
        // Queue this reset before an old-route callback can complete a newer assignment.
        save { bindings.resetAll() }
        old.forEach(::cancel)
    }

    /** Raw correlation precedes device lookup, eligibility and persistent identity reconstruction. */
    fun consumeRetired(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, pressId: Long = down): Boolean {
        val raw = Raw(device, key, scan, down, pressId)
        if (raw !in retired) return false
        if (phase == PhoneHidPhase.UP) retired.remove(raw)
        return true
    }

    /** The original wake disposition survives readiness, missing metadata and overlapping presses. */
    fun consumeWake(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, pressId: Long = down): Boolean {
        val raw = Raw(device, key, scan, down, pressId)
        if (raw !in waking) return false
        if (phase != PhoneHidPhase.DOWN) { waking.remove(raw); retired.add(raw) }
        return true
    }

    /** Attachment shares one original usability decision; it creates no raw key gesture or target edge. */
    fun externalAttached() {
        val source = activation
        requestActivation(ActivationCause.HID_ATTACHMENT, source, source.capture())
    }

    private fun requestActivation(cause: ActivationCause, source: PhoneHidActivation = activation, admission: PhoneHidCapture? = null) {
        try { if (admission == null) source.request(cause) else source.request(cause, admission) } catch (_: Exception) { /* A handled press never falls back to target input. */ }
    }

    /** A match means return false at ingress, before rebuilding possibly changed device facts. */
    fun consumePassed(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, pressId: Long = down): Boolean {
        val raw = Raw(device, key, scan, down, pressId)
        if (raw !in passed && decision(raw) != false) return false
        if (phase != PhoneHidPhase.DOWN) passed.remove(raw)
        return true
    }

    fun consumeUnavailable(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, repeatCount: Int = 0): Boolean {
        val raw = Raw(device, key, scan, down)
        if (consumeWake(device, key, scan, down, phase)) return true
        if (consumePassed(device, key, scan, down, phase)) return false
        val oldCapture = learningPresses[raw]
        val press = presses.remove(raw) ?: constructing.firstOrNull { raw(it.event) == raw }
        if (oldCapture == null && press == null) {
            if (consumeRetired(device, key, scan, down, phase)) return true
            decision(raw)?.let { return it }
            if (phase == PhoneHidPhase.DOWN && repeatCount == 0) remember(raw, false)
            return false
        }
        if (oldCapture != null) { capture = null; learningPresses.remove(raw); learning.cancel(raw) }
        retired.add(raw)
        if (press != null) cancel(press)
        consumeRetired(device, key, scan, down, phase)
        publish()
        return true
    }

    fun consumeChanged(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase,
                       identity: PhoneHidDeviceIdentity): Boolean {
        val raw = Raw(device, key, scan, down)
        if (consumeWake(device, key, scan, down, phase)) return true
        val old = learningPresses[raw]?.event ?: presses[raw]?.event
            ?: constructing.firstOrNull { raw(it.event) == raw }?.event ?: return false
        if (old.binding.button.device == identity) return false
        return consumeUnavailable(device, key, scan, down, phase)
    }

    fun deviceRemoved(device: Int) {
        wakeAdmissions.filter { it.event.attachmentId == device }.forEach { it.canceled = true }
        val wake = waking.filter { it.attachment == device }
        waking.removeAll(wake.toSet()); retired.addAll(wake)
        capture?.takeIf { it.event.attachmentId == device }?.let {
            retired.add(raw(it.event)); capture = null; learning.cancel()
        }
        val old = (presses.values + constructing + releasedGestures.values)
            .filter { it.event.attachmentId == device }.distinct()
        presses.keys.removeAll { it.attachment == device }
        admissions.keys.removeAll { it.attachment == device }
        if (tapAdmission?.event?.attachmentId == device) tapAdmission = null
        releasedGestures.keys.removeAll { it.attachment == device }
        learningPresses.keys.removeAll { it.attachment == device }
        old.forEach { retired.add(raw(it.event)); it.canceled = true }
        old.forEach(::cancel)
        publish()
    }

    fun accept(event: Event): Boolean {
        val raw = raw(event)
        if (event.discarded || !recognizeInput())
            return discardRaw(raw.attachment, raw.key, raw.scan, raw.down, event.phase, raw.pressId)
        if (consumeRetired(raw.attachment, raw.key, raw.scan, raw.down, event.phase, raw.pressId)) return true
        if (consumeWake(raw.attachment, raw.key, raw.scan, raw.down, event.phase, raw.pressId)) return true
        if (consumePassed(raw.attachment, raw.key, raw.scan, raw.down, event.phase, raw.pressId)) return false
        val captured = learningPresses[raw]
        if (captured != null) {
            if (event.phase != PhoneHidPhase.DOWN) {
                learningPresses.remove(raw)
                retired.add(raw)
                if (event.phase == PhoneHidPhase.UP) learning.up(raw, event.eventTimeMs)
                else { learning.cancel(raw); capture = null; publish() }
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
            else old.route?.let { rememberReleasedRoute(old); submit(it, edge(old, event)) }
            return true
        }
        if (old != null) return true
        if (event.repeatCount != 0) return decision(raw) == true
        val key = Key(event.attachmentId, event.binding.button)
        decision(raw)?.let { return it }
        wakeAdmissions.filter { sameButton(it.event, event) }.forEach { it.canceled = true }
        val activation = this.activation // Capture the original local port before cancellation/feedback reentry.
        val target = selected.takeIf { pageVisible }
        if (target != null && !(event.original?.consumeUserControl() ?: userControl()))
            return discardRaw(raw.attachment,raw.key,raw.scan,raw.down,event.phase,raw.pressId)
        val qualification = activation.capture() // Exactly once, after learning admission and before feedback/binding lookup.
        if (target != null) {
            remember(raw, true)
            val previous = capture
            val next = if (previous != null && previous.action == target && sameButton(previous.event, event)) previous
                else {
                    learning.cancel()
                    learningPresses.keys.forEach(retired::add)
                    learningPresses.clear()
                    Capture(event, target)
                }
            capture = next
            learningPresses[raw] = next
            learning.down(raw, Pair(event.attachmentId, event.binding.button), next, event.eventTimeMs)
            if (qualification.disposition != PhoneHidDisposition.USABLE) requestActivation(ActivationCause.HID_PRESS, activation, qualification)
            received(event)
            publish()
            return true
        }
        if (qualification.disposition != PhoneHidDisposition.USABLE) {
            remember(raw, true)
            waking.add(raw) // Before request/feedback, either of which may reenter with release or Stop.
            val wake = WakeAdmission(event).also(wakeAdmissions::add)
            val previous = (presses.values + constructing + releasedGestures.values)
                .filter { sameButton(it.event, event) }.distinct()
            previous.forEach { old ->
                presses.remove(raw(old.event)); retired.add(raw(old.event)); old.canceled = true
                if (admissions[key] === old) admissions.remove(key)
                if (tapAdmission === old) tapAdmission = null
                releasedGestures.entries.removeAll { it.value === old }
            }
            try {
                previous.forEach(::cancel)
                // A cancel callback can end this key, Stop, or admit a newer same-key DOWN.
                // UP alone does not cancel the already-observed wake request; Stop/replacement does.
                if (!wake.canceled) requestActivation(ActivationCause.HID_PRESS, activation, qualification)
            } finally { wakeAdmissions.remove(wake) }
            received(event); publish()
            return true
        }
        val assignment = bindings.assignment(event.binding.button)
        val action = assignment?.first
        remember(raw, action != null)
        if (action == null) { received(event); publish(); return false }
        // Replace this same physical key's old incomplete press, preserving every old release.
        val previous = presses.filter { (_, p) -> sameButton(p.event, event) }.values.toList()
        val press = Press(event.copy(binding = assignment.second), action, ++nextAdmission)
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
        val route = try { routes.capture(edge(press, event), qualification, event.original) } catch (_: Exception) { null }
        press.route = route
        constructing.remove(press)
        if (route != null) {
            if (!current()) cancel(press)
            else press.terminal?.let { rememberReleasedRoute(press); submit(route, edge(press, it)) }
        }
        if (admissions[key] === press) admissions.remove(key)
        return true // Bound ownership is independent of projection availability/admission.
    }

    fun cancelInput() {
        retireInput().forEach(::cancel)
    }

    /** Discard the action and retire only a route already created by this exact original DOWN. */
    fun discardRaw(device: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, pressId: Long = down): Boolean {
        val raw = Raw(device,key,scan,down,pressId)
        remember(raw,true)
        retired.add(raw)
        learningPresses.remove(raw)?.let { learning.cancel(raw); capture=null }
        val old=presses.remove(raw)
        if(old!=null) {
            old.canceled=true
            admissions.entries.removeAll { it.value===old }
            if(tapAdmission===old)tapAdmission=null
            releasedGestures.entries.removeAll { it.value === old }
            // The original proxy receives CANCEL metadata only: no new input action.
            cancel(old)
        }
        if(phase!=PhoneHidPhase.DOWN)retired.remove(raw)
        return true
    }

    fun presentationLost() { retireInput(preserveCapture = true).forEach(::cancel) }

    private fun retireInput(preserveCapture: Boolean = false): List<Press> {
        wakeAdmissions.forEach { it.canceled = true }
        retired.addAll(waking); waking.clear()
        if (!preserveCapture) {
            learningPresses.keys.forEach(retired::add)
            learningPresses.clear()
            learning.cancel()
            capture = null
        }
        val old = (presses.values + constructing + releasedGestures.values).distinct()

        presses.entries.removeAll { it.value in old }
        admissions.entries.removeAll { it.value in old }; tapAdmission = null
        releasedGestures.clear()
        old.forEach { retired.add(raw(it.event)); it.canceled = true }
        return old
    }

    private fun cancel(press: Press) {
        press.canceled = true
        releasedGestures.entries.removeAll { it.value === press }
        val route = press.route ?: return
        press.route = null
        try { routes.cancel(route, now()) } catch (_: Exception) { /* Ownership stays retired. */ }
    }
    private fun submit(route: PhoneHidRoute, event: PhoneHidEdge) {
        try { routes.submit(route, event) } catch (_: Exception) { /* A failed route is never retargeted. */ }
    }
    private fun rememberReleasedRoute(press: Press) {
        if (!press.action.learnsSingleOnly && press.route != null)
            releasedGestures[Key(press.event.attachmentId, press.event.binding.button)] = press
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
        ?.takeIf { it.down == raw.down && it.pressId == raw.pressId }?.handled
    private fun remember(raw: Raw, handled: Boolean) {
        lastDowns[RawKey(raw.attachment, raw.key, raw.scan)] = Decision(raw.down, raw.pressId, handled)
        if (!handled) passed.add(raw)
    }
    private fun raw(event: Event) = Raw(event.attachmentId, event.binding.button.keyCode, event.binding.button.scanCode, event.downTimeMs, event.pressId)
    private fun sameButton(a: Event, b: Event) = a.attachmentId == b.attachmentId && a.binding.button == b.binding.button
    private fun edge(press: Press, event: Event) = PhoneHidEdge(press.action, event.phase, event.attachmentId,
        event.binding.button.keyCode, event.binding.button.scanCode, event.downTimeMs, event.eventTimeMs, event.repeatCount, press.event.binding.gesture)
}
