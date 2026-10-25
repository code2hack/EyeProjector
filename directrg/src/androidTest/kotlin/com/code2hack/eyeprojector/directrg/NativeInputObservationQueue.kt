package com.code2hack.eyeprojector.directrg

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Test-only primitive snapshots. No I/O, event objects, capacity limit or input policy. */
internal class NativeInputObservationQueue {
    data class Row(val type: String, val seq: Long, val activityId: Int?, val uptimeMs: Long,
                   val elapsedNs: Long, val fields: Map<String, Any?>)
    private val rows = ConcurrentLinkedQueue<Row>()
    private val sequence = AtomicLong()
    private val failures = AtomicLong()
    private val callbackNs = AtomicLong()
    private val maximumNs = AtomicLong()
    @Volatile private var accepting = true
    val recorded get() = sequence.get()
    val failureCount get() = failures.get()
    val totalCallbackNs get() = callbackNs.get()
    val maxCallbackNs get() = maximumNs.get()

    fun capture(type: String, activityId: Int?, uptimeMs: Long, elapsedNs: Long,
                fields: () -> Map<String, Any?>) {
        if (!accepting) return
        val measured = type == "key" || type == "touch_motion" || type == "generic_motion" || type == "stage"
        val begin = if (measured) System.nanoTime() else 0L
        try {
            val snapshot = fields().toMap()
            rows.add(Row(type, sequence.incrementAndGet(), activityId, uptimeMs, elapsedNs, snapshot))
        } catch (_: Throwable) {
            failures.incrementAndGet()
        } finally {
            if (measured) {
                val duration = System.nanoTime() - begin
                callbackNs.addAndGet(duration)
                maximumNs.accumulateAndGet(duration, ::maxOf)
            }
        }
    }

    /** Caller first detaches the exact observer on the main thread, then closes and drains. */
    fun close() { accepting = false }
    fun drain(write: (Row) -> Unit): Long {
        var count = 0L
        while (true) {
            val row = rows.poll() ?: return count
            write(row); count++
        }
    }
}
