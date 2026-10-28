package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.ScrcpyRecord
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** No encoded frame queue. Render callback history is diagnostic, never a media admission limit. */
class SurfaceDecoder(
    surface: Surface,
    geometry: Geometry,
    private val rendered: (Double?) -> Unit,
    name: (String) -> Unit,
    private val format: (String) -> Unit,
    private val failed: (Exception) -> Unit,
    private val sample: (Long, Long) -> Unit = { _, _ -> },
    private val dropped: () -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(true)
    private val videoClock = VideoClock()
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val received = LinkedHashMap<Long, Long>()
    private val released = LinkedHashMap<Long, Long>()
    // All progress fields share the frame-tracking monitor. No extra encoded queue.
    private var outputProgress = 0L
    private var presentationTargetNs = 0L
    private var queuedInputs = 0L
    private var configInputs = 0L
    private var outputBuffers = 0L
    private var outputReleases = 0L
    private var renderCallbacks = 0L
    private var renderHistoryEvictions = 0L
    private var unmatchedRenderCallbacks = 0L
    private var inputRetries = 0L
    private var maxInputWaitNs = 0L
    private var lastInputPtsUs = -1L
    private var lastOutputPtsUs = -1L
    private var lastRenderPtsUs = -1L
    private var lastInputNs = 0L
    private var lastOutputNs = 0L
    private var lastRenderNs = 0L
    private val outputThread: Thread

    init {
        try {
            name(codec.name)
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, geometry.width, geometry.height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            }, surface, null, 0)
            codec.setOnFrameRenderedListener({ _, pts, _ ->
                val readAt = synchronized(received) {
                    outputProgress++; renderCallbacks++
                    lastRenderPtsUs = pts; lastRenderNs = System.nanoTime()
                    released.remove(pts).also { if (it == null) unmatchedRenderCallbacks++ }
                }
                // Use one local clock. The vendor render timestamp was incompatible with
                // System.nanoTime() on the RG; callback receipt is the measured endpoint.
                if (running.get()) {
                    val now = System.nanoTime()
                    // A delayed callback remains an actual render observation after its latency
                    // sample has aged out. Missing history must not suppress readiness or invent delay.
                    rendered(readAt?.let { (now - it) / 1e6 }); sample(pts, now)
                }
            }, Handler(Looper.getMainLooper()))
            codec.start()
            outputThread = Thread({
                val info = MediaCodec.BufferInfo()
                try {
                    while (running.get()) {
                        val index = codec.dequeueOutputBuffer(info, 20_000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val output = codec.outputFormat
                            val width = output.getInteger(MediaFormat.KEY_WIDTH)
                            val height = output.getInteger(MediaFormat.KEY_HEIGHT)
                            fun crop(key: String, fallback: Int) = if (output.containsKey(key)) output.getInteger(key) else fallback
                            val croppedWidth = crop("crop-right", width - 1) - crop("crop-left", 0) + 1
                            val croppedHeight = crop("crop-bottom", height - 1) - crop("crop-top", 0) + 1
                            format(output.toString())
                            require(croppedWidth == geometry.width && croppedHeight == geometry.height) { "Unsupported crop" }
                        }
                        if (index >= 0) {
                            synchronized(received) {
                                outputProgress++; outputBuffers++
                                lastOutputPtsUs = info.presentationTimeUs; lastOutputNs = System.nanoTime()
                                received.remove(info.presentationTimeUs)?.let { released[info.presentationTimeUs] = it }
                                // Retain the existing history size for latency measurement only.
                                // MAIN callback scheduling can lag healthy output by many frames.
                                while (released.size > 16) {
                                    val oldest = released.entries.iterator()
                                    oldest.next(); oldest.remove(); renderHistoryEvictions++
                                }
                            }
                            val target = videoClock.timeNs(info.presentationTimeUs) ?: error("Missing video timing origin")
                            val now = System.nanoTime()
                            check(target - now <= 500_000_000L) { "Video clock diverged: videoPtsUs=${info.presentationTimeUs}, aheadMs=${(target - now) / 1_000_000.0}" }
                            // A late completed frame may be the final redraw of a static UI.
                            // Present it now; only future targets wait on this output worker.
                            synchronized(received) { presentationTargetNs = target }
                            try {
                                while (running.get() && target - System.nanoTime() > 2_000_000L) Thread.sleep(2)
                                release(index, running.get())
                            } finally { synchronized(received) { presentationTargetNs = 0 } }
                        }
                    }
                } catch (error: Exception) { if (running.get()) failed(error) }
            }, "eyeprojector-decoder").apply { start() }
        } catch (error: Throwable) {
            running.set(false)
            runCatching { codec.release() }
            throw error
        }
    }

    private fun release(index: Int, render: Boolean) {
        codec.releaseOutputBuffer(index, render)
        synchronized(received) { outputProgress++; outputReleases++ }
    }

    private fun inputBuffer(): Int {
        val begin = System.nanoTime()
        var progress = synchronized(received) { outputProgress }
        try {
            while (true) {
                check(running.get()) { "Decoder closed" }
                val index = codec.dequeueInputBuffer(50_000)
                check(running.get()) { "Decoder closed" }
                if (index >= 0) return index
                val retry = synchronized(received) {
                    inputRetries++
                    maxInputWaitNs = maxOf(maxInputWaitNs, System.nanoTime() - begin)
                    // INFO_TRY_AGAIN_LATER is availability, not a codec error. The output
                    // worker can itself hold codec capacity for an admitted future PTS.
                    val active = outputProgress != progress || presentationTargetNs > System.nanoTime()
                    progress = outputProgress
                    index == MediaCodec.INFO_TRY_AGAIN_LATER && active
                }
                check(retry) { "Decoder input stalled: ${snapshot()}" }
            }
        } finally {
            synchronized(received) { maxInputWaitNs = maxOf(maxInputWaitNs, System.nanoTime() - begin) }
        }
    }

    /** Packet admission, output and callback facts; callbacks are not optical measurements. */
    fun snapshot(): JSONObject = synchronized(received) {
        JSONObject().put("sampleMonoNs", System.nanoTime()).put("running", running.get())
            .put("queuedInputs", queuedInputs).put("configInputs", configInputs)
            .put("outputBuffers", outputBuffers).put("outputReleases", outputReleases)
            .put("renderCallbacks", renderCallbacks).put("inputRetries", inputRetries)
            .put("maxInputWaitMs", maxInputWaitNs / 1e6)
            .put("lastInputPtsUs", lastInputPtsUs).put("lastInputMonoNs", lastInputNs)
            .put("lastOutputPtsUs", lastOutputPtsUs).put("lastOutputMonoNs", lastOutputNs)
            .put("lastRenderPtsUs", lastRenderPtsUs).put("lastRenderMonoNs", lastRenderNs)
            .put("pendingDecodeFrames", received.size)
            .put("pendingRenderFrames", maxOf(0L, outputReleases - renderCallbacks))
            .put("renderHistoryEntries", released.size).put("renderHistoryEvictions", renderHistoryEvictions)
            .put("unmatchedRenderCallbacks", unmatchedRenderCallbacks)
            .put("presentationTargetMonoNs", presentationTargetNs)
    }

    fun enqueue(packet: ScrcpyRecord.Packet, receivedAt: Long) {
        check(running.get())
        val index = inputBuffer()
        val buffer = codec.getInputBuffer(index) ?: error("No decoder buffer")
        require(packet.data.size <= buffer.capacity())
        buffer.clear(); buffer.put(packet.data)
        if (!packet.config) synchronized(received) {
            videoClock.start(packet.ptsUs, receivedAt)
            check(received.size < 16) { "Decoder backlog" }
            received[packet.ptsUs] = receivedAt
        }
        codec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs,
            if (packet.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        synchronized(received) {
            queuedInputs++; if (packet.config) configInputs++
            lastInputPtsUs = packet.ptsUs; lastInputNs = System.nanoTime()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        var interrupted = false
        try {
            if (Thread.currentThread() !== outputThread) outputThread.join(1000)
        } catch (_: InterruptedException) { interrupted = true }
        finally {
            try { runCatching { codec.stop() }; codec.release() }
            finally {
                synchronized(received) { received.clear(); released.clear() }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
