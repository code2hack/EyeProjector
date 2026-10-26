package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.os.SystemClock
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
    private val onReceiverInput: (ProjectionSession, ReceiverInput) -> Unit = { _, _ -> },
    private val nextUsabilityRevision: () -> Long = { System.nanoTime() },
) : Closeable {
    private val running = AtomicBoolean(true)
    private val timer = Executors.newScheduledThreadPool(2)
    private val controls = OrderedControlDispatcher { error -> failure(error); closeWithState("DISCONNECTED") }
    private val scaleControls = OrderedControlDispatcher { error -> failure(error); closeWithState("DISCONNECTED") }
    private val inputLock = Any()
    private val heldTouch = HeldTouchTransport(controls,
        { running.get() && readyForTouch && it.target.geometry == geometry },
        {
            send(Frame(Wire.TOUCH, payload = it.bytes()))
            synchronized(this) { heldTouchFrames++ }
        })
    private val outputLock = Any()
    @Volatile private var controlReady = false
    @Volatile private var sourceUsability: ReceiverUsability? = null
    private var serializedUsability: ReceiverUsability? = null
    private var ownerUsabilityRevision = -1L
    // Separate from native timed input cancellation; both use the existing serialized stream writer.
    private val headPointer = LatestHeadPointer({ action ->
        scaleControls.submit(System.nanoTime() / 1_000_000, { running.get() }, "head-pointer", preserveTiming = false, write = action)
    }, { send(Frame(Wire.HEAD_POINTER, payload = it.bytes())) }, SystemClock::elapsedRealtimeNanos,
        { action -> synchronized(outputLock) { action() } }, compose = { point ->
            // Selection of latest mode and latest point is atomic after output-lock waits.
            var mode = sourceUsability
            if (point != null && mode != null && point.revision > mode.inputRevision) {
                mode = mode.copy(inputRevision = point.revision, revision = nextUsabilityRevision())
                sourceUsability = mode
            }
            val selectedMode = mode
            val selectedPoint = point?.let { p ->
                if (mode != null && (mode.mode == ReceiverUsabilityMode.NONE || p.revision < mode.inputRevision)) p.copy(available = false) else p
            }
            val action: () -> Unit = {
                writeUsabilityLocked(selectedMode)
                if (selectedPoint != null) writeFrameLocked(Frame(Wire.HEAD_POINTER, payload = selectedPoint.bytes()))
            }
            action
        })
    private val lifecycleLock = Any()
    private val closeLock = Any()
    @Volatile private var closeComplete = false
    private var closingThread: Thread? = null
    private val videoReadiness = VideoReadiness()
    private val states = StatePublisher()
    // Guarded by closeLock; nested replacement frames share the outer publication gate.
    private var stateGate: StatePublisher.Gate? = null
    private val writing = AtomicLong()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var decoder: SurfaceDecoder? = null
    @Volatile private var decoderEpoch: Any? = null
    private var droppedVideo = 0L
    @Volatile var geometry: Geometry? = null
        private set
    private var pointerSequence = 0L
    private var lastHeadPointer: HeadPointerState? = null
    @Volatile private var presentationActive = true
    // Once explicit source ownership exists, an old legacy positive callback cannot
    // override a newer NONE. Legacy-only sessions retain their existing boolean behavior.
    private val presentationUsable get() = sourceUsability?.let { it.mode != ReceiverUsabilityMode.NONE } ?: presentationActive
    private val readyForTouch get() = presentationUsable && videoReadiness.ready

    /** Rendering visibility is independent of authenticated transport and offscreen decoding. */
    fun presentation(active: Boolean) = synchronized(inputLock) {
        presentationActive = active
        if (!active) {
            sourceUsability?.let { fact ->
                usability(fact.copy(mode = ReceiverUsabilityMode.NONE, revision = nextUsabilityRevision())) }

            cancelPendingInput()
            lastHeadPointer?.let {
                val unavailable = it.copy(available = false, sequence = ++pointerSequence)
                lastHeadPointer = unavailable
                headPointer.publish(unavailable, 0)
            }
        }
    }
    @Volatile var scaleState: ScaleState? = null
        private set
    private val started = System.nanoTime()
    /** Read-only diagnostic provenance; neither accessor publishes or allocates a revision. */
    internal val observationStartedMonoNs: Long get() = started
    internal fun observationUsability(): ReceiverUsability? = sourceUsability
    private val stats = JSONObject().put("state", "CONNECTING").put("audio", "system-bluetooth")
    private var packets = 0L
    private var changes = 0L
    private var rendered = 0L
    private var touches = 0L
    private var heldTouchFrames = 0L
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
            .put("heldTouchFramesWritten", heldTouchFrames).put("scrollsSent", scrolls).put("navigationsSent", navigations)
            .put("packetReadToCallbackP95Ms", p95(renderDelayMs)).put("networkRoundTripP95Ms", p95(roundTripMs))
            .put("latencyScope", "Receiver packet-read to decoder Surface callback receipt; network echo RTT; window presentation and optical/input timing are separate")
            .put("presentationActive", presentationUsable)
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

    private fun state(value: String, epoch: Any? = null) {
        val gate = if (Thread.holdsLock(closeLock)) stateGate else null
        states.enqueue(gate) {
            if (!running.get() && value in listOf("CONNECTING", "WAITING_FOR_VIDEO", "STREAMING")) return@enqueue
            if (epoch != null && when (value) {
                "WAITING_FOR_VIDEO" -> !videoReadiness.awaiting(epoch)
                "STREAMING" -> !videoReadiness.ready(epoch)
                else -> false
            }) return@enqueue
            // Serialized publication closes the check/callback gap: an older render either
            // finishes before the next WAITING/terminal notification, or is discarded here.
            record(value)
            onState(this, value)
        }
        drainStates()
    }

    private fun drainStates() {
        // A release or geometry callback can reenter close while replacement owns this monitor.
        // The outermost replacement drains after releasing it, including on early return/failure.
        if (!Thread.holdsLock(closeLock)) states.drain()
    }

    @Synchronized private fun failure(error: Exception) {
        if (!running.get()) return
        Log.e("EyeProjector", "Projection failed", error)
        val causes = JSONArray()
        var cause: Throwable? = error
        repeat(6) { cause?.let { causes.put(it.javaClass.simpleName); cause = it.cause } }
        stats.put("errorType", error.javaClass.simpleName).put("causeClasses", causes)
    }

    fun usability(fact: ReceiverUsability) {
        headPointer.sourceChanged {
            val old = sourceUsability
            if (old == null || old.incarnation == fact.incarnation && fact.revision > ownerUsabilityRevision ||
                fact.incarnation.sequence > old.incarnation.sequence) {
                ownerUsabilityRevision = fact.revision
                sourceUsability = fact.copy(revision = nextUsabilityRevision())
            }
        }
    }
    private fun writeUsabilityLocked(fact: ReceiverUsability?) {
        if (controlReady && fact != null && fact != serializedUsability) {
            writeFrameLocked(Frame(Wire.RECEIVER_USABILITY, payload = fact.bytes()))
            serializedUsability = fact
        }
    }
    private fun writeFrameLocked(frame: Frame) {
        check(running.get())
        writing.set(System.nanoTime())
        try { Wire.writeFrame(output ?: error("No authenticated connection"), frame) }
        finally { writing.set(0) }
    }
    private fun send(frame: Frame) = synchronized(outputLock) {
        writeUsabilityLocked(sourceUsability)
        writeFrameLocked(frame)
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
                controlReady = true
                val initialEpoch = videoReadiness.awaitInitialFrame(System.nanoTime())
                synchronized(this) {
                    stats.put("tls", peer.session.protocol).put("peerAddress", peer.inetAddress.hostAddress)
                        .put("localAddress", peer.localAddress.hostAddress).put("scrcpy", Wire.SCRCPY_VERSION)
                }
                if (initialEpoch != null) state("WAITING_FOR_VIDEO", initialEpoch)
                timer.scheduleWithFixedDelay({
                    try {
                        heartbeat()
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

    internal fun heartbeat(nowNs: Long = System.nanoTime()) {
        send(Frame(Wire.PING, System.nanoTime()))
        val expiry = videoReadiness.expiry(nowNs)
        if (expiry != null) closeWithState("VIDEO_STALLED", expiry) else record()
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
            Wire.RECEIVER_INPUT -> onReceiverInput(this, ReceiverInput.from(frame.payload))
            else -> error("Unnegotiated media")
        }
    }

    internal fun replaceDecoder(size: Geometry) {
        var gate: StatePublisher.Gate? = null
        try {
            synchronized(closeLock) {
                if (stateGate == null) {
                    gate = StatePublisher.Gate()
                    stateGate = gate
                }
                try {
                    val epoch = synchronized(inputLock) {
                        if (!running.get()) return
                        val next = videoReadiness.beginReplacement() ?: return
                        cancelPendingInput()
                        next
                    }
                    val old = synchronized(lifecycleLock) {
                        if (!running.get() || !videoReadiness.replacing(epoch)) return
                        decoderEpoch = epoch
                        decoder.also { decoder = null }
                    }
                    old?.close()
                    if (!running.get() || !videoReadiness.awaitFrame(epoch, System.nanoTime())) return
                    state("WAITING_FOR_VIDEO", epoch)
                    geometry = size
                    onGeometry(this, size)
                    synchronized(this) { stats.put("width", size.width).put("height", size.height) }
                    if (!running.get() || !videoReadiness.awaiting(epoch)) return
                    val next = SurfaceDecoder(surface, size, { delay ->
                        synchronized(this) {
                            rendered++; renderDelayMs.addLast(delay)
                            if (renderDelayMs.size > 256) renderDelayMs.removeFirst()
                        }
                        if (running.get() && videoReadiness.rendered(epoch)) state("STREAMING", epoch)
                    }, { name -> synchronized(this) { stats.put("decoder", name) } },
                        { format -> if (decoderEpoch === epoch) synchronized(this) { stats.put("decoderFormat", format) } }, { error ->
                            if (videoReadiness.claimFailure(epoch)) { failure(error); closeWithState("DECODE_FAILED") }
                        },
                        dropped = { synchronized(this) { droppedVideo++ } })
                    val installed = synchronized(lifecycleLock) {
                        if (running.get() && decoderEpoch === epoch) { decoder = next; true } else false
                    }
                    if (!installed) next.close()
                } finally { if (gate != null) stateGate = null }
            }
        } finally {
            gate?.release()
            drainStates()
        }
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

    fun touch(event: Touch, eventTimeMs: Long): Boolean = synchronized(inputLock) {
        heldTouch.submit(event, eventTimeMs).also { accepted ->
            if (!accepted) synchronized(this) { inputRejected++ }
        }
    }

    fun cancelPendingInput() = synchronized(inputLock) {
        controls.cancelPending()
        heldTouch.cancel(0)
    }
    fun cancelHeadScroll() = controls.cancelPending("head-scroll")
    fun headPointer(state: HeadPointerState, sourceExpiresAtNs: Long): Boolean = synchronized(inputLock) {
        if (!running.get() || geometry == null) return false
        val current = state.copy(sequence = ++pointerSequence, available = state.available && presentationUsable)
        lastHeadPointer = current
        headPointer.publish(current, sourceExpiresAtNs)
    }

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

    private fun closeWithState(value: String, expiry: VideoReadiness.Expiry? = null) {
        if (expiry != null && !videoReadiness.claim(expiry)) return
        sourceUsability?.let { usability(it.copy(mode = ReceiverUsabilityMode.NONE, revision = nextUsabilityRevision())) }
        videoReadiness.close()
        // Cancel construction immediately; wait below for its unpublished codec to be released.
        running.set(false)
        // Always join the outer replacement frame: a reentrant close may have marked
        // completion while that frame still owns a detached or unpublished codec.
        synchronized(closeLock) {
            if (closeComplete || closingThread === Thread.currentThread()) return
            closingThread = Thread.currentThread()
            try {
                val old = synchronized(lifecycleLock) {
                    running.set(false)
                    decoder.also { decoder = null }
                }
                runCatching { socket?.close() }; timer.shutdownNow(); controls.close()
                headPointer.close(); scaleControls.close()
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
