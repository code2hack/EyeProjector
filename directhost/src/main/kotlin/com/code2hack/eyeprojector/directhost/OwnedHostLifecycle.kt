package com.code2hack.eyeprojector.directhost

import java.io.Closeable

/** Readiness belongs to the bound listener; thread creation is only STARTING. */
internal class OwnedHostLifecycle {
    enum class State { STOPPED, STARTING, READY, SESSION_STARTING, STREAMING, STOPPING, FAILED, STOP_UNVERIFIED }
    data class Snapshot(val state: State, val failure: String? = null)
    interface Backend : Closeable { fun run() }

    private val lock = Any()
    private var worker: Thread? = null
    private var backend: Backend? = null
    private var wanted = false
    private var snapshot = Snapshot(State.STOPPED)
    private var backendFailure: String? = null

    fun status(): Snapshot = synchronized(lock) { snapshot }

    fun start(create: (ready: () -> Unit) -> Backend): Snapshot = synchronized(lock) {
        // A start during teardown waits for a fresh user Start after teardown completes.
        if (worker != null || backend != null) return@synchronized snapshot
        wanted = true
        backendFailure = null
        snapshot = Snapshot(State.STARTING)
        worker = Thread({
            var owned: Backend? = null
            var failure: String? = null
            var closed = true
            try {
                owned = create {
                    synchronized(lock) { if (wanted) snapshot = Snapshot(State.READY) }
                }
                val run = synchronized(lock) { backend = owned; wanted }
                if (run) owned.run()
            } catch (_: Exception) {
                failure = "BACKEND_START_OR_RUN_FAILED"
            } finally {
                try { owned?.close() } catch (_: Exception) { closed = false }
                synchronized(lock) {
                    snapshot = when {
                        !closed -> Snapshot(State.STOP_UNVERIFIED, "BACKEND_CLOSE_FAILED")
                        (failure != null || backendFailure != null) && wanted -> Snapshot(State.FAILED, failure ?: backendFailure)
                        else -> Snapshot(State.STOPPED)
                    }
                    wanted = false; backend = if (closed) null else owned; worker = null
                }
            }
        }, "eyeprojector-owned-host").also { it.start() }
        snapshot
    }

    fun report(state: State, failure: String? = null) = synchronized(lock) {
        if (wanted && worker != null) {
            if (failure != null) backendFailure = failure
            snapshot = Snapshot(state, failure)
        }
    }

    fun stop(waitMillis: Long = 5000): Snapshot {
        val (thread, owned) = synchronized(lock) {
            wanted = false
            if (worker == null && backend == null) {
                snapshot = Snapshot(State.STOPPED)
                return snapshot
            }
            snapshot = Snapshot(State.STOPPING)
            worker to backend
        }
        var closed = true
        try { owned?.close() } catch (_: Exception) {
            closed = false
            synchronized(lock) { snapshot = Snapshot(State.STOP_UNVERIFIED, "BACKEND_CLOSE_FAILED") }
        }
        thread?.join(waitMillis)
        return synchronized(lock) {
            if (worker != null) snapshot = Snapshot(State.STOP_UNVERIFIED, "BACKEND_STOP_PENDING")
            else if (closed) { backend = null; snapshot = Snapshot(State.STOPPED) }
            snapshot
        }
    }
}
