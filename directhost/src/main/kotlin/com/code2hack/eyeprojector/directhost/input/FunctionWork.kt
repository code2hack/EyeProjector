package com.code2hack.eyeprojector.directhost.input

import java.io.Closeable
import java.util.concurrent.Executors

/** Deadline callbacks only hand off. One ordinary worker retains every callback in submit order. */
internal class FunctionWork : Closeable {
    private val lock = Any()
    @Volatile private var workerThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eyeprojector-phone-function").apply { isDaemon = true }.also { workerThread = it }
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

    override fun close() = synchronized(lock) {
        closed = true; pending.clear()
        // A Function failure can own Session release; retain that caller's original cleanup opportunity.
        if (Thread.currentThread() === workerThread) worker.shutdown() else worker.shutdownNow()
        Unit
    }
}
