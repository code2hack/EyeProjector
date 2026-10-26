package com.code2hack.eyeprojector.directrg.localcamera

import android.view.Surface
import java.io.Closeable

/** Opaque identity issued only after this output's actual Phone texture latch. Not a wire frame ID. */
internal class PhoneTextureLatchKey internal constructor(
    internal val outputIdentity: Any,
    val latchSequence: Long,
    val surfaceTimestampNanos: Long,
)

internal enum class LayoutCorrespondence { FIXTURE_ASSERTED }
internal data class FixturePhoneBinding(
    val latch: PhoneTextureLatchKey,
    val image: PhoneImageKey,
    val layout: ViewfinderLayout?,
) {
    val correspondence = LayoutCorrespondence.FIXTURE_ASSERTED
}

internal data class LocalCameraConfiguration(
    val source: CameraSourceKey,
    val sampling: CameraSampling,
    // Requested/default buffer size, NOT an observation of actual producer buffer dimensions.
    val requestedBufferWidth: Int,
    val requestedBufferHeight: Int,
) {
    init { require(requestedBufferWidth > 0 && requestedBufferHeight > 0) }
}

internal data class LocalTextureLatch(
    val sequence: Long,
    val surfaceTimestampNanos: Long,
) {
    // SurfaceTexture's producer timestamp domain is not established by this API.
    val timestampClockDomain = "SURFACE_TEXTURE_PRODUCER_UNVERIFIED"
}

internal enum class LocalDrawCause {
    PHONE_LATCH, WINDOW_ATTACH, LEGACY_REDRAW, CAMERA_LATCH, FIXTURE_LAYOUT, CAMERA_REMOVE;
    val reportsLegacyPhone get() = this == PHONE_LATCH || this == WINDOW_ATTACH || this == LEGACY_REDRAW
}

/** Successful mask-contributing swap; neither Camera2 frame identity nor optical presentation. */
internal data class LocalPresentationReceipt(
    val input: LocalCameraInput,
    val configuration: LocalCameraConfiguration,
    val localLatch: LocalTextureLatch,
    val fixtureBinding: FixturePhoneBinding,
    val target: Surface,
    val windowGeneration: Long,
    val cause: LocalDrawCause,
    val swapReturnMonotonicNanos: Long,
) {
    val swapClockDomain = "GLASSES_SYSTEM_NANO_TIME"
}

internal data class LocalCameraFailure(val operation: String, val error: Exception, val input: LocalCameraInput)
internal interface LocalCameraObserver {
    fun presented(receipt: LocalPresentationReceipt) = Unit
    fun failed(failure: LocalCameraFailure) = Unit
}

/**
 * Owns its Surface wrapper, SurfaceTexture and OES texture. The producer borrows [surface] and
 * owns/stops its own producer before closing an unbound legacy lease. Real Camera2 users register
 * [ProducerRetirementInput] before borrowing; logical retirement then requests that original
 * producer's nonblocking stop and holds resources until its actual output-use fence.
 */
internal interface LocalCameraInput : Closeable {
    val surface: Surface
    val configuration: LocalCameraConfiguration
    val retired: Boolean
    val lastFailure: LocalCameraFailure?
}

/** Optional capability; the legacy ReceiverVideoOutput and its callers remain unchanged. */
internal interface LocalPreviewOutput {
    fun currentPhoneTextureLatch(): PhoneTextureLatchKey?
    /** Caller asserts fixture correspondence to EXACTLY this actual latch, not an encoded frame. */
    fun bindFixturePhoneImage(latch: PhoneTextureLatchKey, image: PhoneImageKey, layout: ViewfinderLayout?): Boolean
    /** Replaces/retires the original lease; default size and claimed sampling size remain distinct. */
    fun openLocalCamera(configuration: LocalCameraConfiguration, observer: LocalCameraObserver? = null): LocalCameraInput
}
