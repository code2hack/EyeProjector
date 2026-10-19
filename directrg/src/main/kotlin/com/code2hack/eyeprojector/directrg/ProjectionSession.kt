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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
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
) : Closeable {
    private val running = AtomicBoolean(true)
    private val timer = Executors.newScheduledThreadPool(2)
    private val controls = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, SynchronousQueue())
    private val outputLock = Any()
    private val lifecycleLock = Any()
    private val writing = AtomicLong()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var decoder: SurfaceDecoder? = null
    @Volatile private var audio: AudioDecoder? = null
    private val playbackClock = PlaybackClock()
    private var audioCodec = 0
    private val avSamples = ArrayDeque<JSONObject>()
    private var lastAvSampleNs = 0L
    private var droppedVideo = 0L
    private var mediaStartedNs = 0L
    private var stoppedAudio: JSONObject? = null
    @Volatile var geometry: Geometry? = null
        private set
    @Volatile private var readyForTouch = false
    private val started = System.nanoTime()
    private val stats = JSONObject().put("state", "CONNECTING").put("audio", "required-waiting")
    private var packets = 0L
    private var changes = 0L
    private var rendered = 0L
    private var touches = 0L
    private var scrolls = 0L
    private var navigations = 0L
    private var lastHash: ByteArray? = null
    private val renderDelayMs = ArrayDeque<Double>()
    private val roundTripMs = ArrayDeque<Double>()

    @Synchronized private fun record(state: String? = null) {
        if (state != null) stats.put("state", state)
        val seconds = (System.nanoTime() - started) / 1e9
        fun p95(values: ArrayDeque<Double>): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * .95).toInt()] }
        stats.put("startedMonoNs", started).put("elapsedMs", (seconds * 1000).toLong()).put("packets", packets).put("changingPackets", changes)
            .put("rendered", rendered).put("receivedFps", if (seconds > 0) packets / seconds else 0)
            .put("renderedFps", if (seconds > 0) rendered / seconds else 0).put("touchesSent", touches)
            .put("scrollsSent", scrolls).put("navigationsSent", navigations)
            .put("packetReadToCallbackP95Ms", p95(renderDelayMs)).put("networkRoundTripP95Ms", p95(roundTripMs))
            .put("latencyScope", "RG System.nanoTime packet-read to render-callback receipt; network echo RTT; no optical/input end-to-end claim")
            .put("maxEncodedPacketBytes", Wire.MAX_PAYLOAD).put("maxInflightFrames", 16).put("queuedTaps", 0)
        stats.put("audio", audio?.snapshot() ?: stoppedAudio ?: "required-waiting")
            .put("videoLateDrops", droppedVideo).put("avSample", avSamples.lastOrNull())
            .put("avScope", "Phone source PTS compared using RG AudioTimestamp clock and RG render-callback receipt; not optical/acoustic offset")
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
        if (!running.get() && value in listOf("CONNECTING", "WAITING_FOR_VIDEO", "WAITING_FOR_AUDIO", "WAITING_FOR_AUDIO_CLOCK", "STREAMING")) return
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
                        val current = audio
                        if ((current == null && System.nanoTime() - mediaStartedNs > 5_000_000_000L) || current?.stalled() == true) {
                            closeWithState("AUDIO_STALLED")
                        } else if (current != null) {
                            val audioStatus = current.snapshot()
                            if (current.clockStalled()) {
                                closeWithState("AUDIO_CLOCK_UNAVAILABLE")
                            } else if (readyForTouch && audioStatus.optLong("decodedBuffers", 0) > 0 && audioStatus.optBoolean("timestampValid")) {
                                state("STREAMING")
                            } else record()
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
                    try { when (frame.channel) {
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
                        Wire.AUDIO_CODEC -> {
                            require(audioCodec == 0) { "Repeated audio codec" }
                            audioCodec = DataInputStream(frame.payload.inputStream()).readInt()
                            check(audioCodec == Wire.OPUS) { "Required audio capture unavailable: $audioCodec" }
                        }
                        Wire.AUDIO -> {
                            require(audioCodec == Wire.OPUS && frame.stamp >= 0)
                            val packet = ScrcpyRecord.Packet(frame.stamp, frame.payload)
                            if (packet.config) {
                                check(audio == null) { "Repeated audio config" }
                                val next = AudioDecoder(context ?: error("Audio context unavailable"), packet.data, playbackClock) { error ->
                                    failure(error); closeWithState("AUDIO_FAILED")
                                }
                                val installed = synchronized(lifecycleLock) {
                                    if (running.get()) { audio = next; true } else false
                                }
                                if (!installed) next.close()
                            } else (audio ?: error("Missing audio configuration")).enqueue(packet)
                        }
                        Wire.PONG -> synchronized(this) {
                            val delay = (System.nanoTime() - frame.stamp) / 1e6
                            require(delay in 0.0..5000.0)
                            roundTripMs.addLast(delay); if (roundTripMs.size > 256) roundTripMs.removeFirst()
                        }
                        Wire.END -> break
                        else -> error("Unnegotiated media")
                    } } finally {
                        val ms = (System.nanoTime() - handleBegin) / 1_000_000
                        if (ms > 500) Log.w("EyeProjector", "Frame handling channel=${frame.channel} took ${ms}ms")
                    }
                }
                closeWithState("DISCONNECTED")
            } catch (error: Exception) { failure(error); closeWithState("CONNECTION_OR_REQUIRED_AUDIO_FAILED") }
        }, "eyeprojector-receiver").start()
    }

    internal fun replaceDecoder(size: Geometry) {
        val old = synchronized(lifecycleLock) {
            readyForTouch = false
            decoder.also { decoder = null }
        }
        old?.close()
        state("WAITING_FOR_VIDEO")
        geometry = size
        onGeometry(this, size)
        synchronized(this) { stats.put("width", size.width).put("height", size.height) }
        val next = SurfaceDecoder(surface, size, { delay ->
            synchronized(this) {
                rendered++; renderDelayMs.addLast(delay)
                if (renderDelayMs.size > 256) renderDelayMs.removeFirst()
            }
            if (!readyForTouch && running.get()) { readyForTouch = true; state(if (audio != null) "WAITING_FOR_AUDIO_CLOCK" else "WAITING_FOR_AUDIO") }
        }, { name -> synchronized(this) { stats.put("decoder", name) } },
            { format -> synchronized(this) { stats.put("decoderFormat", format) } }, { error -> failure(error); closeWithState("DECODE_FAILED") },
            { pts -> playbackClock.videoTimeNs(pts, System.nanoTime()) },
            { pts, now -> synchronized(this) {
                if (now - lastAvSampleNs >= 1_000_000_000L) {
                    playbackClock.ptsAt(now)?.let { audioPts ->
                        avSamples.addLast(JSONObject().put("elapsedMs", (now - started) / 1_000_000)
                            .put("videoPtsUs", pts).put("audioPtsUs", audioPts).put("videoMinusAudioMs", (pts - audioPts) / 1000.0))
                        if (avSamples.size > 2) avSamples.removeFirst()
                        lastAvSampleNs = now
                    }
                }
            } }, { synchronized(this) { droppedVideo++ } })
        val installed = synchronized(lifecycleLock) {
            if (running.get()) { decoder = next; true } else false
        }
        if (!installed) next.close()
    }

    fun tap(tap: Tap): Boolean = input(Wire.TAP, tap.geometry, tap.bytes())
    fun scroll(scroll: Scroll): Boolean = input(Wire.SCROLL, scroll.target.geometry, scroll.bytes())
    fun navigate(action: Navigation): Boolean {
        val size = geometry ?: return false
        return input(Wire.NAVIGATE, size, Navigate(action, size).bytes())
    }

    private fun input(channel: Int, size: Geometry, payload: ByteArray): Boolean {
        if (!running.get() || !readyForTouch || size != geometry) return false
        return try {
            // SynchronousQueue retains no command. Pause/loss closes the socket and rejects later work.
            controls.execute {
                try {
                    if (running.get() && readyForTouch && size == geometry) {
                        send(Frame(channel, payload = payload))
                        synchronized(this) { when (channel) {
                            Wire.TAP -> touches++
                            Wire.SCROLL -> scrolls++
                            Wire.NAVIGATE -> navigations++
                        } }
                    }
                } catch (_: Exception) { closeWithState("DISCONNECTED") }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }

    private fun closeWithState(value: String) {
        val old = synchronized(lifecycleLock) {
            if (!running.compareAndSet(true, false)) return
            readyForTouch = false
            decoder.also { decoder = null }
        }
        val oldAudio = synchronized(lifecycleLock) { audio.also { audio = null } }
        runCatching { socket?.close() }; timer.shutdownNow(); controls.shutdownNow()
        runCatching { oldAudio?.close() }
        stoppedAudio = oldAudio?.snapshot()
        runCatching { old?.close() }
        state(value)
    }
    override fun close() = closeWithState("STOPPED")
}
