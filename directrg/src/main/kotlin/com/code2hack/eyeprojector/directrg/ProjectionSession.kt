package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import org.json.JSONArray
import org.json.JSONObject

class ProjectionSession(
    private val directory: File,
    private val surface: Surface,
    private val onState: (ProjectionSession, String) -> Unit,
    private val onGeometry: (ProjectionSession, Geometry) -> Unit,
    private val context: Context? = null,
    private val onScale: (ProjectionSession, ScaleState) -> Unit = { _, _ -> },
) : Closeable {
    private val running = AtomicBoolean(true)
    private val timer = Executors.newScheduledThreadPool(2)
    private val controls = OrderedControlDispatcher { error -> failure(error); closeWithState("DISCONNECTED") }
    private val scaleControls = OrderedControlDispatcher { error -> failure(error); closeWithState("DISCONNECTED") }
    private val outputLock = Any()
    private val lifecycleLock = Any()
    private val closeLock = Any()
    @Volatile private var closeComplete = false
    private var closingThread: Thread? = null
    private val writing = AtomicLong()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var decoder: SurfaceDecoder? = null
    private var decoderEpoch: Any? = null
    private var droppedVideo = 0L
    @Volatile private var mediaStartedNs = 0L
    @Volatile var geometry: Geometry? = null
        private set
    @Volatile private var readyForTouch = false
    @Volatile var scaleState: ScaleState? = null
        private set
    private val started = System.nanoTime()
    private val stats = JSONObject().put("state", "CONNECTING").put("audio", "system-bluetooth")
    private var packets = 0L
    private var changes = 0L
    private var rendered = 0L
    private var touches = 0L
    private var scrolls = 0L
    private var navigations = 0L
    private var inputRejected = 0L
    private var lastHash: ByteArray? = null
    private val renderDelayMs = ArrayDeque<Double>()
    private val roundTripMs = ArrayDeque<Double>()

    @Synchronized private fun record(state: String? = null) {
        if (state != null) stats.put("state", state)
        decoder?.let { stats.put("decoderProgress", it.snapshot()) }
        val seconds = (System.nanoTime() - started) / 1e9
        fun p95(values: ArrayDeque<Double>): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * .95).toInt()] }
        stats.put("startedMonoNs", started).put("elapsedMs", (seconds * 1000).toLong()).put("packets", packets).put("changingPackets", changes)
            .put("rendered", rendered).put("receivedFps", if (seconds > 0) packets / seconds else 0)
            .put("renderedFps", if (seconds > 0) rendered / seconds else 0).put("touchesSent", touches)
            .put("scrollsSent", scrolls).put("navigationsSent", navigations)
            .put("packetReadToCallbackP95Ms", p95(renderDelayMs)).put("networkRoundTripP95Ms", p95(roundTripMs))
            .put("latencyScope", "RG System.nanoTime packet-read to render-callback receipt; network echo RTT; no optical/input end-to-end claim")
            .put("maxEncodedPacketBytes", Wire.MAX_PAYLOAD).put("maxInflightFrames", 16)
        stats.put("videoLateDrops", droppedVideo)
            .put("videoTiming", "Video PTS relative to first frame receipt in each decoder stream; Receiver monotonic clock")
            .put("avScope", "Native Bluetooth audio is independent; physical AV offset is unmeasured")
        val input = controls.snapshot()
        stats.put("controlsAccepted", input.accepted).put("controlsWritten", input.written)
            .put("controlsCanceled", input.canceled).put("controlsFailed", input.failed)
            .put("controlsRejected", input.rejected + inputRejected).put("controlsPending", input.pending).put("controlsInFlight", input.inFlight)
            .put("controlSubmissionToWriteMeanMs", input.meanWriteDelayMs).put("controlSubmissionToWriteMaxMs", input.maxWriteDelayMs)
            .put("controlDeliveryScope", "written means TLS write returned; no target-injection acknowledgment")
        val writeBegin = System.nanoTime()
        val file = AtomicFile(File(directory.parentFile, "status.json"))
        val out = file.startWrite()
        try { out.write(stats.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
        finally {
            val ms = (System.nanoTime() - writeBegin) / 1_000_000
            if (ms > 250) Log.w("EyeProjector", "Status write took ${ms}ms")
        }
    }

    @Synchronized private fun state(value: String) {
        if (!running.get() && value in listOf("CONNECTING", "WAITING_FOR_VIDEO", "STREAMING")) return
        record(value); onState(this, value)
    }

    @Synchronized private fun failure(error: Exception) {
        if (!running.get()) return
        Log.e("EyeProjector", "Projection failed", error)
        val causes = JSONArray()
        var cause: Throwable? = error
        repeat(6) { cause?.let { causes.put(it.javaClass.simpleName); cause = it.cause } }
        stats.put("errorType", error.javaClass.simpleName).put("causeClasses", causes)
    }

    private fun send(frame: Frame) = synchronized(outputLock) {
        check(running.get())
        writing.set(System.nanoTime())
        try { Wire.writeFrame(output ?: error("No authenticated connection"), frame) }
        finally { writing.set(0) }
    }

    fun start() {
        state("CONNECTING")
        Thread({
            try {
                val peer = Tls.connect(directory)
                socket = peer
                if (!running.get()) { peer.close(); return@Thread }
                output = DataOutputStream(peer.outputStream)
                Wire.writeRequest(output!!, Wire.STREAM)
                val input = DataInputStream(peer.inputStream)
                Wire.readWelcome(input)
                mediaStartedNs = System.nanoTime()
                synchronized(this) {
                    stats.put("tls", peer.session.protocol).put("peerAddress", peer.inetAddress.hostAddress)
                        .put("localAddress", peer.localAddress.hostAddress).put("scrcpy", Wire.SCRCPY_VERSION)
                }
                state("WAITING_FOR_VIDEO")
                timer.scheduleWithFixedDelay({
                    try {
                        send(Frame(Wire.PING, System.nanoTime()))
                        if (!readyForTouch && System.nanoTime() - mediaStartedNs > 5_000_000_000L) {
                            closeWithState("VIDEO_STALLED")
                        } else record()
                    }
                    catch (_: Exception) { closeWithState("DISCONNECTED") }
                }, 0, 1, TimeUnit.SECONDS)
                timer.scheduleWithFixedDelay({
                    val since = writing.get()
                    if (since != 0L && System.nanoTime() - since > 2_000_000_000L) closeWithState("WRITE_STALLED")
                }, 250, 250, TimeUnit.MILLISECONDS)
                while (running.get()) {
                    val readBegin = System.nanoTime()
                    val frame = try { Wire.readFrame(input) } finally {
                        val ms = (System.nanoTime() - readBegin) / 1_000_000
                        if (ms > 1000) Log.w("EyeProjector", "Frame read took ${ms}ms")
                    }
                    val handleBegin = System.nanoTime()
                    try { receive(frame) } finally {
                        val ms = (System.nanoTime() - handleBegin) / 1_000_000
                        if (ms > 500) Log.w("EyeProjector", "Frame handling channel=${frame.channel} took ${ms}ms")
                    }
                }
                closeWithState("DISCONNECTED")
            } catch (error: Exception) { failure(error); closeWithState("CONNECTION_OR_VIDEO_FAILED") }
        }, "eyeprojector-receiver").start()
    }

    internal fun receive(frame: Frame) {
        when (frame.channel) {
            Wire.SCALE_STATE -> {
                val next = ScaleState.from(frame.payload)
                scaleState = next
                onScale(this, next)
            }
            Wire.GEOMETRY -> {
                val data = DataInputStream(frame.payload.inputStream())
                val size = Geometry(data.readInt(), data.readInt())
                replaceDecoder(size)
            }
            Wire.VIDEO -> {
                require(frame.stamp >= 0)
                val packet = ScrcpyRecord.Packet(frame.stamp, frame.payload)
                if (!packet.config) synchronized(this) {
                    packets++
                    val hash = MessageDigest.getInstance("SHA-256").digest(frame.payload)
                    if (lastHash?.contentEquals(hash) != true) changes++
                    lastHash = hash
                }
                (decoder ?: error("Missing geometry")).enqueue(packet, System.nanoTime())
            }
            Wire.PONG -> synchronized(this) {
                val delay = (System.nanoTime() - frame.stamp) / 1e6
                require(delay in 0.0..5000.0)
                roundTripMs.addLast(delay); if (roundTripMs.size > 256) roundTripMs.removeFirst()
            }
            Wire.END -> closeWithState("DISCONNECTED")
            else -> error("Unnegotiated media")
        }
    }

    internal fun replaceDecoder(size: Geometry) = synchronized(closeLock) {
        if (!running.get()) return@synchronized
        controls.cancelPending()
        val epoch = Any()
        val old = synchronized(lifecycleLock) {
            decoderEpoch = epoch
            readyForTouch = false
            decoder.also { decoder = null }
        }
        old?.close()
        mediaStartedNs = System.nanoTime()
        state("WAITING_FOR_VIDEO")
        geometry = size
        onGeometry(this, size)
        synchronized(this) { stats.put("width", size.width).put("height", size.height) }
        if (!running.get()) return@synchronized
        val next = SurfaceDecoder(surface, size, { delay ->
            synchronized(this) {
                rendered++; renderDelayMs.addLast(delay)
                if (renderDelayMs.size > 256) renderDelayMs.removeFirst()
            }
            synchronized(lifecycleLock) {
                if (decoderEpoch === epoch && running.get() && !readyForTouch) {
                    readyForTouch = true; state("STREAMING")
                }
            }
        }, { name -> synchronized(this) { stats.put("decoder", name) } },
            { format -> synchronized(this) { stats.put("decoderFormat", format) } }, { error -> failure(error); closeWithState("DECODE_FAILED") },
            dropped = { synchronized(this) { droppedVideo++ } })
        val installed = synchronized(lifecycleLock) {
            if (running.get() && decoderEpoch === epoch) { decoder = next; true } else false
        }
        if (!installed) next.close()
    }

    /** Acceptance here means queued locally; onScale/scaleState supplies backend observations. */
    fun requestScale(value: Double, requestId: Long = System.nanoTime()): Boolean {
        if (!running.get() || geometry == null) return false
        return scaleControls.submit(System.nanoTime() / 1_000_000, { running.get() && geometry != null }, "scale") {
            send(Frame(Wire.SCALE_REQUEST, requestId, ScaleRequest(value).bytes()))
        }
    }

    /** True means queued, not an acknowledgment from the Phone's target widget. */
    fun tap(tap: Tap, eventTimeMs: Long = System.nanoTime() / 1_000_000): Boolean =
        input(Wire.TAP, tap.geometry, tap.bytes(), eventTimeMs)
    fun scroll(scroll: Scroll, eventTimeMs: Long = System.nanoTime() / 1_000_000, headMotion: Boolean = false): Boolean =
        input(Wire.SCROLL, scroll.target.geometry, scroll.bytes(), eventTimeMs, if (headMotion) "head-scroll" else "input")
    fun navigate(action: Navigation): Boolean {
        val size = geometry ?: return false
        return input(Wire.NAVIGATE, size, Navigate(action, size).bytes(), System.nanoTime() / 1_000_000)
    }

    fun cancelPendingInput() = controls.cancelPending()
    fun cancelHeadScroll() = controls.cancelPending("head-scroll")

    private fun input(channel: Int, size: Geometry, payload: ByteArray, eventTimeMs: Long, category: String = "input"): Boolean {
        if (!running.get() || !readyForTouch || size != geometry) {
            synchronized(this) { inputRejected++ }
            return false
        }
        return controls.submit(eventTimeMs, { running.get() && readyForTouch && size == geometry }, category) {
            send(Frame(channel, payload = payload))
            synchronized(this) { when (channel) {
                Wire.TAP -> touches++
                Wire.SCROLL -> scrolls++
                Wire.NAVIGATE -> navigations++
            } }
        }
    }

    private fun closeWithState(value: String) {
        if (closeComplete) return
        // Cancel construction immediately; wait below for its unpublished codec to be released.
        running.set(false)
        synchronized(closeLock) {
            if (closeComplete || closingThread === Thread.currentThread()) return
            closingThread = Thread.currentThread()
            try {
                val old = synchronized(lifecycleLock) {
                    running.set(false)
                    readyForTouch = false
                    decoder.also { decoder = null }
                }
                runCatching { socket?.close() }; timer.shutdownNow(); controls.close()
                scaleControls.close()
                runCatching { synchronized(this) { old?.let { stats.put("decoderProgress", it.snapshot()) } } }
                runCatching { old?.close() }
                closeComplete = true
            } finally { closingThread = null }
        }
        // Callbacks may synchronously close again or wait for another close caller.
        state(value)
    }
    override fun close() = closeWithState("STOPPED")
}
