package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.util.Size
import com.code2hack.eyeprojector.directrg.localcamera.CameraSampling
import com.code2hack.eyeprojector.directrg.localcamera.LocalCameraInput
import com.code2hack.eyeprojector.directrg.localcamera.LocalInputReleaseResult
import com.code2hack.eyeprojector.directrg.localcamera.LocalOutputReleaseResult
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraOutputsSafe
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraOwnershipSnapshot
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraRunIdentity
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.RequestedCameraOutput
import com.code2hack.eyeprojector.protocol.CameraStreamRequest
import java.util.Collections
import kotlin.math.abs

/** This original permission request cannot grant a replacement use or a replacement Activity. */
internal class ReceiverCameraPermissionRequest(
    val projectionIdentity: Any,
    val original: CapturedPhoneCameraUse,
    val hostIdentity: Any,
    val requestCode: Int,
)

/** Camera dimensions have no dependency on the bounds of the projected Phone image. */
internal data class ReceiverCameraSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

internal data class ReceiverCameraOwnerFailure(val operation: String, val error: Throwable? = null)

/**
 * A selection from the actual CameraManager catalogs, not a successful combined Camera2 session.
 * Requested Phone pixel format stays in the original CameraStreamRequest and is deliberately
 * absent from the two PRIVATE Surface output descriptions below.
 */
internal class ReceiverCameraProfile(
    val cameraId: String,
    val requestedSize: ReceiverCameraSize,
    val localSize: ReceiverCameraSize,
    val encoderSize: ReceiverCameraSize,
    val lensFacing: Int?,
    val sensorOrientation: Int?,
    val hardwareLevel: Int?,
    advertisedAeRanges: List<Pair<Int, Int>>,
    val localMinFrameDurationNs: Long?,
    val encoderMinFrameDurationNs: Long?,
    val encoderBitRate: Int,
    val encoderFrameRate: Int,
    val encoderIFrameIntervalSeconds: Int,
    defaultsApplied: Set<String>,
    localSizeCatalog: List<ReceiverCameraSize>,
    encoderSizeCatalog: List<ReceiverCameraSize>,
    metadataFailures: List<ReceiverCameraOwnerFailure> = emptyList(),
) {
    val advertisedAeRanges = receiverCameraList(advertisedAeRanges)
    val defaultsApplied: Set<String> = Collections.unmodifiableSet(LinkedHashSet(defaultsApplied))
    val localSizeCatalog = receiverCameraList(localSizeCatalog)
    val encoderSizeCatalog = receiverCameraList(encoderSizeCatalog)
    val metadataFailures = receiverCameraList(metadataFailures)
    val localOutput get() = RequestedCameraOutput(localSize.width, localSize.height, ImageFormat.PRIVATE)
    val forwardOutput get() = RequestedCameraOutput(encoderSize.width, encoderSize.height, ImageFormat.PRIVATE)
    val bitRate get() = encoderBitRate
    val frameRate get() = encoderFrameRate
    val iFrameIntervalSeconds get() = encoderIFrameIntervalSeconds

    /** Raw local-buffer sampling only; neither sensor orientation nor Phone layout is applied. */
    val sampling get() = CameraSampling(localSize.width, localSize.height)
    val provenance = "CameraManager SurfaceTexture/MediaCodec catalogs; raw local sampling; Phone correspondence unobserved"

    /** The frozen acquisition adapter does not set CONTROL_AE_TARGET_FPS_RANGE. */
    val cameraAeFpsRangeApplied = false

    /**
     * Phone dimensions/pixel format describe the consumer target and initially hint selection;
     * they do not require equality with this observed native profile. Every explicit encoder
     * option must agree; an absent option imposes no constraint on a running encoder. Scaling,
     * crop/orientation and Phone output production remain separately observed operations.
     */
    fun compatibleWith(request: CameraStreamRequest): Boolean =
        request.requestedMime == AVC_MIME &&
            (request.bitRate == null || request.bitRate == encoderBitRate) &&
            (request.frameRate == null || request.frameRate == encoderFrameRate) &&
            (request.iFrameIntervalSeconds == null || request.iFrameIntervalSeconds == encoderIFrameIntervalSeconds)

    /** Profile equality is never authority to adopt another source's original resources. */
    fun samePhysicalProfile(other: ReceiverCameraProfile): Boolean =
        cameraId == other.cameraId && localSize == other.localSize && encoderSize == other.encoderSize &&
            encoderBitRate == other.encoderBitRate && encoderFrameRate == other.encoderFrameRate &&
            encoderIFrameIntervalSeconds == other.encoderIFrameIntervalSeconds

    companion object { const val AVC_MIME = "video/avc" }
}

