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
            geometry = next
            motionTarget?.geometry(next)
        }
    }

    fun touch(event: Touch): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val old = held
        if (event.phase == Touch.Phase.CANCEL && old == null) return@synchronized true
        if ((event.phase == Touch.Phase.DOWN || event.phase == Touch.Phase.MOVE) &&
            event.target.geometry != geometry) return@synchronized false
        when (event.phase) {
            Touch.Phase.DOWN -> {
                if (old != null) cancel()
                if (closed || held != null || geometry != event.target.geometry) return@synchronized false
                // Publish original ownership before external output, so reentrant retirement sees it.
                held = event
                if (!writeTouch(event)) return@synchronized false
                if (held !== event) return@synchronized false
            }
            Touch.Phase.MOVE, Touch.Phase.UP, Touch.Phase.CANCEL -> {
                if (old?.contactId != event.contactId) return@synchronized false
                if (!writeTouch(event)) return@synchronized false
                // A callback may retire this contact and admit a successor; never overwrite it.
                if (held === old) held = if (event.phase == Touch.Phase.MOVE) event else null
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
        if (!closed) { closed = true; held = null }
    }

    /** Detach the exact ordinary contact before its cancellation output. */
    fun prepareCancel(nextGeometry: Geometry? = null): () -> Boolean = synchronized(lock) {
        val old = held ?: return@synchronized { true }
        held = null
        val event = old.copy(phase = Touch.Phase.CANCEL)
        val originalTarget = motionTarget
        var finished = false
        var result = false
        var failure: Throwable? = null
        val finish: () -> Boolean = {
            if (!finished) {
                finished = true
                try { result = originalTarget?.touch(event) ?: run { write(Scrcpy.touch(event)); true } }
                catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
            result
        }
        finish
    }

    fun cancel(nextGeometry: Geometry? = null): Boolean = prepareCancel(nextGeometry).invoke()

    private fun writeTouch(event: Touch): Boolean = motionTarget?.touch(event) ?: run {
        write(Scrcpy.touch(event)); true
    }
}
