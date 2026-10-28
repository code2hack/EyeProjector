package com.code2hack.eyeprojector.directrg

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Immutable status snapshots leave callback locks before entering this process-owned disk lane. */
internal class SessionStatusWriter(
    private val file: File,
    private val onFailure: (Throwable) -> Unit = {
        Log.w("EyeProjector", "Status persistence failed: ${it.javaClass.simpleName}")
    },
    private val executor: Executor = disk,
    private val write: (File, String) -> Unit = ::persist,
) {
    companion object {
        private val gate = Any()
        private val owners = HashMap<String, SessionStatusWriter>()
        private val disk = Executors.newSingleThreadExecutor { action ->
            Thread(action, "eyeprojector-status").apply { isDaemon = true }
        }

        private fun persist(file: File, payload: String) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(payload.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (error: Throwable) {
                try { atomic.failWrite(out) } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }

    // Normalization is lexical: construction performs no filesystem lookup on the callback lane.
    private val path = file.absoluteFile.normalize().path
    private var pending: String? = null
    private var draining = false
    private var sealed = false

    init { synchronized(gate) { owners[path] = this } }

    /** A terminal snapshot seals this original. Later heartbeats cannot replace its final state. */
    fun submit(payload: String, terminal: Boolean = false) {
        val start = synchronized(gate) {
            if (sealed || owners[path] !== this) return
            pending = payload
            if (terminal) sealed = true
            if (draining) false else { draining = true; true }
        }
        if (start) try { executor.execute(::drain) }
        catch (error: Throwable) {
            synchronized(gate) { draining = false; pending = null }
            report(error)
        }
    }

    private fun drain() {
        while (true) {
            val payload = synchronized(gate) {
                if (owners[path] !== this) pending = null
                pending.also {
                    pending = null
                    if (it == null) draining = false
                }
            } ?: return
            // Shared serialization also orders an already-running predecessor before its successor.
            // Neither disk I/O nor failure callbacks own gate or a ProjectionSession monitor.
            try { write(file, payload) } catch (error: Throwable) { report(error) }
        }
    }

    private fun report(error: Throwable) {
        try { onFailure(error) } catch (_: Throwable) { /* A diagnostic cannot kill disk ownership. */ }
    }
}
