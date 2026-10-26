package android.view

import android.graphics.SurfaceTexture
import android.opengl.GlProbe

/** Host identity and dimensions only; release accounting distinguishes owned and borrowed wrappers. */
class Surface private constructor(
    val surfaceTexture: SurfaceTexture?,
    width: Int,
    height: Int,
    val label: String,
) {
    val id = SurfaceProbe.nextId()
    @Volatile var width: Int = width
    @Volatile var height: Int = height
    @Volatile var releaseCount = 0
        private set
    @Volatile var releaseReturnedCount = 0
        private set
    @Volatile var failNextRelease: Throwable? = null
    val isValid: Boolean get() = releaseCount == 0

    constructor(texture: SurfaceTexture) : this(texture, texture.defaultWidth, texture.defaultHeight, "texture")
    constructor(width: Int = 640, height: Int = 480, label: String = "borrowed") : this(null, width, height, label)
    constructor(label: String) : this(null, 640, 480, label)

    init {
        GlProbe.record("Surface.create", "id" to id, "texture" to surfaceTexture?.textureName, "label" to label)
        if (GlProbe.takeFailure("surface")) error("Injected Surface construction failure")
        SurfaceProbe.add(this)
    }

    @Synchronized fun release() {
        releaseCount++
        GlProbe.record("Surface.release", "id" to id, "count" to releaseCount)
        val failure = failNextRelease
        failNextRelease = null
        if (failure != null) throw failure
        releaseReturnedCount++
    }

    override fun toString(): String = "Surface($id,$label,${width}x$height)"
}

object SurfaceProbe {
    private val lock = Any()
    private var sequence = 1
    private val created = mutableListOf<Surface>()
    val surfaces: List<Surface> get() = synchronized(lock) { created.toList() }
    internal fun nextId(): Int = synchronized(lock) { sequence++ }
    internal fun add(surface: Surface) = synchronized(lock) { created.add(surface); Unit }
    fun reset() = synchronized(lock) { created.clear(); sequence = 1 }
}
