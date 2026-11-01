package com.code2hack.eyeprojector.directhost.input

import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Deadline callbacks only hand off. One ordinary worker retains every callback in submit order. */
internal class FunctionWork(private val threadName: String = "eyeprojector-phone-function") : Closeable {
    private val lock = Any()
    @Volatile private var workerThread: Thread? = null
    private val terminationObservers = mutableListOf<() -> Unit>()
    private var terminationReported = false
    @Volatile var discardedCallbacks = 0; private set
    private val worker = object : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { task ->
            Thread(task, threadName).apply { isDaemon = true }.also { workerThread = it }
        }) {
        override fun terminated() {
            val observers = synchronized(lock) {
                terminationReported = true
                terminationObservers.toList().also { terminationObservers.clear() }
            }
            observers.forEach { runCatching(it) }
        }
    }
    fun whenTerminated(observer: () -> Unit) {
        val complete = synchronized(lock) {
            if (terminationReported) true else { terminationObservers.add(observer); false }
        }
        if (complete) runCatching(observer)
    }
    private val pending = ArrayDeque<() -> Unit>()
    private var scheduled = false
    private var closed = false

    fun submit(action: () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        pending.addLast(action)
        if (!scheduled) {
            scheduled = true
            worker.execute(::run)
        }
    }

    private fun run() {
        while (true) {
            val action = synchronized(lock) {
                if (closed || pending.isEmpty()) { scheduled = false; return }
                pending.removeFirst()
            }
            try { action() }
            catch (error: Throwable) { close(); throw error }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            discardedCallbacks += pending.size
            pending.clear()
        }
        // Preserve the original close behavior; observers never wait for termination.
        if (Thread.currentThread() === workerThread) worker.shutdown() else worker.shutdownNow()
    }
}