/** Failed catalog operations are retained even when another actual camera can be selected. */
internal class ReceiverCameraSelectionFailure(failures: List<ReceiverCameraOwnerFailure>) :
    IllegalStateException("No compatible advertised Glasses camera profile", failures.lastOrNull()?.error) {
    val failures = receiverCameraList(failures)
}

/** Actual production source selection. The owner invokes this, without a caller-provided catalog. */
internal class ReceiverCameraSelector(private val context: Context) {
    fun select(request: CameraStreamRequest): ReceiverCameraProfile {
        val failures = ArrayList<ReceiverCameraOwnerFailure>()
        if (request.requestedMime != ReceiverCameraProfile.AVC_MIME) {
            throw ReceiverCameraSelectionFailure(listOf(ReceiverCameraOwnerFailure("encoder-mime-unsupported")))
        }
        val manager = try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (error: Exception) {
            throw ReceiverCameraSelectionFailure(listOf(ReceiverCameraOwnerFailure("camera-manager", error)))
        } ?: throw ReceiverCameraSelectionFailure(listOf(ReceiverCameraOwnerFailure("camera-manager-unavailable")))
        val ids = try {
            manager.cameraIdList.toList().sorted()
        } catch (error: Exception) {
            throw ReceiverCameraSelectionFailure(listOf(ReceiverCameraOwnerFailure("camera-id-list", error)))
        }
        val profiles = ArrayList<ReceiverCameraProfile>()
        for (id in ids) {
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val map = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                if (map == null) {
                    failures.add(ReceiverCameraOwnerFailure("camera-stream-map-unavailable:$id"))
                    continue
                }
                // Separate consumer classes can advertise different supported dimensions.
                val localSizes = sizes(map.getOutputSizes(SurfaceTexture::class.java))
                val encoderSizes = sizes(map.getOutputSizes(MediaCodec::class.java))
                val local = receiverCameraSizeFor(localSizes, request)
                val encoder = receiverCameraSizeFor(encoderSizes, request)
                if (local == null || encoder == null) {
                    failures.add(ReceiverCameraOwnerFailure("camera-surface-output-unavailable:$id"))
                    continue
                }
                fun <T> metadata(operation: String, read: () -> T?): T? = try {
                    read()
                } catch (error: Exception) {
                    failures.add(ReceiverCameraOwnerFailure("$operation:$id", error))
                    null
                }
                val lens = metadata("camera-lens") { characteristics[CameraCharacteristics.LENS_FACING] }
                val orientation = metadata("camera-sensor-orientation") { characteristics[CameraCharacteristics.SENSOR_ORIENTATION] }
                val level = metadata("camera-hardware-level") { characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL] }
                val ranges = metadata("camera-advertised-ae-ranges") {
                    characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                        ?.map { it.lower to it.upper }
                }.orEmpty()
                val localDuration = metadata("camera-local-min-frame-duration") {
                    map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(local.width, local.height))
                }
                val encoderDuration = metadata("camera-encoder-min-frame-duration") {
                    map.getOutputMinFrameDuration(MediaCodec::class.java, Size(encoder.width, encoder.height))
                }
                profiles.add(ReceiverCameraProfile(
                    cameraId = id,
                    requestedSize = ReceiverCameraSize(request.requestedWidth, request.requestedHeight),
                    localSize = local,
                    encoderSize = encoder,
                    lensFacing = lens,
                    sensorOrientation = orientation,
                    hardwareLevel = level,
                    advertisedAeRanges = ranges,
                    localMinFrameDurationNs = localDuration,
                    encoderMinFrameDurationNs = encoderDuration,
                    encoderBitRate = request.bitRate ?: 2_000_000,
                    encoderFrameRate = request.frameRate ?: 30,
                    encoderIFrameIntervalSeconds = request.iFrameIntervalSeconds ?: 1,
                    defaultsApplied = buildSet {
                        if (request.bitRate == null) add("bitRate")
                        if (request.frameRate == null) add("frameRate")
                        if (request.iFrameIntervalSeconds == null) add("iFrameIntervalSeconds")
                    },
                    localSizeCatalog = localSizes,
                    encoderSizeCatalog = encoderSizes,
                ))
            } catch (error: Exception) {
                failures.add(ReceiverCameraOwnerFailure("camera-profile:$id", error))
            }
        }
        if (profiles.isEmpty()) throw ReceiverCameraSelectionFailure(failures)
        // Prefer an advertised back-facing source, then external, then front/unknown. Metadata
        // does not establish physical view direction. Stable IDs break otherwise equal choices.
        val chosen = profiles.minWithOrNull(compareBy<ReceiverCameraProfile>(
            { lensRank(it.lensFacing) },
            { aspectDistance(it.encoderSize, request) },
            { dimensionDistance(it.encoderSize, request) },
            { aspectDistance(it.localSize, request) },
            { dimensionDistance(it.localSize, request) },
            { it.cameraId },
        ))!!
        return ReceiverCameraProfile(
            chosen.cameraId, chosen.requestedSize, chosen.localSize, chosen.encoderSize,
            chosen.lensFacing, chosen.sensorOrientation, chosen.hardwareLevel, chosen.advertisedAeRanges,
            chosen.localMinFrameDurationNs, chosen.encoderMinFrameDurationNs,
            chosen.encoderBitRate, chosen.encoderFrameRate, chosen.encoderIFrameIntervalSeconds,
            chosen.defaultsApplied, chosen.localSizeCatalog, chosen.encoderSizeCatalog, failures,
        )
    }

    private fun sizes(values: Array<Size>?): List<ReceiverCameraSize> = values.orEmpty()
        .map { ReceiverCameraSize(it.width, it.height) }.distinct()

    private fun lensRank(lens: Int?) = when (lens) {
        CameraCharacteristics.LENS_FACING_BACK -> 0
        CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
        CameraCharacteristics.LENS_FACING_FRONT -> 2
        else -> 3
    }
}

