package com.code2hack.eyeprojector.directrg

/** Actual HID edges stay separate from Rokid marker/summary normalization. */
internal class HidKeyInput(
    private val bindings: HidBindings,
    private val now: () -> Long,
    private val context: () -> Any?,
    schedule: (Long, () -> Unit) -> (() -> Unit),
    private val position: () -> PointerPosition,
    private val contact: (Any, PadGestureRecognizer.Event) -> Unit,
    private val cancelContact: (Any, Long) -> Unit,
    private val scroll: (Int, PointerPosition, Long) -> Unit,
    function: (FunctionGestureRecognizer.Action) -> Unit,
    private val changed: () -> Unit = {},
) {
    enum class Phase { DOWN, UP, CANCEL }
    data class Event(
        val attachmentId: Int, val binding: HidBinding, val phase: Phase,
        val downTimeMs: Long, val timeMs: Long, val repeat: Int = 0,
    )
    private data class Key(val attachment: Int, val button: HidButton)
    private class Press(val event: Event, val action: HidAction, val context: Any) { val owner = Any() }
    private data class Capture(val action: HidAction, val event: Event)
    private val presses = mutableMapOf<Key, Press>()
    // A canceled capture still owns its release; it must never become ordinary Back/input.
    private val suppressed = mutableMapOf<Key, Long>()
    private var capture: Capture? = null
    var selected: HidAction? = null; private set
    private var functionAttachment: Int? = null
    private var generation = 0L
    private val recognizer = FunctionGestureRecognizer(now,
        { context()?.let { Pair(it, functionAttachment) } }, schedule, function)

    fun owns(owner: Any): Boolean = presses.values.any { it.owner === owner && it.context == context() }

    fun beginCapture(action: HidAction) {
        cancel()
        selected = action
        changed()
    }

    fun accept(event: Event): Boolean {
        val key = Key(event.attachmentId, event.binding.button)
        suppressed[key]?.let { down ->
            if (event.downTimeMs == down) {
                if (event.phase == Phase.UP) suppressed.remove(key)
                return true
            }
            if (event.phase == Phase.DOWN && event.repeat == 0) suppressed.remove(key)
        }
        var capturing = capture
        if (capturing != null && key == Key(capturing.event.attachmentId, capturing.event.binding.button) &&
            event.phase == Phase.DOWN && event.repeat == 0 && event.downTimeMs != capturing.event.downTimeMs) {
            // A new real press abandons an incomplete capture without inventing its release.
            suppressed[key] = capturing.event.downTimeMs
            capture = null
            capturing = null
        }
        if (capturing != null && key == Key(capturing.event.attachmentId, capturing.event.binding.button) &&
            event.downTimeMs == capturing.event.downTimeMs) {
            if (event.phase != Phase.DOWN) {
                capture = null
                if (event.phase == Phase.UP && event.timeMs >= event.downTimeMs) {
                    bindings.assign(capturing.action, capturing.event.binding)
                    selected = null
                } else suppressed[key] = capturing.event.downTimeMs
                changed()
            }
            return true
        }
        val selection = selected
        if (selection != null && capturing == null && event.phase == Phase.DOWN && event.repeat == 0) {
            // Capture persists only a complete press/release, never constituent output actions.
            if (event.downTimeMs <= event.timeMs) capture = Capture(selection, event)
            else suppressed[key] = event.downTimeMs
            changed()
            return true
        }
        val old = presses[key]
        val action = old?.action ?: bindings.action(event.binding.button) ?: return false
        if (context() == null) { cancel(); return true }
        if (event.phase == Phase.DOWN) {
            if (old?.event?.downTimeMs == event.downTimeMs || event.repeat != 0) return true
            old?.let { retire(key, it, event.timeMs) }
            val current = context() ?: return true
            val admitted = generation
            val point = if (action == HidAction.TAP) position() else null
            if (admitted != generation || current != context()) return true
            val press = Press(event, action, current)
            presses[key] = press // Output may synchronously cancel/change the view.
            when (action) {
                HidAction.TAP -> contact(press.owner, edge(event, PadGestureRecognizer.Phase.DOWN, point))
                HidAction.FUNCTION -> {
                    if (functionAttachment != event.attachmentId) recognizer.cancel()
                    functionAttachment = event.attachmentId
                    recognizer.accept(FunctionGestureRecognizer.Report.DOWN)
                }
                else -> Unit
            }
            return true
        }
        if (old == null || old.event.downTimeMs != event.downTimeMs) return true
        val admitted = generation
        // Refresh admission even for a stationary UP. Pointer freshness may synchronously cancel.
        val point = if (event.phase == Phase.UP && old.action != HidAction.FUNCTION) position() else null
        if (admitted != generation || presses[key] !== old) return true
        presses.remove(key) // Retire before callbacks.
        if (event.phase == Phase.CANCEL || old.context != context() || event.timeMs < event.downTimeMs) {
            cancelPress(old, event.timeMs)
            return true
        }
        when (old.action) {
            HidAction.TAP -> contact(old.owner, edge(event, PadGestureRecognizer.Phase.UP, point))
            HidAction.SCROLL_DOWN -> if (point?.available == true) scroll(160, point, event.timeMs)
            HidAction.SCROLL_UP -> if (point?.available == true) scroll(-160, point, event.timeMs)
            HidAction.FUNCTION -> if (functionAttachment == event.attachmentId)
                recognizer.accept(FunctionGestureRecognizer.Report.UP)
        }
        return true
    }

    fun consumeRetired(attachmentId: Int, keyCode: Int, scanCode: Int, phase: Phase, downTimeMs: Long): Boolean {
        val key = suppressed.keys.firstOrNull { it.attachment == attachmentId &&
            it.button.keyCode == keyCode && it.button.scanCode == scanCode && suppressed[it] == downTimeMs } ?: return false
        if (phase == Phase.UP) suppressed.remove(key)
        return true
    }

    /** A removed InputDevice may already be absent from KeyEvent.device for a queued release. */
    fun consumeDetached(attachmentId: Int, keyCode: Int, scanCode: Int, phase: Phase, downTimeMs: Long): Boolean {
        fun matches(key: Key) = key.attachment == attachmentId && key.button.keyCode == keyCode && key.button.scanCode == scanCode
        val captured = capture?.event?.let { it.attachmentId == attachmentId &&
            it.binding.button.keyCode == keyCode && it.binding.button.scanCode == scanCode && it.downTimeMs == downTimeMs } == true
        val active = presses.any { (key, press) -> matches(key) && press.event.downTimeMs == downTimeMs }
        if (captured || active) cancel()
        return consumeRetired(attachmentId, keyCode, scanCode, phase, downTimeMs)
    }

    @Suppress("UNUSED_PARAMETER") // Removal invalidates the shared HID context, including pending Function work.
    fun cancelDevice(attachmentId: Int) {
        // Attachment IDs are transient; saved identity and assignments remain untouched.
        cancel()
        // Keep ownership of a canceled capture's late release even if Android removes/reuses its ID.
        // A fresh first DOWN replaces that tombstone; it carries no pending or held action.
    }

    fun cancel() {
        generation++
        val oldCapture = capture
        val wasSelected = selected != null
        capture = null; selected = null
        if (oldCapture != null) suppressed[Key(oldCapture.event.attachmentId, oldCapture.event.binding.button)] = oldCapture.event.downTimeMs
        val old = presses.values.toList()
        presses.forEach { (key, press) -> suppressed[key] = press.event.downTimeMs }
        presses.clear()
        recognizer.cancel(); functionAttachment = null
        old.forEach { cancelPress(it, now()) }
        if (wasSelected) changed()
    }

    private fun retire(key: Key, press: Press, at: Long) {
        presses.remove(key)
        cancelPress(press, at)
    }
    private fun cancelPress(press: Press, at: Long) {
        when (press.action) {
            HidAction.TAP -> cancelContact(press.owner, at)
            HidAction.FUNCTION -> if (functionAttachment == press.event.attachmentId) recognizer.cancel()
            else -> Unit
        }
    }
    private fun edge(event: Event, phase: PadGestureRecognizer.Phase, point: PointerPosition?) = PadGestureRecognizer.Event(
        PadGestureRecognizer.Key.HELD_CONTACT, phase, event.timeMs, event.downTimeMs,
        position = point,
    )
}
