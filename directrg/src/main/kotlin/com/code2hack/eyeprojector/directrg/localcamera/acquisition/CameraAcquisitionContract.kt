package com.code2hack.eyeprojector.directrg.localcamera.acquisition

import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.CameraSampling
import com.code2hack.eyeprojector.directrg.localcamera.CameraSourceKey
import com.code2hack.eyeprojector.directrg.localcamera.LocalCameraInput

/** Requested configuration only; neither this object nor a Surface proves device support. */
internal data class RequestedCameraOutput(val width: Int, val height: Int, val format: Int) {
    init { require(width > 0 && height > 0) }
}

internal data class CameraUseDescription(
    val source: CameraSourceKey,
    val cameraId: String,
    val localOutput: RequestedCameraOutput,
    val forwardingOutput: RequestedCameraOutput,
    val framing: CameraSampling,
    val provenance: String,
)

/** Object identity is independent of reusable source/use/stream generation numbers. */
internal class CameraRunIdentity internal constructor()
internal data class ForwardCameraStream(val source: CameraSourceKey, val streamGeneration: Long)

/** The encoder/media owner prepares and releases this endpoint. Acquisition only borrows it. */
internal interface ForwardCameraInputLease {
    val surface: Surface
    val stream: ForwardCameraStream
    val requestedOutput: RequestedCameraOutput
    /** Output-use fence only, not encoder drain, encoded bytes, transport or Phone capture. */
    fun producerStopped(original: CameraRunIdentity, fact: CameraOutputsSafe)
}

/** Optional association with one original producer before Camera2 work or either Surface borrow. */
internal interface ProducerBoundForwardCameraInputLease : ForwardCameraInputLease {
    /**
     * Claim the original before invoking external callbacks, and invoke them outside the binding
     * lock. False rejects this caller; a throw after claim retains the original producer duty
     * until its actual [producerStopped] fact. Legacy forwarding leases need not implement this.
     */
    fun bindProducer(original: CameraRunIdentity, requestStop: () -> Unit): Boolean
}

internal data class CameraTargets(val local: LocalCameraInput, val forwarding: ForwardCameraInputLease)

internal enum class SessionRetirement { SESSION_ON_CLOSED, CONFIGURATION_FAILED, PARENT_DEVICE_ON_CLOSED }
internal enum class CameraOutputFence { NO_OPEN_INVOKED, KNOWN_OPEN_REJECTED_BEFORE_ACCEPTANCE, ORIGINAL_DEVICE_ON_CLOSED }
internal enum class CameraArrivalDisposition { NONE, PENDING, ARRIVED, REJECTED_BEFORE_ACCEPTANCE, RETIRED_BY_ORIGINAL_PARENT_FENCE, INDETERMINATE }
internal enum class CameraCallOperation { OPEN, CONFIGURE }

internal data class CameraCallFailureContext(
    val original: CameraRunIdentity,
    val operation: CameraCallOperation,
    val request: CameraUseDescription,
    val failure: Exception,
)

/**
 * Explicit supported operation/path evidence, not a catch-type rule or vendor attestation.
 * Future ordinary permission/platform wiring supplies a resolver only when its observed path
 * supports this disposition. The default adapter cannot infer it from exception names/messages.
 */
internal data class CameraRejectedBeforeAcceptance(
    val original: CameraRunIdentity,
    val operation: CameraCallOperation,
    val originalFailure: Exception,
    val provenance: String,
)

internal fun interface CameraCallOutcomeResolver {
    fun rejection(context: CameraCallFailureContext): CameraRejectedBeforeAcceptance?
}

internal data class CameraOwnershipSnapshot(
    val openPending: Boolean = false,
    val configurationPending: Boolean = false,
    val openInvoked: Boolean = false,
    val hasDevice: Boolean = false,
    val hasSession: Boolean = false,
    val closing: Boolean = false,
    val deviceCloseInvoked: Boolean = false,
    val deviceCloseCallReturned: Boolean = false,
    val sessionCloseInvoked: Boolean = false,
    val sessionCloseCallReturned: Boolean = false,
    val deviceClosedObserved: Boolean = false,
    val sessionClosedObserved: Boolean = false,
    val sessionRetirement: SessionRetirement? = null,
    val outputsSafe: Boolean = false,
    val openDisposition: CameraArrivalDisposition = CameraArrivalDisposition.NONE,
    val configurationDisposition: CameraArrivalDisposition = CameraArrivalDisposition.NONE,
    val openArrivalObserved: Boolean = false,
    val configurationArrivalObserved: Boolean = false,
    val openRejectionProvenance: String? = null,
    val configurationRejectionProvenance: String? = null,
)

internal data class CameraOutputsSafe(
    val original: CameraRunIdentity,
    val fence: CameraOutputFence,
    val ownership: CameraOwnershipSnapshot,
)

/** CLOSE_INVOKED is a local pre-call onceclaim, not native entry, return, closure or output safety. */
internal enum class CameraRunStage {
    OPEN_REQUESTED, OPENED, CONFIGURATION_REQUESTED, CONFIGURED, REPEATING_SUBMITTED,
    STOP_REQUESTED, DEVICE_CLOSED, SESSION_CLOSED, CONFIGURATION_FAILED,
    SESSION_CLOSE_RETURNED, DEVICE_CLOSE_RETURNED, OUTPUTS_SAFE, QUIT_REQUESTED,
    SESSION_CLOSE_INVOKED, DEVICE_CLOSE_INVOKED,
    OWNER_LOOP_RETURNED, OWNER_THREAD_CLOSED, STALE_CALLBACK,
    OUTCOME_INDETERMINATE, REJECTED_BEFORE_ACCEPTANCE, LATE_CONFIGURATION_ARRIVED,
    DEVICE_DISCONNECTED, DEVICE_ERROR,
}

internal data class CameraRunObservation(
    val stage: CameraRunStage,
    val ownership: CameraOwnershipSnapshot,
    val receiptMonotonicNanos: Long,
    val actualCameraId: String? = null,
    val repeatingSequenceId: Int? = null,
    val quitAccepted: Boolean? = null,
    val ownerThreadAlive: Boolean? = null,
)

internal data class CameraCaptureObservation(
    val frameNumber: Long,
    val sensorTimestampNanos: Long?,
    val receiptMonotonicNanos: Long,
) {
    val timestampClockDomain = "CAMERA2_SENSOR_TIMESTAMP_SOURCE_UNOBSERVED"
}

internal data class CameraRunFailure(
    val operation: String,
    val error: Exception?,
    val cameraErrorCode: Int? = null,
    val captureFrameNumber: Long? = null,
    val captureFailureReason: Int? = null,
)

internal interface CameraRunEvents {
    fun observed(original: OwnedCameraRun, observation: CameraRunObservation) = Unit
    fun capture(original: OwnedCameraRun, observation: CameraCaptureObservation) = Unit
    fun failed(original: OwnedCameraRun, failure: CameraRunFailure) = Unit
    fun outputsSafe(original: OwnedCameraRun, fact: CameraOutputsSafe) = Unit
}

/** Receiving this return value means local stop submission only, never Camera2 quiescence. */
internal data class CameraStopSubmission(
    val original: CameraRunIdentity,
    val firstSubmission: Boolean,
    val posted: Boolean,
    val ownershipAtReturn: CameraOwnershipSnapshot,
)

internal interface OwnedCameraRun {
    val identity: CameraRunIdentity
    val request: CameraUseDescription
    val snapshot: CameraOwnershipSnapshot
    val ownerThreadClosedObserved: Boolean
    val lastFailure: CameraRunFailure?
    fun stop(reason: String): CameraStopSubmission
}
