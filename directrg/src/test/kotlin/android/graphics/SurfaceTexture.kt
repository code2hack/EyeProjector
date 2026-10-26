package android.graphics

import android.os.Handler
import java.util.concurrent.atomic.AtomicInteger

/** Platform boundary double, not a GPU/video decoder. */
class SurfaceTexture(name: Int) {
    fun interface OnFrameAvailableListener { fun onFrameAvailable(texture: SurfaceTexture) }
    private var listener: OnFrameAvailableListener? = null
    private var handler: Handler? = null
    var released = false
    val updates = AtomicInteger()
    @Volatile var latest = 0L
    val timestamp: Long get() = latest
    fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?, handler: Handler) {
        this.listener = listener; this.handler = handler
    }
    fun frame(value: Long) {
        latest = value
        handler?.post(Runnable { listener?.onFrameAvailable(this) })
    }
    fun updateTexImage() { check(!released); updates.incrementAndGet() }
    fun getTransformMatrix(matrix: FloatArray) { for (i in matrix.indices) matrix[i] = if (i % 5 == 0) 1f else 0f }
    fun release() { released = true }
}
