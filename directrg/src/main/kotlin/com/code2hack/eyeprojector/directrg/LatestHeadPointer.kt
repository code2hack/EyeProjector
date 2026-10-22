package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.HeadPointerState
import java.io.Closeable

/** One serialization plus one latest value, on the Session's existing writer. */
internal class LatestHeadPointer(private val schedule: (() -> Unit) -> Boolean,
    private val write: (HeadPointerState) -> Unit) : Closeable {
    private val lock = Any()
    private var latest: HeadPointerState? = null
    private var scheduled = false
    private var closed = false

    fun publish(value: HeadPointerState): Boolean {
        val start = synchronized(lock) {
            if (closed) return false
            latest = value
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) enqueue()
        return true
    }

    private fun enqueue() {
        if (!schedule(::serialize)) synchronized(lock) { scheduled = false; latest = null }
    }

    private fun serialize() {
        val value = synchronized(lock) { latest.also { latest = null }?.takeUnless { closed } }
        try { if (value != null) write(value) }
        finally {
            val again = synchronized(lock) {
                if (!closed && latest != null) true else { scheduled = false; false }
            }
            if (again) enqueue()
        }
    }

    override fun close() = synchronized(lock) { closed = true; latest = null }
}
