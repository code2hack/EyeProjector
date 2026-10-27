package com.code2hack.eyeprojector.directhost.camera

import java.util.ArrayDeque
import java.util.concurrent.Semaphore

/** The registration and every input/physical-wrapper owner mutate under this same lock. */
internal class PhoneCameraOwnership {
    val lock = java.lang.Object()
    var revision = 0L
    /** Caller holds lock; no external work or observers run here. */
    fun changedLocked() { revision++; lock.notifyAll() }
}

/** Queue enrollment, actual original loop return and actual join are independent observations. */
public data class PhoneWorkerSnapshot(
    val queued: Int,
    val startFailure: Throwable?,
    val loopReturned: Boolean,
    val joined: Boolean,
    val primaryFailure: Throwable?,
    val startRequested: Boolean,
    val startReturned: Boolean,
    val threadEntered: Boolean,
    val closing: Boolean,
    val running: Boolean,
    val joinObserverStarted: Boolean,
    val joinFailure: Throwable?,
    val observerFailure: Throwable?,
)

/** Real original-thread lane; no native action runs through an inline start port. */
internal class PhoneCameraWorker(
    private val name: String,
    private val ownership: PhoneCameraOwnership,
    private val onFailure: (Throwable) -> Unit = {},
    private val startThread: (Thread) -> Unit = { it.start() },
) {
    private val queue = ArrayDeque<() -> Unit>()
    private val wake = Semaphore(0)
    private var startRequested = false
    private var startReturned = false
    private var threadEntered = false
    private var closing = false
    private var running = false
    private var loopReturned = false
    private var joined = false
    private var joinObserverStarted = false
    private var startFailure: Throwable? = null
    private var primaryFailure: Throwable? = null
    private var joinFailure: Throwable? = null
    private var observerFailure: Throwable? = null
    private val original = Thread({ runOriginal() }, name)

    /** True means enrolled/owned, not started/executed. A start exception can follow real start. */
    fun post(action: () -> Unit): Boolean {
        val start = synchronized(ownership.lock) {
            if (closing || loopReturned || joined) return false
            queue.addLast(action)
            val first = !startRequested
            if (first) startRequested = true
            ownership.changedLocked()
            first
        }
        wake.release()
        if (start) startOriginal()
        return true
    }

    /** Close admission and drain already-owned work. Never waits or interrupts the original. */
    fun closeWhenIdle() {
        val start = synchronized(ownership.lock) {
            val first = !startRequested
            if (first) startRequested = true
            if (!closing) { closing = true; ownership.changedLocked() }
            first
        }
        wake.release()
        if (start) startOriginal()
    }

    fun snapshot(): PhoneWorkerSnapshot = synchronized(ownership.lock) {
        PhoneWorkerSnapshot(queue.size, startFailure, loopReturned, joined, primaryFailure,
            startRequested, startReturned, threadEntered, closing, running,
            joinObserverStarted, joinFailure, observerFailure)
    }

    private fun failure(error: Throwable, start: Boolean = false, join: Boolean = false) {
        synchronized(ownership.lock) {
            if (primaryFailure == null) primaryFailure = error
            if (start && startFailure == null) startFailure = error
            if (join && joinFailure == null) joinFailure = error
            ownership.changedLocked()
        }
        // This diagnostic port must not perform resource work; it is never under ownership.lock.
        try { onFailure(error) } catch (observer: Throwable) {
            synchronized(ownership.lock) {
                if (observerFailure == null) observerFailure = observer
                ownership.changedLocked()
            }
        }
    }

    private fun startOriginal() {
        var error: Throwable? = null
        try { startThread(original) } catch (caught: Throwable) { error = caught }
        synchronized(ownership.lock) { startReturned = error == null; ownership.changedLocked() }
        val startError = error
        if (startError != null) failure(startError, start = true)
        else if (original.state == Thread.State.NEW)
            failure(IllegalStateException("Original camera worker start remains unobserved"), start = true)
    }

    private fun runOriginal() {
        if (Thread.currentThread() !== original) {
            failure(IllegalStateException("Camera worker start ran inline on another thread"), start = true)
            return // No original loop-return or join fact exists.
        }
        synchronized(ownership.lock) { threadEntered = true; ownership.changedLocked() }
        observeJoin()
        try {
            while (true) {
                val action = synchronized(ownership.lock) {
                    if (queue.isEmpty()) {
                        if (closing) return
                        null
                    } else queue.removeFirst().also { running = true; ownership.changedLocked() }
                }
                if (action == null) { wake.acquire(); continue }
                try { action() } catch (error: Throwable) { failure(error) }
                finally {
                    synchronized(ownership.lock) { running = false; ownership.changedLocked() }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure(error)
        } finally {
            synchronized(ownership.lock) { loopReturned = true; ownership.changedLocked() }
        }
    }

    private fun observeJoin() {
        try {
            Thread({
                synchronized(ownership.lock) { joinObserverStarted = true; ownership.changedLocked() }
                try {
                    original.join()
                    val stopped = !original.isAlive
                    synchronized(ownership.lock) { joined = stopped; ownership.changedLocked() }
                    if (!stopped) failure(IllegalStateException("Original camera worker still alive after join"), join = true)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failure(error, join = true)
                }
            }, "$name-join").apply { isDaemon = true; start() }
        } catch (error: Throwable) { failure(error, join = true) }
    }
}
