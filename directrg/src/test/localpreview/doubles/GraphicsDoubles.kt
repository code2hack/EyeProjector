package android.graphics

import android.opengl.GlProbe
import android.os.Handler

/** A controllable producer endpoint. No image pixels or platform GL work are simulated. */
class SurfaceTexture(val textureName: Int) {
    fun interface OnFrameAvailableListener {
        fun onFrameAvailable(surfaceTexture: SurfaceTexture)
    }

    private data class Registration(val listener: OnFrameAvailableListener, val handler: Handler?)
    private var registration: Registration? = null
    private var pendingTimestamp = 0L
    private var pendingTransform = identity()
    private var acquiredTransform = identity()
    @Volatile var timestamp: Long = 0L
        private set
    @Volatile var releaseCount = 0
        private set
    @Volatile var releaseReturnedCount = 0
        private set
    @Volatile var failNextRelease: Throwable? = null
    @Volatile var failNextListenerDetach: Throwable? = null
    @Volatile var updateCount = 0
        private set
    @Volatile var defaultWidth = 0
        private set
    @Volatile var defaultHeight = 0
        private set

    init {
        GlProbe.record("SurfaceTexture.create", "texture" to textureName)
        if (GlProbe.takeFailure("surfaceTexture")) error("Injected SurfaceTexture construction failure")
        SurfaceTextureProbe.add(this)
    }

    @Synchronized fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?, handler: Handler?) {
        if (listener == null) {
            val failure = failNextListenerDetach
            failNextListenerDetach = null
            if (failure != null) throw failure
        }
        registration = listener?.let { Registration(it, handler) }
        GlProbe.record("SurfaceTexture.listener", "texture" to textureName, "attached" to (listener != null))
    }

    fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?) = setOnFrameAvailableListener(listener, null)

    @Synchronized fun setDefaultBufferSize(width: Int, height: Int) {
        defaultWidth = width
        defaultHeight = height
        GlProbe.record("SurfaceTexture.setDefaultBufferSize", "texture" to textureName, "width" to width, "height" to height)
    }

    @Synchronized fun updateTexImage() {
        GlProbe.record("SurfaceTexture.updateTexImage", "texture" to textureName)
        if (GlProbe.takeFailure("updateTexImage")) error("Injected updateTexImage failure")
        check(releaseCount == 0) { "SurfaceTexture already released" }
        updateCount++
        timestamp = pendingTimestamp
        acquiredTransform = pendingTransform.copyOf()
    }

    @Synchronized fun getTransformMatrix(destination: FloatArray) {
        GlProbe.record("SurfaceTexture.getTransformMatrix", "texture" to textureName)
        if (GlProbe.takeFailure("getTransformMatrix")) error("Injected transform read failure")
        acquiredTransform.copyInto(destination, endIndex = 16)
    }

    @Synchronized fun release() {
        releaseCount++
        registration = null
        GlProbe.record("SurfaceTexture.release", "texture" to textureName, "count" to releaseCount)
        val failure = failNextRelease
        failNextRelease = null
        if (failure != null) throw failure
        releaseReturnedCount++
    }

    /** Schedule through the registered Handler, just as the production callback route does. */
    fun emitFrame(timestampNs: Long = timestamp + 1L, transform: FloatArray = identity()): Boolean {
        val captured = synchronized(this) {
            pendingTimestamp = timestampNs
            pendingTransform = transform.copyOf()
            registration
        } ?: return false
        val action = Runnable { captured.listener.onFrameAvailable(this) }
        return captured.handler?.post(action) ?: run { action.run(); true }
    }

    /** Capture the listener itself; invoking it later forces an already captured stale callback. */
    @Synchronized fun captureFrameCallback(): () -> Unit {
        val captured = registration
        return { captured?.listener?.onFrameAvailable(this) }
    }

    /** Capture a delivery which still uses the originally registered Handler. */
    @Synchronized fun capturePostedFrameCallback(): () -> Boolean {
        val captured = registration
        return {
            if (captured == null) false else {
                val action = Runnable { captured.listener.onFrameAvailable(this) }
                captured.handler?.post(action) ?: run { action.run(); true }
            }
        }
    }

    companion object {
        fun identity(): FloatArray = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        )
    }
}

object SurfaceTextureProbe {
    private val lock = Any()
    private val created = mutableListOf<SurfaceTexture>()
    val textures: List<SurfaceTexture> get() = synchronized(lock) { created.toList() }
    val latest: SurfaceTexture get() = synchronized(lock) { created.last() }
    internal fun add(texture: SurfaceTexture) = synchronized(lock) { created.add(texture); Unit }
    fun reset() = synchronized(lock) { created.clear() }
}
