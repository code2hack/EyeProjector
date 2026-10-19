package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.protocol.ScrcpyRecord
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** One codec, one output worker, no application packet/PCM queue; stalls fail the mandatory-audio session. */
internal class AudioDecoder(
    context: Context,
    config: ByteArray,
    private val clock: PlaybackClock,
    private val failed: (Exception) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val trackLock = Any()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN && running.get()) failed(IllegalStateException("Audio focus lost"))
        }, Handler(Looper.getMainLooper())).build()
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var focusHeld = false
    private val decoded = AtomicLong()
    private val writtenFrames = AtomicLong()
    private val lastOutput = AtomicLong(System.nanoTime())
    private val lastClock = AtomicLong(System.nanoTime())
    @Volatile private var lastPts = -1L
    @Volatile private var peakQueuedFrames = 0L
    @Volatile private var peakPcm = 0.0
    @Volatile private var timestampValid = false
    @Volatile private var decoderName = ""
    @Volatile private var route = -1
    @Volatile private var underruns = 0
    @Volatile private var bufferFrames = 0
    private val began = System.nanoTime()
    private val knownTones = KnownTones()

    init {
        try {
            require(config.size == 19 && config.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII)))
            require(config[8].toInt() == 1 && config[9].toInt() == 2 && config[18].toInt() == 0) { "Unsupported upstream Opus layout" }
            val preSkip = (config[10].toInt() and 255) or ((config[11].toInt() and 255) shl 8)
            fun ns(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 2).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
                setByteBuffer("csd-1", ns(preSkip * 1_000_000_000L / 48_000))
                setByteBuffer("csd-2", ns(80_000_000))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable" }
            focusHeld = true
            val minimum = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum in 1..38_400) { "Unsupported minimum audio buffer" }
            track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 19_200)).build()
            check(track!!.state == AudioTrack.STATE_INITIALIZED)
            bufferFrames = track!!.bufferSizeInFrames
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            decoderName = codec!!.name
            codec!!.configure(format, null, null, 0); codec!!.start()
            track!!.play()
            worker = Thread({ output() }, "eyeprojector-audio-output").apply { start() }
        } catch (error: Throwable) { close(); throw error }
    }

    fun enqueue(packet: ScrcpyRecord.Packet) {
        check(running.get() && !packet.config)
        val decoder = codec ?: error("Missing audio decoder")
        val index = decoder.dequeueInputBuffer(250_000)
        check(index >= 0) { "Audio decoder input stalled" }
        val buffer = decoder.getInputBuffer(index) ?: error("No audio input buffer")
        require(packet.data.size <= buffer.capacity())
        buffer.clear(); buffer.put(packet.data)
        decoder.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs, 0)
    }

    private fun output() {
        val decoder = codec!!; val audio = track!!
        val info = MediaCodec.BufferInfo(); val timestamp = AudioTimestamp()
        try {
            while (running.get()) {
                val index = decoder.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48_000 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2)
                    require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                }
                if (index >= 0) {
                    try {
                        val buffer = decoder.getOutputBuffer(index) ?: error("No decoded audio")
                        require(info.size % 4 == 0 && info.presentationTimeUs >= 0)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        knownTones.observe(buffer, System.nanoTime())
                        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var peak = 0
                        while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))
                        peakPcm = maxOf(peakPcm, peak / 32768.0)
                        val firstFrame = writtenFrames.get()
                        clock.append(firstFrame, info.presentationTimeUs)
                        val deadline = System.nanoTime() + 250_000_000L
                        while (buffer.hasRemaining() && running.get()) {
                            val n = audio.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            check(n >= 0 && n % 4 == 0) { "AudioTrack write failed" }
                            writtenFrames.addAndGet(n / 4L)
                            check(System.nanoTime() < deadline) { "AudioTrack stalled" }
                            if (n == 0) Thread.sleep(2)
                        }
                        if (!running.get()) continue
                        lastPts = info.presentationTimeUs; decoded.incrementAndGet(); lastOutput.set(System.nanoTime())
                        synchronized(trackLock) {
                            if (running.get()) {
                                val now = System.nanoTime()
                                timestampValid = audio.getTimestamp(timestamp) && clock.update(timestamp.framePosition, timestamp.nanoTime, now)
                                if (timestampValid) lastClock.set(now)
                                val head = if (timestampValid) minOf(writtenFrames.get(), timestamp.framePosition + (now - timestamp.nanoTime) * 48_000 / 1_000_000_000)
                                    else audio.playbackHeadPosition.toLong() and 0xffffffffL
                                val queued = (writtenFrames.get() - head).coerceAtLeast(0)
                                peakQueuedFrames = maxOf(peakQueuedFrames, queued)
                                check(queued <= 14_400) { "Audio playback backlog" }
                                underruns = audio.underrunCount; route = audio.routedDevice?.type ?: -1
                                check(route !in listOf(7, 8, 26, 27, 30)) { "Bluetooth output is unsupported" }
                            }
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
        } catch (error: Exception) { if (running.get()) failed(error) }
    }

    fun clockStalled() = System.nanoTime() - lastClock.get() > 5_000_000_000L
    fun stalled() = System.nanoTime() - lastOutput.get() > 5_000_000_000L
    fun snapshot(): JSONObject {
        val tones = knownTones.peaks(System.nanoTime())
        return JSONObject().put("fixture440Peak1s", tones.first).put("fixture1000Peak1s", tones.second).put("state", if (running.get()) "PLAYING_UNCONFIRMED_AUDIBILITY" else "STOPPED")
        .put("codec", decoderName).put("sampleRate", 48_000).put("channels", 2).put("pcmEncoding", "PCM16")
        .put("decodedBuffers", decoded.get()).put("writtenFrames", writtenFrames.get()).put("lastSourcePtsUs", lastPts)
        .put("timestampValid", clock.ptsAt(System.nanoTime()) != null).put("peakQueuedMs", peakQueuedFrames / 48.0)
        .put("trackBufferMs", bufferFrames / 48.0).put("peakPcm", peakPcm).put("underruns", underruns)
        .put("routeType", route).put("elapsedMs", (System.nanoTime() - began) / 1_000_000)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        val audio = track
        synchronized(trackLock) { runCatching { audio?.pause() }; runCatching { audio?.flush() } }
        var interrupted = false
        try { if (Thread.currentThread() !== worker) worker?.join(1000) }
        catch (_: InterruptedException) { interrupted = true }
        finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { audio?.release() }
            if (focusHeld) runCatching { manager.abandonAudioFocusRequest(focus) }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
