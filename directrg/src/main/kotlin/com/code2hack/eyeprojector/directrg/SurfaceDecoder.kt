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

/** No encoded frame queue. Hardware input wait and Surface delivery are bounded and observable. */
class SurfaceDecoder(
    surface: Surface,
    geometry: Geometry,
    private val rendered: (Double) -> Unit,
    name: (String) -> Unit,
    private val format: (String) -> Unit,
    private val failed: (Exception) -> Unit,
    private val audioTime: (Long) -> Long? = { null },
    private val sample: (Long, Long) -> Unit = { _, _ -> },
    private val dropped: () -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(true)
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val received = LinkedHashMap<Long, Long>()
    private val released = LinkedHashMap<Long, Long>()
    private val outputThread: Thread

    init {
        try {
            name(codec.name)
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, geometry.width, geometry.height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            }, surface, null, 0)
            codec.setOnFrameRenderedListener({ _, pts, _ ->
                val readAt = synchronized(received) { released.remove(pts) }
                // Use one local clock. The vendor render timestamp was incompatible with
                // System.nanoTime() on the RG; callback receipt is the measured endpoint.
                if (running.get() && readAt != null) {
                    val now = System.nanoTime()
                    rendered((now - readAt) / 1e6); sample(pts, now)
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
                                received.remove(info.presentationTimeUs)?.let { released[info.presentationTimeUs] = it }
                                check(released.size <= 16) { "Surface stalled" }
                            }
                            val target = audioTime(info.presentationTimeUs)
                            val now = System.nanoTime()
                            if (target != null && target < now - 100_000_000L) {
                                synchronized(received) { released.remove(info.presentationTimeUs) }
                                codec.releaseOutputBuffer(index, false); dropped()
                            } else {
                                // Sparse UI updates can lead the current audio position by334ms.
                                // Half a second fits the existing clock freshness and16-frame cap;
                                // this is a bounded wait, not permission to present out of sync.
                                check(target == null || target - now <= 500_000_000L) { "Video/audio clock diverged: videoPtsUs=${info.presentationTimeUs}, aheadMs=${(target!! - now) / 1_000_000.0}" }
                                // Wait only on the output worker; Stop remains bounded and cancels presentation.
                                while (running.get() && target != null && target - System.nanoTime() > 2_000_000L) Thread.sleep(2)
                                codec.releaseOutputBuffer(index, running.get())
                            }
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

    fun enqueue(packet: ScrcpyRecord.Packet, receivedAt: Long) {
        check(running.get())
        val index = codec.dequeueInputBuffer(50_000)
        check(index >= 0) { "Decoder input stalled" }
        val buffer = codec.getInputBuffer(index) ?: error("No decoder buffer")
        require(packet.data.size <= buffer.capacity())
        buffer.clear(); buffer.put(packet.data)
        if (!packet.config) synchronized(received) {
            check(received.size < 16) { "Decoder backlog" }
            received[packet.ptsUs] = receivedAt
        }
        codec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs,
            if (packet.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
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
