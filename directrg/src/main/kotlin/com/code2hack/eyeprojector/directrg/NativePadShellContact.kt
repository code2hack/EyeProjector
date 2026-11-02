package com.code2hack.eyeprojector.directrg

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import com.code2hack.eyeprojector.protocol.Touch

/** Receives the shell reader's real long-key edges, without changing ordinary pad recognition. */
internal class NativePadShellContact(
    private var heldOwner: HeldInputOwner,
    private var context: () -> Any?,
    private var position: () -> PointerPosition,
    private var prepareDown: () -> Unit,
    private var changed: () -> Unit,
    private var admit: ((Boolean) -> Unit) -> Unit = { it(true) },
    private var functionSourceAvailable: (Any) -> Unit = {},
    private var functionSourceLost: (Any) -> Unit = {},
    private var functionEdge: (Any, Long, Boolean, Long, Boolean) -> Unit = { _, _, _, _, _ -> },
) {
    private class Listener(val server: LocalServerSocket) {
        @Volatile var socket: LocalSocket? = null
    }
    private class Source(val socket: LocalSocket) {
        var functionConnected = false
        var rawPress: Long? = null
        var press: Press? = null
    }
    private class Press(val id: Long, val down: PadGestureRecognizer.Event, val context: Any,
        val targetOwner: HeldInputOwner) { val owner = Any() }
    private data class Received(val source: Source, val id: Long, val phase: PadGestureRecognizer.Phase,
        val revision: Long, val at: Long, val point: PointerPosition?, val context: Any?)
    private val input = ReceiverContactInput<Received>({ it.source to it.id },
        { Touch.Phase.valueOf(it.phase.name) }, { admit(it) }, ::acceptAdmitted)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: Listener? = null
    @Volatile private var contextRevision = 0L
    @Volatile private var powerRequest: Any? = null
    private var source: Source? = null // Main-thread ownership, separate from the reader's physical state.
    private var admission = 0L // A completed raw Press remains observable without retaining its Source/socket.
    private var viewGeneration = 0L
    val holding: Boolean get() = source?.rawPress != null

    /** Rebind ordinary view callbacks without closing the original native Function connection. */
    fun attachView(next: NativePadShellContact) {
        if (next === this) return
        val binding = ++viewGeneration
        prepareCancel().finishOriginal()
        if (viewGeneration != binding) return
        heldOwner = next.heldOwner; context = next.context; position = next.position
        prepareDown = next.prepareDown; changed = next.changed; admit = next.admit
        functionSourceAvailable = next.functionSourceAvailable
        functionSourceLost = next.functionSourceLost; functionEdge = next.functionEdge
    }

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
                val original = Source(socket)
                main.post {
                    if (listener === run) {
                        val previous = source
                        source = original
                        input.retire()
                        previous?.let {
                            if (it.functionConnected) { it.functionConnected = false; functionSourceLost(it) }
                            cancel(it)
                        }
                        Log.i("EyeProjectorPad", "Native pad shell reader connected")
                    }
                }
                try {
                    socket.inputStream.bufferedReader(Charsets.US_ASCII).use { input ->
                        while (listener === run) {
                            val line = input.readLine() ?: break
                            val fields = line.split(' ')
                            val report = fields.firstOrNull()
                            if (report == "FUNCTION_SOURCE" || report == "FUNCTION_LOST") {
                                main.post {
                                    if (listener === run && source === original) {
                                        if (report == "FUNCTION_SOURCE" && !original.functionConnected) {
                                            original.functionConnected = true
                                            functionSourceAvailable(original)
                                        } else if (report == "FUNCTION_LOST" && original.functionConnected) {
                                            original.functionConnected = false
                                            functionSourceLost(original)
                                        }
                                    }
                                }
                                continue
                            }
                            val id = fields.getOrNull(1)?.toLongOrNull() ?: continue
                            if (report == "FUNCTION_DOWN" || report == "FUNCTION_UP") {
                                val at = fields.getOrNull(2)?.toLongOrNull() ?: continue
                                val revision = contextRevision
                                main.post {
                                    if (listener === run && source === original && original.functionConnected)
                                        functionEdge(original, id, report == "FUNCTION_DOWN", at, revision == contextRevision)
                                }
                                continue
                            }
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
                            input.retire()
                            if (original.functionConnected) {
                                original.functionConnected = false
                                functionSourceLost(original)
                            }
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
            original.rawPress = id // Physical-source metadata only, before any control decision/effect.
        } else {
            if (original.rawPress != id) return
            original.rawPress = null
        }
        val at = SystemClock.uptimeMillis()
        val admitted = if (phase == PadGestureRecognizer.Phase.DOWN) context() else null
        val point = if (phase == PadGestureRecognizer.Phase.DOWN) position() else null
        input.accept(Received(original,id,phase,revision,at,point,admitted))
    }

    private fun acceptAdmitted(received: Received) {
        val original = received.source
        if (source !== original) return
        if (received.phase == PadGestureRecognizer.Phase.DOWN) {
            val admitted = received.context ?: return
            if (received.revision != contextRevision || context() != admitted) return
            val baseline = input.admission
            cancel(original)
            prepareDown()
            if (source !== original || context() != admitted || input.admission != baseline) return
            val press = Press(received.id, PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT,
                PadGestureRecognizer.Phase.DOWN, received.at, received.at, position = received.point), admitted, heldOwner)
            original.press = press
            admission++
            val accepted = press.targetOwner.accept(press.owner, press.down)
            if (!accepted || source !== original || context() != admitted) {
                if (original.press === press) original.press = null
                press.targetOwner.cancel(press.owner, SystemClock.uptimeMillis())
            }
            Log.i("EyeProjectorPad", "Native raw hold DOWN id=${received.id} accepted=$accepted")
        } else {
            val press = original.press?.takeIf { it.id == received.id }
            if (press != null) {
                original.press = null
                if (context() == press.context) press.targetOwner.accept(press.owner,
                    press.down.copy(phase = PadGestureRecognizer.Phase.UP, timeMs = received.at.coerceAtLeast(press.down.timeMs)))
                else press.targetOwner.cancel(press.owner, received.at)
            }
            Log.i("EyeProjectorPad", "Native raw hold UP id=${received.id}")
        }
        changed()
    }

    /** Retire only output ownership; a still-held physical key cannot reacquire after context loss. */
    fun prepareCancel(): InputRetirement {
        powerRequest = null // Retire a queued original before any callback-producing cleanup.
        input.retire()
        val inputBaseline = input.admission
        contextRevision++
        val original = source
        val old = original?.press
        if (original != null) original.press = null
        val revision = contextRevision
        val baseline = admission
        val at = SystemClock.uptimeMillis()
        return InputRetirement({ if (old != null) old.targetOwner.cancel(old.owner, at) },
            { contextRevision != revision || source !== original || admission != baseline || input.admission != inputBaseline })
    }

    fun cancel() = prepareCancel().finishOriginal()

    private fun cancel(original: Source) {
        val press = original.press ?: return
        original.press = null
        press.targetOwner.cancel(press.owner, SystemClock.uptimeMillis())
    }

    /** Sleep uses the retained shell power owner, independently of this Activity's pad connection. */
    fun sleep(): Boolean {
        val original = Any()
        powerRequest = original
        Thread({
            try {
                if (powerRequest !== original) return@Thread
                LocalSocket().use { power ->
                    power.connect(LocalSocketAddress(NativePadReader.POWER_SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT))
                    if (powerRequest !== original) return@use
                    power.outputStream.write("SLEEP\n".toByteArray(Charsets.US_ASCII))
                    power.outputStream.flush()
                }
            } catch (error: IOException) {
                Log.w("EyeProjectorPad", "Glasses sleep needs the retained local shell power owner", error)
            }
        }, "native-pad-sleep").apply { isDaemon = true; start() }
        return true // Queued locally; real power state is established on the device.
    }

    fun stop() {
        powerRequest = null
        input.retire()
        val run = listener
        listener = null
        val original = source
        source = null
        if (original != null) {
            if (original.functionConnected) { original.functionConnected = false; functionSourceLost(original) }
            cancel(original)
        }
        try { run?.socket?.close() } catch (_: IOException) { }
        try { run?.server?.close() } catch (_: IOException) { }
    }
}
