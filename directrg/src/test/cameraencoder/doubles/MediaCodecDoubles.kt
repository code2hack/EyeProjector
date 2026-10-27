package android.media

import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaCrypto
class MediaCodecInfo {
    object CodecCapabilities { const val COLOR_FormatSurface = 0x7F000789 }
}

/** A local method invocation observation; native/platform entry and quiescence are not simulated. */
class CodecCall(
    val operation: String,
    val codec: MediaCodec?,
    val ordinal: Int,
    val thread: Thread,
    val arguments: Map<String, Any?>,
) {
    @Volatile var returned = false
        internal set
    @Volatile var failure: Throwable? = null
        internal set
}
class CodecNativeGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
}
sealed interface CodecOutput {
    data class Format(val format: MediaFormat) : CodecOutput
    data class Status(val code: Int) : CodecOutput
    class Buffer(
        val index: Int,
        val bytes: ByteBuffer?,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        /** Runs inside the actual releaseOutputBuffer boundary, exposing reads made too late. */
        val recycle: (() -> Unit)? = null,
    ) : CodecOutput {
        val occurrence: Int = occurrences.incrementAndGet()
        companion object { private val occurrences = AtomicInteger() }
    }
}
data class CodecBufferRelease(
    val codec: MediaCodec,
    val index: Int,
    val occurrence: Int,
    val render: Boolean,
    val thread: Thread,
)

object CodecProbe {
    val calls = CopyOnWriteArrayList<CodecCall>()
    val codecs = CopyOnWriteArrayList<MediaCodec>()
    val bufferReleases = CopyOnWriteArrayList<CodecBufferRelease>()
    private val factories = LinkedBlockingQueue<MediaCodec>()
    private val serial = AtomicInteger()
    @Volatile var createFailure: Throwable? = null
    fun supply(codec: MediaCodec) { factories.put(codec) }
    internal fun call(operation: String, codec: MediaCodec?, vararg args: Pair<String, Any?>): CodecCall =
        CodecCall(operation, codec, serial.incrementAndGet(), Thread.currentThread(), args.toMap()).also { calls.add(it) }
    internal fun create(mime: String): MediaCodec {
        val call = call("createEncoderByType", null, "mime" to mime)
        try {
            val failure = createFailure
            createFailure = null
            if (failure != null) throw failure
            val codec = factories.poll() ?: MediaCodec("explicit-avc-${serial.get()}")
            codecs.add(codec); call.returned = true
            return codec
        } catch (error: Throwable) { call.failure = error; throw error }
    }
    fun reset() {
        calls.clear(); codecs.clear(); bufferReleases.clear(); factories.clear(); serial.set(0); createFailure = null
    }
}

/**
 * Explicit synchronous MediaCodec boundary. Scenarios supply output events themselves.
 * No automatic encode, EOS, Camera2 image, drain success or lifecycle state machine is invented.
 */
class MediaCodec(val name: String = "explicit-avc-codec") {
    class BufferInfo {
        var offset = 0
        var size = 0
        var presentationTimeUs = 0L
        var flags = 0
        fun set(offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            this.offset = offset; this.size = size; this.presentationTimeUs = presentationTimeUs; this.flags = flags
        }
    }
    private val outputEvents = LinkedBlockingQueue<CodecOutput>()
    private val active = linkedMapOf<Int, CodecOutput.Buffer>()
    private val failures = linkedMapOf<String, ArrayDeque<Throwable>>()
    private val gates = linkedMapOf<String, ArrayDeque<CodecNativeGate>>()
    private val entrySignals = ConcurrentHashMap<String, CountDownLatch>()
    private val settlementSignals = ConcurrentHashMap<String, CountDownLatch>()
    private var observedFormat = MediaFormat()
    val surfaces = CopyOnWriteArrayList<Surface>()
    @Volatile var configuredFormat: MediaFormat? = null
        private set
    @Volatile var configuredFlags: Int? = null
        private set
    val outputFormat: MediaFormat get() = invoke("getOutputFormat") { observedFormat }
    val inputSurface: Surface get() = surfaces.last()

    fun output(event: CodecOutput) { outputEvents.put(event) }
    /** Deterministic test milestones for first invocation/settlement of a named native boundary. */
    fun entered(operation: String): CountDownLatch = entrySignals.computeIfAbsent(operation) { CountDownLatch(1) }
    fun settled(operation: String): CountDownLatch = settlementSignals.computeIfAbsent(operation) { CountDownLatch(1) }
    @Synchronized fun failNext(operation: String, error: Throwable) {
        failures.getOrPut(operation) { ArrayDeque() }.addLast(error)
    }
    @Synchronized fun holdNext(operation: String): CodecNativeGate = CodecNativeGate().also {
        gates.getOrPut(operation) { ArrayDeque() }.addLast(it)
    }
    @Synchronized private fun takeFailure(operation: String): Throwable? = failures[operation]?.pollFirst()
    @Synchronized private fun takeGate(operation: String): CodecNativeGate? = gates[operation]?.pollFirst()

    private fun <T> invoke(operation: String, vararg args: Pair<String, Any?>, action: () -> T): T {
        val call = CodecProbe.call(operation, this, *args)
        entered(operation).countDown()
        try {
            takeGate(operation)?.let { gate -> gate.entered.countDown(); gate.release.await() }
            takeFailure(operation)?.let { throw it }
            val result = action()
            call.returned = true
            return result
        } catch (error: Throwable) { call.failure = error; throw error }
        finally { settled(operation).countDown() }
    }

    fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) =
        invoke("configure", "format" to format, "surface" to surface, "crypto" to crypto, "flags" to flags) {
            configuredFormat = format; configuredFlags = flags
        }
    fun createInputSurface(): Surface = invoke("createInputSurface") {
        Surface("$name-input-${surfaces.size}").also { surfaces.add(it) }
    }
    fun start() = invoke("start") { Unit }
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = invoke("dequeueOutputBuffer", "timeoutUs" to timeoutUs) {
        val event = if (timeoutUs > 0) outputEvents.poll(timeoutUs, TimeUnit.MICROSECONDS) else outputEvents.poll()
        when (event) {
            null -> INFO_TRY_AGAIN_LATER
            is CodecOutput.Format -> { observedFormat = event.format; INFO_OUTPUT_FORMAT_CHANGED }
            is CodecOutput.Status -> event.code
            is CodecOutput.Buffer -> {
                info.set(event.offset, event.size, event.presentationTimeUs, event.flags)
                active[event.index] = event
                event.index
            }
        }
    }
    fun getOutputBuffer(index: Int): ByteBuffer? = invoke("getOutputBuffer", "index" to index) { active[index]?.bytes }
    fun releaseOutputBuffer(index: Int, render: Boolean) = invoke("releaseOutputBuffer", "index" to index, "render" to render) {
        val event = checkNotNull(active.remove(index)) { "No original output occurrence for index $index" }
        CodecProbe.bufferReleases.add(CodecBufferRelease(this, index, event.occurrence, render, Thread.currentThread()))
        event.recycle?.invoke()
    }
    fun signalEndOfInputStream() = invoke("signalEndOfInputStream") { Unit }
    fun stop() = invoke("stop") { Unit }
    fun release() = invoke("release") { Unit }

    companion object {
        const val CONFIGURE_FLAG_ENCODE = 1
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        const val INFO_OUTPUT_BUFFERS_CHANGED = -3
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4
        @JvmStatic fun createEncoderByType(mime: String): MediaCodec = CodecProbe.create(mime)
    }
}
