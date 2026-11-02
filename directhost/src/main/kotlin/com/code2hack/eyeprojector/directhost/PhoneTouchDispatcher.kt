package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

/** State uses the Session control lock; effects share a separate serialized stock-output lock. */
internal class PhoneTouchDispatcher(
    private val lock: Any = Any(),
    private val outputLock: Any = Any(),
    private val write: (ByteArray) -> Unit,
) {
    interface MotionTarget {
        fun geometry(next: Geometry)
        fun touch(event: Touch): Boolean
        fun tap(event: Tap): Boolean
        fun scroll(event: Scroll): Boolean
    }
    private var closed = false
    private var geometry: Geometry? = null
    private var held: Touch? = null
    private var motionTarget: MotionTarget? = null
    val holding: Boolean get() = synchronized(lock) { held != null }

    fun bindMotionTarget(target: MotionTarget) = synchronized(outputLock) {
        val size = synchronized(lock) {
            check(!closed && motionTarget == null && held == null)
            motionTarget = target
            geometry
        }
        size?.let(target::geometry)
    }

    fun geometry(next: Geometry) = synchronized(outputLock) {
        val target = synchronized(lock) {
            if (closed) return@synchronized null
            geometry = next
            motionTarget
        }
        target?.geometry(next)
        Unit
    }

    fun touch(event: Touch): Boolean = synchronized(outputLock) {
        val cancellation = synchronized(lock) {
            if (closed) return false
            if (event.phase == Touch.Phase.CANCEL && held == null) return true
            if ((event.phase == Touch.Phase.DOWN || event.phase == Touch.Phase.MOVE) &&
                event.target.geometry != geometry) return false
            if (event.phase == Touch.Phase.DOWN) prepareCancellation() else null
        }
        cancellation?.invoke()
        val old: Touch?
        val target: MotionTarget?
        synchronized(lock) {
            if (closed) return false
            old = held
            if (event.phase == Touch.Phase.DOWN) {
                // A cancellation callback may have admitted a successor; never replace that contact.
                if (old != null || event.target.geometry != geometry) return false
                held = event
            } else if (old?.contactId != event.contactId) return false
            target = motionTarget
        }
        val accepted = target?.touch(event) ?: run { write(Scrcpy.touch(event)); true }
        synchronized(lock) {
            if (!accepted) return false
            if (event.phase == Touch.Phase.DOWN) return held === event
            if (held === old) held = if (event.phase == Touch.Phase.MOVE) event else null
        }
        true
    }

    fun tap(event: Tap): Boolean = ordinary(event.geometry) { target ->
        target?.tap(event) ?: run { write(Scrcpy.tap(event)); true }
    }
    fun scroll(event: Scroll): Boolean = ordinary(event.target.geometry) { target ->
        target?.scroll(event) ?: run { write(Scrcpy.scroll(event)); true }
    }
    fun navigate(event: Navigate): Boolean = ordinary(event.geometry) {
        write(Scrcpy.navigate(event)); true
    }

    private fun ordinary(size: Geometry, effect: (MotionTarget?) -> Boolean): Boolean = synchronized(outputLock) {
        val cancellation = synchronized(lock) {
            if (closed || size != geometry) return false
            prepareCancellation()
        }
        cancellation?.invoke()
        val target = synchronized(lock) {
            if (closed || held != null || size != geometry) return false
            motionTarget
        }
        effect(target)
    }

    /** Called under the state lock; detaches and captures only the original contact/target. */
    private fun prepareCancellation(): (() -> Boolean)? {
        val old = held ?: return null
        held = null
        val target = motionTarget
        val event = old.copy(phase = Touch.Phase.CANCEL)
        return { target?.touch(event) ?: run { write(Scrcpy.touch(event)); true } }
    }

    fun cancel(): Boolean = synchronized(outputLock) {
        val effect = synchronized(lock) { prepareCancellation() }
        effect?.invoke() ?: true
    }

    fun close() = synchronized(outputLock) {
        synchronized(lock) { closed = true; held = null }
    }
}
