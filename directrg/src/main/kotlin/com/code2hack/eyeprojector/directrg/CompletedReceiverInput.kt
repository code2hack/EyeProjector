package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.ReceiverInput
import com.code2hack.eyeprojector.protocol.ReceiverInputKind
import com.code2hack.eyeprojector.protocol.Touch

/** Completed coordinates, with Android time/contact anchored locally; never HID classification. */
internal class CompletedReceiverInput(
    private val now: () -> Long,
    private val revision: () -> Long,
    private val local: () -> Boolean,
    private val beforeDown: () -> Unit,
    private val touch: (HeldPointerDispatcher.Event) -> Boolean,
    private val scroll: (PointerPosition, Int) -> Boolean,
    private val release: () -> Unit,
    private val presentation: (ReceiverInputKind) -> Unit,
) {
    private class Contact(val remote: Long, val local: Long, val revision: Long)
    private var held: Contact? = null
    private var generation = 0L

    fun accept(event: ReceiverInput) {
        if (event.revision != revision()) return
        when (event.kind) {
            ReceiverInputKind.PANEL, ReceiverInputKind.BACK, ReceiverInputKind.EXIT,
            ReceiverInputKind.FUNCTION_DOUBLE, ReceiverInputKind.SLEEP -> {
                cancel(); if (event.revision == revision()) presentation(event.kind)
            }
            ReceiverInputKind.SCROLL -> {
                cancel(); beforeDown()
                if (local() && event.revision == revision()) scroll(PointerPosition(event.x, event.y, true), event.pixels)
            }
            else -> {
                if (!local()) { cancel(); return }
                if (event.kind == ReceiverInputKind.DOWN) {
                    cancel()
                    val admitted = generation
                    beforeDown()
                    if (generation != admitted || event.revision != revision() || !local()) return
                    val next = Contact(event.contactId, now(), event.revision)
                    held = next
                    if (!touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, next.local, next.local,
                            PointerPosition(event.x, event.y, true))) && held === next) held = null
                    return
                }
                val old = held ?: return
                if (old.remote != event.contactId || old.revision != event.revision) return
                val phase = when (event.kind) {
                    ReceiverInputKind.MOVE -> Touch.Phase.MOVE
                    ReceiverInputKind.UP -> Touch.Phase.UP
                    else -> Touch.Phase.CANCEL
                }
                if (phase != Touch.Phase.MOVE) held = null
                // Phone duration is not subtracted from Receiver uptime. Actual dispatch uses this clock.
                touch(HeldPointerDispatcher.Event(phase, old.local, now().coerceAtLeast(old.local),
                    PointerPosition(event.x, event.y, true)))
            }
        }
    }

    fun cancel() {
        generation++
        if (held != null) { held = null; release() }
    }
}
