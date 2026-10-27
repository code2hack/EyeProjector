package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Touch

/** A bounded Android long press, not a reconstruction of physical contact/release or dragging. */
internal class ClassifiedPadLongPress(
    private val heldOwner: HeldInputOwner,
    private val now: () -> Long,
    private val holdMs: Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    private class Press(val down: PadGestureRecognizer.Event, val current: () -> Boolean) {
        val owner = Any()
        var unschedule: (() -> Unit)? = null
    }
    private var press: Press? = null

    /** Capture a fixed target only for this helper's DOWN; other contacts retain pointer motion. */
    fun route(send: (HeldPointerDispatcher.Event) -> Boolean): (HeldPointerDispatcher.Event) -> Boolean {
        var fixed: PointerPosition? = null
        return { event ->
            if (event.phase == Touch.Phase.DOWN) {
                val original = press?.down?.position
                fixed = original?.takeIf { it === event.position }
            }
            val point = fixed
            send(if (point == null) event else event.copy(position = point))
        }
    }

    fun start(action: PadGestureRecognizer.Action.LongPress, current: () -> Boolean): Boolean {
        val point = action.position?.takeIf { it.available } ?: return false
        if (action.markerDownTimeMs == null) return false
        cancel()
        if (!current()) return false
        val at = now()
        // A separate position object identifies exactly this DOWN when its route is captured.
        val next = Press(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT,
            PadGestureRecognizer.Phase.DOWN, at, at, position = point.copy()), current)
        press = next
        val accepted = heldOwner.accept(next.owner, next.down)
        if (!accepted || press !== next || !current()) {
            if (press === next) press = null
            heldOwner.cancel(next.owner, now())
            return false
        }
        // Schedule from actual admission, never backdate a DOWN to the firmware marker.
        next.unschedule = schedule(now() + holdMs) {
            if (press === next) {
                press = null
                if (next.current()) heldOwner.accept(next.owner,
                    next.down.copy(phase = PadGestureRecognizer.Phase.UP, timeMs = now()))
                else heldOwner.cancel(next.owner, now())
            }
        }
        return true
    }

    fun cancel() {
        val old = press ?: return
        press = null
        old.unschedule?.invoke()
        // Token-specific cancellation cannot cancel a newer native or HID contact.
        heldOwner.cancel(old.owner, now())
    }
}
