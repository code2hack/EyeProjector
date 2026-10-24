package android.media

import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** JVM double for the platform allocation and initialization boundaries. */
class MediaCodec {
    companion object {
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        const val INFO_TRY_AGAIN_LATER = -1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        var created = MediaCodec()
        @JvmStatic fun createDecoderByType(type: String): MediaCodec = created
    }
    class BufferInfo { @JvmField var presentationTimeUs = 0L }
    fun interface OnFrameRenderedListener { fun onFrameRendered(codec: MediaCodec, pts: Long, time: Long) }
    var at: (String) -> Unit = {}
    var output: (BufferInfo) -> Int = { -1 }
    var releasedOutput: (Boolean) -> Unit = {}
    var queuedInput: (Long, Int) -> Unit = { _, _ -> }
    var input: (Long) -> Int = { 0 }
    var frameListener: OnFrameRenderedListener? = null
    var configuredSurface: Surface? = null
    val releases = AtomicInteger()
    var releaseFailure: Throwable? = null
    val name: String get() { at("name"); return "test-avc" }
    fun configure(format: MediaFormat, surface: Surface, crypto: android.media.MediaCrypto?, flags: Int) { configuredSurface = surface; at("configure") }
    fun setOnFrameRenderedListener(listener: OnFrameRenderedListener, handler: Handler) { at("listener"); frameListener = listener }
    fun start() { at("start") }
    fun dequeueOutputBuffer(info: BufferInfo, timeout: Long): Int { at("dequeueOutput"); Thread.sleep(1); return output(info) }
    val outputFormat: MediaFormat get() = MediaFormat()
    fun releaseOutputBuffer(index: Int, render: Boolean) { releasedOutput(render) }
    fun dequeueInputBuffer(timeout: Long) = input(timeout)
    fun getInputBuffer(index: Int): ByteBuffer = ByteBuffer.allocate(1024)
    fun queueInputBuffer(index: Int, offset: Int, size: Int, pts: Long, flags: Int) { queuedInput(pts, flags) }
    fun stop() = Unit
    fun release() { at("release"); releases.incrementAndGet(); releaseFailure?.let { throw it } }
}

class MediaCrypto
