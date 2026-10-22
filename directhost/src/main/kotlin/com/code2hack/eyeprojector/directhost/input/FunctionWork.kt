package com.code2hack.eyeprojector.directhost.input

import java.io.Closeable
import java.util.concurrent.Executors

/** Deadline callbacks only hand off. One ordinary worker plus one latest current Function callback. */
internal class FunctionWork : Closeable {
    private val lock = Any()
    @Volatile private var workerThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eyeprojector-phone-function").apply { isDaemon = true }.also { workerThread = it }
    }
    private var latest: (() -> Unit)? = null
    private var scheduled = false
    private var closed = false

    fun submit(action: () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        latest = action
        if (!scheduled) {
            scheduled = true
            worker.execute(::run)
        }
    }

    private fun run() {
        while (true) {
            val action = synchronized(lock) {
                if (closed || latest == null) { scheduled = false; return }
                latest.also { latest = null }
            }
            try { action?.invoke() }
            catch (error: Throwable) { close(); throw error }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true; latest = null
        // A Function failure can own Session release; retain that caller's original cleanup opportunity.
        if (Thread.currentThread() === workerThread) worker.shutdown() else worker.shutdownNow()
        Unit
    }
}
