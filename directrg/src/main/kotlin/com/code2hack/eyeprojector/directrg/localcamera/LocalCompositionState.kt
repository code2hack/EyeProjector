package com.code2hack.eyeprojector.directrg.localcamera

/** A timestamp keeps its original domain; no cross-device latency is derived here. */
internal data class LocalCameraFrame(
    val source: CameraSourceKey,
    val sequence: Long,
    val timestampNanos: Long,
    val clockDomain: String,
)

internal enum class CompositionCause { PHONE_FRAME, CAMERA_FRAME, WINDOW_ATTACH, REDRAW }

internal data class LocalLayer(
    val layout: ViewfinderLayout,
    val frame: LocalCameraFrame,
    val sampling: CameraSampling,
)

/** Immutable associations selected on the output's serialized GL thread, before window swap. */
internal data class CompositionPlan(
    val windowGeneration: Long,
    val image: PhoneImageKey,
    val local: LocalLayer?,
    val cause: CompositionCause,
) {
    // Camera-only swaps must use their own observation, never legacy Phone-presented authority.
    val reportsPhonePresentation get() = cause != CompositionCause.CAMERA_FRAME
}

/**
 * Pure ownership/accounting for the retained-output seam. This owns no Android Surface, camera,
 * decoder, session-readiness state or timer. A caller reports latches/swaps only when they occur.
 */
internal class LocalCompositionState {
    private var image: PhoneImageKey? = null
    private var layout: ViewfinderLayout? = null
    private var source: CameraSourceKey? = null
    private var sampling: CameraSampling? = null
    private var frame: LocalCameraFrame? = null
    private var window: Long? = null
    private var closed = false
    var phoneLatches = 0L
        private set
    var cameraLatches = 0L
        private set
    var phoneWindowSubmissions = 0L
        private set
    var cameraWindowSubmissions = 0L
        private set

    fun phoneLatched(next: PhoneImageKey) {
        if (closed) return
        image = next; phoneLatches++
        if (layout?.image != next) layout = null
    }

    /** Layout publication does not invent a decoded frame. Exact image association is explicit. */
    fun setLayout(next: ViewfinderLayout?) {
        if (!closed) layout = next?.takeIf { it.image == image }
    }

    fun invalidatePhone() {
        image = null; layout = null
        // Acquisition/forwarding lifetime is independent of presentation geometry.
    }

    fun replaceCamera(next: CameraSourceKey, transform: CameraSampling) {
        if (closed) return
        source = next; sampling = transform; frame = null
    }

    fun cameraLatched(next: LocalCameraFrame): Boolean {
        if (closed || next.source != source) return false
        frame = next; cameraLatches++
        return true
    }

    fun closeCamera(original: CameraSourceKey) {
        if (source != original) return
        source = null; sampling = null; frame = null
    }

    fun attachWindow(generation: Long) { if (!closed) window = generation }
    fun detachWindow(originalGeneration: Long) { if (window == originalGeneration) window = null }

    fun plan(cause: CompositionCause): CompositionPlan? {
        if (closed) return null
        val target = window ?: return null
        val phone = image ?: return null
        val mask = layout
        val camera = frame
        val transform = sampling
        val local = if (mask != null && camera != null && transform != null &&
            mask.image == phone && camera.source == source && mask.cameraUse == camera.source.use &&
            mask.cameraUse.projectionEpoch == phone.projectionEpoch) LocalLayer(mask, camera, transform) else null
        return CompositionPlan(target, phone, local, cause)
    }

    /** Call only AFTER a successful EGL swap; an obsolete selection cannot describe its successor. */
    fun submitted(original: CompositionPlan): Boolean {
        if (original != plan(original.cause)) return false
        if (original.reportsPhonePresentation) phoneWindowSubmissions++ else cameraWindowSubmissions++
        return true
    }

    fun close() {
        closed = true; invalidatePhone(); source = null; sampling = null; frame = null; window = null
    }
}
