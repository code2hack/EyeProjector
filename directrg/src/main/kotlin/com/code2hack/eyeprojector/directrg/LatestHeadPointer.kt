package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.HeadPointerState
import java.io.Closeable

/** One serialization plus one latest value, on the Session's existing writer. */
internal class LatestHeadPointer(private val schedule: (() -> Unit) -> Boolean,
    private val write: (HeadPointerState) -> Unit,
    private val nowNs: () -> Long,
    private val withWriterLock: (() -> Unit) -> Unit = { it() }) : Closeable {
    private data class Pending(val state: HeadPointerState, val expiresAtNs: Long)
    private val lock = Any()
    private var latest: Pending? = null
    private var scheduled = false
    private var closed = false

    fun publish(value: HeadPointerState, expiresAtNs: Long): Boolean {
        val start = synchronized(lock) {
            if (closed) return false
            latest = Pending(value, expiresAtNs)
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) enqueue()
        return true
    }

    private fun enqueue() {
        if (!schedule(::serialize)) synchronized(lock) { scheduled = false; latest = null }
    }

    private fun serialize() {
        try { withWriterLock {
            // Select current source state and recheck its own elapsed-clock deadline after writer waits.
            val value = synchronized(lock) { latest.also { latest = null }?.takeUnless { closed } }
            if (value != null) write(if (value.state.available && nowNs() >= value.expiresAtNs)
                value.state.copy(available = false) else value.state)
        } }
        finally {
            val again = synchronized(lock) {
                if (!closed && latest != null) true else { scheduled = false; false }
            }
            if (again) enqueue()
        }
    }

    override fun close() = synchronized(lock) { closed = true; latest = null }
}
