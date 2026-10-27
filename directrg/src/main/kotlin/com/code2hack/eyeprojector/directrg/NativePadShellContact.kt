package com.code2hack.eyeprojector.directrg

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException

/** Receives the shell reader's real long-key edges, without changing ordinary pad recognition. */
internal class NativePadShellContact(
    private val heldOwner: HeldInputOwner,
    private val context: () -> Any?,
    private val position: () -> PointerPosition,
    private val prepareDown: () -> Unit,
    private val changed: () -> Unit,
) {
    private class Listener(val server: LocalServerSocket) {
        @Volatile var socket: LocalSocket? = null
    }
    private class Source {
        var rawPress: Long? = null
        var press: Press? = null
    }
    private class Press(val down: PadGestureRecognizer.Event, val context: Any) { val owner = Any() }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: Listener? = null
    @Volatile private var contextRevision = 0L
    private var source: Source? = null // Main-thread ownership, separate from the reader's physical state.
    val holding: Boolean get() = source?.rawPress != null

    fun start() {
        if (listener != null) return
        val run = try { Listener(LocalServerSocket(NativePadReader.SOCKET_NAME)) }
        catch (error: IOException) {
            Log.w("EyeProjectorPad", "Native pad listener could not start", error)
            return
        }
        listener = run
        Thread({ listen(run) }, "native-pad-socket").apply { isDaemon = true; start() }
    }

    private fun listen(run: Listener) {
        try {
            while (listener === run) {
                val socket = run.server.accept()
                run.socket = socket
                if (listener !== run) { socket.close(); break }
                val original = Source()
                main.post {
                    if (listener === run) {
                        source?.let(::cancel)
                        source = original
                        Log.i("EyeProjectorPad", "Native pad shell reader connected")
                    }
                }
                try {
                    socket.inputStream.bufferedReader(Charsets.US_ASCII).use { input ->
                        while (listener === run) {
                            val line = input.readLine() ?: break
                            val fields = line.split(' ')
                            val id = fields.getOrNull(1)?.toLongOrNull() ?: continue
                            val phase = when (fields.firstOrNull()) {
                                "DOWN" -> PadGestureRecognizer.Phase.DOWN
                                "UP" -> PadGestureRecognizer.Phase.UP
                                else -> continue
                            }
                            val revision = contextRevision
                            main.post {
                                if (listener === run && source === original) accept(original, id, phase, revision)
                            }
                        }
                    }
                } catch (error: IOException) {
                    if (listener === run) Log.i("EyeProjectorPad", "Native pad reader socket closed: ${error.message}")
                } finally {
                    try { socket.close() } catch (_: IOException) { }
                    if (run.socket === socket) run.socket = null
                    main.post {
                        if (source === original) {
                            source = null
                            cancel(original)
                            changed()
                            Log.i("EyeProjectorPad", "Native pad shell reader disconnected")
                        }
                    }
                }
            }
        } catch (error: IOException) {
            if (listener === run) Log.w("EyeProjectorPad", "Native pad listener closed", error)
        }
    }

    private fun accept(original: Source, id: Long, phase: PadGestureRecognizer.Phase, revision: Long) {
        if (phase == PadGestureRecognizer.Phase.DOWN) {
            if (original.rawPress == id) return
            cancel(original)
            original.rawPress = id
            if (revision != contextRevision) return
            val admitted = context() ?: return
            prepareDown()
            val point = position()
            if (source !== original || original.rawPress != id || context() != admitted) return
            val at = SystemClock.uptimeMillis()
            val press = Press(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT,
                PadGestureRecognizer.Phase.DOWN, at, at, position = point), admitted)
            original.press = press
            val accepted = heldOwner.accept(press.owner, press.down)
            if (!accepted || source !== original || context() != admitted) {
                if (original.press === press) original.press = null
                heldOwner.cancel(press.owner, SystemClock.uptimeMillis())
            }
            Log.i("EyeProjectorPad", "Native raw hold DOWN id=$id accepted=$accepted")
        } else {
            if (original.rawPress != id) return
            original.rawPress = null
            val press = original.press
            original.press = null
            if (press != null) {
                val at = SystemClock.uptimeMillis()
                if (context() == press.context) heldOwner.accept(press.owner,
                    press.down.copy(phase = PadGestureRecognizer.Phase.UP, timeMs = at))
                else heldOwner.cancel(press.owner, at)
            }
            Log.i("EyeProjectorPad", "Native raw hold UP id=$id")
        }
        changed()
    }

    /** Retire only output ownership; a still-held physical key cannot reacquire after context loss. */
    fun cancel() { contextRevision++; source?.let(::cancel) }

    private fun cancel(original: Source) {
        val press = original.press ?: return
        original.press = null
        heldOwner.cancel(press.owner, SystemClock.uptimeMillis())
    }

    fun stop() {
        val run = listener
        listener = null
        val original = source
        source = null
        if (original != null) cancel(original)
        try { run?.socket?.close() } catch (_: IOException) { }
        try { run?.server?.close() } catch (_: IOException) { }
    }
}
