package com.code2hack.eyeprojector.directrg

import android.view.InputDevice
import android.view.MotionEvent
import com.code2hack.eyeprojector.protocol.Touch

/** Own copies of raw Motion edges while the original Phone decision is pending. */
internal class NativePadMotionInput(
    private val deliver: (MotionEvent) -> Unit,
    admit: ((Boolean) -> Unit) -> Unit,
    private val current: () -> Any?,
) {
    private data class Received(val event: MotionEvent, val context: Any?)
    private fun key(event: MotionEvent) = event.deviceId to event.downTime
    private fun phase(event: MotionEvent) = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> Touch.Phase.DOWN
        MotionEvent.ACTION_MOVE -> Touch.Phase.MOVE
        MotionEvent.ACTION_UP -> Touch.Phase.UP
        else -> Touch.Phase.CANCEL
    }
    private val contacts = ReceiverContactInput<Received>({ key(it.event) }, { phase(it.event) }, admit,
        { original -> if (current() == original.context) deliver(original.event) },
        { original -> original.event.recycle() })
    val admission: Long get() = contacts.admission
    fun accept(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false
        if (event.actionMasked !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) return false
        val id = key(event)
        val original = contacts.original(id)
        if (phase(event) == Touch.Phase.DOWN && original != null) return true
        // Orphan releases stay consumed; they cannot manufacture a fresh DOWN/decision.
        if (phase(event) != Touch.Phase.DOWN && original == null) return true
        val captured = if (original == null) current() else original.context
        contacts.accept(Received(MotionEvent.obtain(event), captured))
        return true
    }
    fun retire() { contacts.retire() }
}
