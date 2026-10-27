package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

/** Share the session's control lock with resize writes, geometry changes and release. */
internal class PhoneTouchDispatcher(private val lock: Any = Any(), private val write: (ByteArray) -> Unit) {
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

    fun bindMotionTarget(target: MotionTarget) = synchronized(lock) {
        check(!closed && motionTarget == null && held == null)
        motionTarget = target
        geometry?.let(target::geometry)
    }

    fun geometry(next: Geometry) = synchronized(lock) {
        if (!closed) {
            if (geometry != next) cancel(next)
            geometry = next
            motionTarget?.geometry(next)
        }
    }

    fun touch(event: Touch): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val old = held
        if (event.phase == Touch.Phase.CANCEL && old == null) return@synchronized true
        if (event.target.geometry != geometry) return@synchronized false
        when (event.phase) {
            Touch.Phase.DOWN -> {
                if (old != null) cancel()
                if (!writeTouch(event)) return@synchronized false
                held = event
            }
            Touch.Phase.MOVE, Touch.Phase.UP, Touch.Phase.CANCEL -> {
                if (old?.contactId != event.contactId) return@synchronized false
                if (!writeTouch(event)) return@synchronized false
                held = if (event.phase == Touch.Phase.MOVE) event else null
            }
        }
        true
    }

    fun tap(event: Tap): Boolean = synchronized(lock) {
        if (closed || event.geometry != geometry) return@synchronized false
        cancel()
        motionTarget?.let { return@synchronized it.tap(event) }
        write(Scrcpy.tap(event)); true
    }
    fun scroll(event: Scroll): Boolean = synchronized(lock) {
        if (closed || event.target.geometry != geometry) return@synchronized false
        cancel()
        motionTarget?.let { return@synchronized it.scroll(event) }
        write(Scrcpy.scroll(event)); true
    }
    fun navigate(event: Navigate): Boolean = synchronized(lock) {
        if (closed || event.geometry != geometry) return@synchronized false
        cancel(); write(Scrcpy.navigate(event)); true
    }

    fun close() = synchronized(lock) {
        if (!closed) { closed = true; cancel() }
    }

    fun cancel(nextGeometry: Geometry? = null): Unit = synchronized(lock) {
        val old = held ?: return@synchronized
        held = null
        val size = nextGeometry ?: geometry ?: old.target.geometry
        // scrcpy checks current video dimensions even on CANCEL after a resize.
        val target = if (size == old.target.geometry) old.target else Tap(
            (old.target.x.toLong() * size.width / old.target.geometry.width).toInt().coerceIn(0, size.width - 1),
            (old.target.y.toLong() * size.height / old.target.geometry.height).toInt().coerceIn(0, size.height - 1), size)
        writeTouch(old.copy(phase = Touch.Phase.CANCEL, target = target))
    }

    private fun writeTouch(event: Touch): Boolean = motionTarget?.touch(event) ?: run {
        write(Scrcpy.touch(event)); true
    }
}