private fun aspectDistance(size: ReceiverCameraSize, request: CameraStreamRequest): Double =
    abs(size.width.toDouble() / size.height - request.requestedWidth.toDouble() / request.requestedHeight)

private fun dimensionDistance(size: ReceiverCameraSize, request: CameraStreamRequest): Double =
    abs(size.width.toDouble() - request.requestedWidth) / request.requestedWidth +
        abs(size.height.toDouble() - request.requestedHeight) / request.requestedHeight

private fun receiverCameraSizeFor(sizes: List<ReceiverCameraSize>, request: CameraStreamRequest): ReceiverCameraSize? =
    sizes.minWithOrNull(compareBy<ReceiverCameraSize>(
        { aspectDistance(it, request) }, { dimensionDistance(it, request) }, { it.width }, { it.height },
    ))

private fun <T> receiverCameraList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Original role object association stays separate from its reusable wire correlation tuple. */
internal class ReceiverCameraRoleCloseObservation(
    val original: CapturedPhoneCameraUse,
    observation: RoleCloseObservation?,
    val pending: Boolean,
) {
    val observation = observation?.copy(
        retirementWorkers = receiverCameraList(observation.retirementWorkers),
        socketCloseResults = receiverCameraList(observation.socketCloseResults),
        completions = receiverCameraList(observation.completions),
    )
}

/** Exact independent facts; a producer fence never substitutes for native release or a join. */
internal class ReceiverCameraSourceCloseObservation(
    val sourceIdentity: Any,
    val profile: ReceiverCameraProfile?,
    val cameraIdentity: CameraRunIdentity? = null,
    val cameraOwnership: CameraOwnershipSnapshot? = null,
    val cameraOutputsSafe: CameraOutputsSafe? = null,
    val cameraOwnerThreadClosedObserved: Boolean? = null,
    val encoderIdentity: SurfaceEncoderIdentity? = null,
    val encoderOwnership: EncoderLifetimeSnapshot? = null,
    val encoderRelease: EncoderReleaseResult? = null,
    val localInput: LocalCameraInput? = null,
    localRelease: LocalInputReleaseResult? = null,
    roles: List<ReceiverCameraRoleCloseObservation> = emptyList(),
    pending: Set<String> = emptySet(),
    failures: List<ReceiverCameraOwnerFailure> = emptyList(),
    outputRelease: LocalOutputReleaseResult? = null,
) {
    val localRelease = localRelease?.copy(
        steps = Collections.unmodifiableMap(LinkedHashMap(localRelease.steps)),
        failures = receiverCameraList(localRelease.failures),
    )
    val outputRelease = outputRelease?.copy(
        inputs = receiverCameraList(outputRelease.inputs.map { input -> input.copy(
            steps = Collections.unmodifiableMap(LinkedHashMap(input.steps)),
            failures = receiverCameraList(input.failures),
        ) }),
        steps = Collections.unmodifiableMap(LinkedHashMap(outputRelease.steps)),
        failures = receiverCameraList(outputRelease.failures),
    )
    val roles = receiverCameraList(roles)
    val pending: Set<String> = Collections.unmodifiableSet(LinkedHashSet(pending))
    val failures = receiverCameraList(failures)
}

internal class ReceiverCameraCloseObservation(
    val projectionIdentity: Any?,
    val resourcesHeld: Boolean,
    sources: List<ReceiverCameraSourceCloseObservation>,
    failures: List<ReceiverCameraOwnerFailure>,
    pendingOperations: Set<String> = emptySet(),
) {
    val sources = receiverCameraList(sources)
    val failures = receiverCameraList(failures)
    val pendingOperations: Set<String> = Collections.unmodifiableSet(LinkedHashSet(pendingOperations))
}
