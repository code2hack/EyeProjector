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
        if (!closed) { closed = true; cancel() }
    }

    /** Detach the exact original before a persistent Anchor cancellation runs off the ingress lane. */
    fun prepareCancel(nextGeometry: Geometry? = null): () -> Boolean = synchronized(lock) {
        val old = held ?: return@synchronized { true }
        held = null
        val size = nextGeometry ?: geometry ?: old.target.geometry
        // scrcpy checks current video dimensions even on CANCEL after a resize.
        val target = if (size == old.target.geometry) old.target else Tap(
            (old.target.x.toLong() * size.width / old.target.geometry.width).toInt().coerceIn(0, size.width - 1),
            (old.target.y.toLong() * size.height / old.target.geometry.height).toInt().coerceIn(0, size.height - 1), size)
        val event = old.copy(phase = Touch.Phase.CANCEL, target = target)
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

    /** Persistent Anchor release detaches only its original backend before an external UP. */
    fun prepareRelease(expected: Touch): () -> Boolean = synchronized(lock) {
        val old = held?.takeIf { it.contactId == expected.contactId && it.target == expected.target }
            ?: return@synchronized { false }
        held = null
        val originalTarget = motionTarget
        var completed = false
        var result = false
        var failure: Throwable? = null
        val finish: () -> Boolean = {
            if (!completed) {
                completed = true
                fun output(phase: Touch.Phase): Boolean {
                    val event = old.copy(phase = phase)
                    return originalTarget?.touch(event) ?: run { write(Scrcpy.touch(event)); true }
                }
                try { result = output(Touch.Phase.UP) }
                catch (error: Throwable) { failure = error }
                if (!result) try {
                    if (!output(Touch.Phase.CANCEL)) throw java.io.IOException("Original Anchor release cleanup was rejected")
                } catch (cleanup: Throwable) {
                    failure?.let { if (it !== cleanup) it.addSuppressed(cleanup) } ?: run { failure = cleanup }
                }
            }
            failure?.let { throw it }
            result
        }
        finish
    }

    private fun writeTouch(event: Touch): Boolean = motionTarget?.touch(event) ?: run {
        write(Scrcpy.touch(event)); true
    }
}
