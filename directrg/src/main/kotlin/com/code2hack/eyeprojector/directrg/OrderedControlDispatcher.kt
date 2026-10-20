package com.code2hack.eyeprojector.directrg

import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One ordered, paced control stream. Acceptance means queued; written means the write returned. */
internal class OrderedControlDispatcher(private val onFailure: (Exception) -> Unit) : Closeable {
    data class Snapshot(val accepted: Long, val written: Long, val canceled: Long, val failed: Long, val rejected: Long,
        val pending: Long, val inFlight: Long, val meanWriteDelayMs: Double, val maxWriteDelayMs: Double)
    private data class Job(val future: java.util.concurrent.ScheduledFuture<*>, val category: String, val timeMs: Long, val dueNs: Long, val preserveTiming: Boolean)
    private val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val jobs = mutableMapOf<Long, Job>()
    private var sequence = 0L
    private var generation = 0L
    private var closed = false
    private var lastEventMs: Long? = null
    private var lastDueNs = 0L
    private var accepted = 0L
    private var written = 0L
    private var canceled = 0L
    private var failed = 0L
    private var rejected = 0L
    private var inFlight = 0L
    private var lastWrittenEventMs: Long? = null
    private var lastWriteEndNs = 0L
    private var totalWriteDelayNs = 0L
    private var maxWriteDelayNs = 0L

    @Synchronized fun snapshot() = Snapshot(accepted, written, canceled, failed, rejected, jobs.size.toLong(), inFlight,
        if (written == 0L) 0.0 else totalWriteDelayNs / written / 1e6, maxWriteDelayNs / 1e6)

    @Synchronized fun submit(eventTimeMs: Long, isCurrent: () -> Boolean, category: String = "input", preserveTiming: Boolean = true, write: () -> Unit): Boolean {
        if (closed) { rejected++; return false }
        val now = System.nanoTime()
        val delta = if (!preserveTiming) 0L else lastEventMs?.let { (eventTimeMs - it).coerceAtLeast(0) * 1_000_000 } ?: 0L
        val due = if (preserveTiming) maxOf(now, lastDueNs + delta) else now
        val id = ++sequence
        val epoch = generation
        if (preserveTiming) { lastEventMs = eventTimeMs; lastDueNs = due }
        val job = executor.schedule({
            var started = false
            try {
                // Backpressure must not compress a delayed multi-tap into simultaneous clicks.
                val waitNs = synchronized(this) {
                    if (!preserveTiming) 0L else lastWrittenEventMs?.let { lastWriteEndNs +
                        (eventTimeMs - it).coerceAtLeast(0) * 1_000_000 - System.nanoTime() } ?: 0L
                }
                if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs)
                synchronized(this) {
                    if (epoch != generation || closed || !jobs.containsKey(id) || !isCurrent()) {
                        if (jobs.remove(id) != null) canceled++
                        return@schedule
                    }
                    // Started writes complete or fail; close cancels queued work.
                    jobs.remove(id); inFlight++; started = true
                }
                write()
                synchronized(this) {
                    written++; inFlight--
                    val delay = System.nanoTime() - now
                    totalWriteDelayNs += delay; maxWriteDelayNs = maxOf(maxWriteDelayNs, delay)
                    if (epoch == generation && preserveTiming) { lastWrittenEventMs = eventTimeMs; lastWriteEndNs = System.nanoTime() }
                }
            } catch (error: Exception) {
                val report = synchronized(this) {
                    if (started) { inFlight--; failed++; true }
                    else if (epoch != generation || closed || !jobs.containsKey(id)) false
                    else { jobs.remove(id); failed++; true }
                }
                if (report) onFailure(error)
            }
        }, due - now, TimeUnit.NANOSECONDS)
        jobs[id] = Job(job, category, eventTimeMs, due, preserveTiming); accepted++
        return true
    }

    @Synchronized fun cancelPending(category: String? = null) {
        if (category == null) generation++
        val removed = jobs.filterValues { category == null || it.category == category }
        removed.forEach { (id, job) -> jobs.remove(id); job.future.cancel(true) }
        canceled += removed.size
        val tail = jobs.filterValues { it.preserveTiming }.maxByOrNull { it.key }?.value
        lastEventMs = tail?.timeMs; lastDueNs = tail?.dueNs ?: 0
        if (category == null) { lastWrittenEventMs = null; lastWriteEndNs = 0 }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        executor.shutdownNow()
    }
}
