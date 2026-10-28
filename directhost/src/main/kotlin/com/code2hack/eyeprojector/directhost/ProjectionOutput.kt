package com.code2hack.eyeprojector.directhost

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Attempt identity/scalars survive completion and teardown; only its outcome is published later. */
internal class ProjectionWriteAttempt(val id: Long, val channel: Int, val bytes: Int,
    val startedMonoNs: Long, val writerId: Long, val writerName: String) {
    val completion = AtomicReference<ProjectionWriteCompletion?>()
}
internal data class ProjectionWriteCompletion(val endedMonoNs: Long, val failure: Throwable?)
internal class ProjectionWrites {
    private val sequence = AtomicLong()
    val active = AtomicReference<ProjectionWriteAttempt?>()
    val failed = AtomicReference<ProjectionWriteAttempt?>()
    fun begin(channel: Int, bytes: Int): ProjectionWriteAttempt {
        val writer = Thread.currentThread()
        return ProjectionWriteAttempt(sequence.incrementAndGet(), channel, bytes, System.nanoTime(),
            writer.id, writer.name).also { active.set(it) }
    }
    fun complete(attempt: ProjectionWriteAttempt, failure: Throwable?) {
        attempt.completion.set(ProjectionWriteCompletion(System.nanoTime(), failure))
        if (failure != null) failed.compareAndSet(null, attempt)
        active.compareAndSet(attempt, null)
    }
}

/** One original Session worker. Admission never waits for the serialized projection writer. */
internal class SessionPongLane(private val send: (Long) -> Unit, private val failed: (Throwable) -> Unit) {
    private val gate = Any()
    private var retired = false
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
        Thread(it, "eyeprojector-pong")
    }
    @Volatile var failure: Throwable? = null; private set
    @Volatile var cleanupFailure: Throwable? = null; private set

    fun submit(stamp: Long) = synchronized(gate) {
        if (!retired) worker.execute {
            try { send(stamp) }
            catch (error: Throwable) {
                failure = error
                retire()
                try { failed(error) } catch (cleanup: Throwable) { cleanupFailure = cleanup }
            }
        }
    }

    fun retire() = synchronized(gate) {
        retired = true
        worker.queue.clear()
        // Session closes the socket to cancel I/O. Never interrupt or join our own cleanup caller.
        worker.shutdown()
    }

    val terminated: Boolean get() = worker.isTerminated
}
