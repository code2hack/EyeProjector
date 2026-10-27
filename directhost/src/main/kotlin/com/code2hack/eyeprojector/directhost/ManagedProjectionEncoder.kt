package com.code2hack.eyeprojector.directhost

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Scrcpy
import com.code2hack.eyeprojector.protocol.ScrcpyRecord
import java.io.Closeable
import java.util.ArrayDeque

/** One actual codec epoch. Its display owner serializes prepare, drain and close off control/output locks. */
internal class ManagedProjectionEncoder : Closeable {
    private var originalCodec: MediaCodec? = null
    private var originalSurface: Surface? = null
    private var started = false
    private var released = false
    private var surfaceReleased = false
    private var closeAttempted = false
    private var closeFailure: Throwable? = null
    private var formatObserved = false
    private var eosObserved = false
    private val pending = ArrayDeque<ScrcpyRecord>()
    lateinit var sourceGeometry: Geometry
        private set

    val inputSurface: Surface get() = originalSurface ?: error("Encoder surface is unavailable")

    fun prepare(source: Geometry) {
        sourceGeometry = source
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        originalCodec = codec // Retain originals before the next fallible native operation.
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            ?: error("AVC video capabilities are unavailable")
        // Surface dimensions follow the selected codec's reported alignment.
        fun aligned(value: Int, alignment: Int) = (value + alignment - 1) / alignment * alignment
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
            aligned(source.width, caps.widthAlignment), aligned(source.height, caps.heightAlignment)).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setFloat("max-fps-to-encoder", 30f) // Same max_fps as the existing scrcpy path.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        originalSurface = codec.createInputSurface()
        codec.start()
        started = true
    }

    /** Null means current output availability only; the caller retains ownership and checks resize/Stop. */
    fun readRecord(): ScrcpyRecord? {
        pending.pollFirst()?.let { return it }
        if (eosObserved) error("Managed encoder ended")
        val codec = originalCodec ?: error("Encoder is unavailable")
        val info = MediaCodec.BufferInfo()
        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return null
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val actual = codec.outputFormat
                val geometry = Geometry(actual.getInteger(MediaFormat.KEY_WIDTH), actual.getInteger(MediaFormat.KEY_HEIGHT))
                pending.add(ScrcpyRecord.Session(geometry, clientResize = false))
                val csd = actual.keys.filter { it.startsWith("csd-") }.sorted().mapNotNull { key ->
                    actual.getByteBuffer(key)?.duplicate()?.let { view ->
                        ByteArray(view.remaining()).also { view.get(it) }
                    }
                }
                if (csd.isNotEmpty()) {
                    val data = ByteArray(csd.sumOf { it.size })
                    var offset = 0
                    csd.forEach { bytes -> bytes.copyInto(data, offset); offset += bytes.size }
                    if (data.isNotEmpty()) pending.add(ScrcpyRecord.Packet(Scrcpy.CONFIG, data))
                }
                formatObserved = true
                return pending.pollFirst()
            }
            else -> {
                check(index >= 0) { "Unexpected encoder output result" }
                val data = try {
                    if (info.size == 0) null else {
                        val view = (codec.getOutputBuffer(index) ?: error("Encoder output is unavailable")).duplicate()
                        view.limit(info.offset + info.size)
                        view.position(info.offset)
                        ByteArray(info.size).also { view.get(it) }
                    }
                } finally {
                    // No borrowed native buffer survives publication or a possible socket write.
                    codec.releaseOutputBuffer(index, false)
                }
                eosObserved = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (data == null) return null
                check(formatObserved) { "Encoder output preceded its actual format" }
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val stamp = if (config) Scrcpy.CONFIG else info.presentationTimeUs or (if (key) Scrcpy.KEY_FRAME else 0L)
                return ScrcpyRecord.Packet(stamp, data)
            }
        }
    }

    /** The display must first detach its producer. Originals remain reachable even after partial failure. */
    override fun close() {
        if (closeAttempted) { closeFailure?.let { throw it }; return }
        closeAttempted = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        originalCodec?.let { codec ->
            if (started) release { codec.stop(); started = false }
            if (!released) release { codec.release(); released = true }
        }
        if (originalCodec == null || released) originalSurface?.let { surface ->
            if (!surfaceReleased) release { surface.release(); surfaceReleased = true }
        }
        pending.clear()
        closeFailure = failure
        failure?.let { throw it }
    }
}
