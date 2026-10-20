package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Touch

/** Route captured at DOWN; pointer motion changes position without inventing contact edges. */
internal class HeldPointerDispatcher(
    private val currentPosition: () -> PointerPosition,
    private val route: () -> ((Event) -> Boolean)?,
) {
    data class Event(val phase: Touch.Phase, val contactId: Long, val timeMs: Long, val position: PointerPosition)
    private data class Held(val event: Event, val send: (Event) -> Boolean)
    private var held: Held? = null
    val holding get() = held != null
    var accepted = 0L; private set
    var rejected = 0L; private set
    var canceled = 0L; private set

    fun accept(contact: PadGestureRecognizer.Event): Boolean {
        val phase = Touch.Phase.valueOf(contact.phase.name)
        if (phase == Touch.Phase.DOWN) {
            if (held?.event?.contactId == contact.downTimeMs) return true
            cancel(contact.timeMs)
            val point = contact.position ?: currentPosition()
            val send = route()
            if (!point.available || send == null) { rejected++; return false }
            val event = Event(phase, contact.downTimeMs, contact.timeMs, point)
            val next = Held(event, send)
            held = next // Callbacks may synchronously switch context and cancel.
            if (!send(event)) { rejected++; if (held === next) cancel(contact.timeMs); return false }
            accepted++; return true
        }
        val old = held ?: return false
        if (old.event.contactId != contact.downTimeMs) return false
        if (phase == Touch.Phase.CANCEL) { cancel(contact.timeMs); return true }
        // A pad's coordinates never replace the head pointer's screen coordinates.
        return update(phase, contact.timeMs)
    }

    fun move(timeMs: Long): Boolean = if (holding) update(Touch.Phase.MOVE, timeMs) else false

    private fun update(phase: Touch.Phase, timeMs: Long): Boolean {
        val old = held ?: return false
        val point = currentPosition()
        if (!point.available) { cancel(timeMs); return false }
        if (phase == Touch.Phase.MOVE && point == old.event.position) return true
        val event = old.event.copy(phase = phase, timeMs = maxOf(timeMs, old.event.timeMs), position = point)
        val next = Held(event, old.send)
        held = next
        if (!old.send(event)) { rejected++; if (held === next) cancel(timeMs); return false }
        accepted++
        if (phase == Touch.Phase.UP && held === next) held = null
        return true
    }

    fun cancel(timeMs: Long) {
        val old = held ?: return
        held = null; canceled++
        val event = old.event.copy(phase = Touch.Phase.CANCEL, timeMs = maxOf(timeMs, old.event.timeMs))
        if (old.send(event)) accepted++ else rejected++
    }
}
