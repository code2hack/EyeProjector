package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraOutputsSafe
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraRunIdentity
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.ForwardCameraStream
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.RequestedCameraOutput
import java.util.Collections

/** Caller requests only. This does not advertise codec/camera support or an observed format. */
internal data class SurfaceEncoderRequest(
    val stream: ForwardCameraStream,
    val output: RequestedCameraOutput,
    val bitRate: Int,
    val frameRate: Int,
    val iFrameIntervalSeconds: Int,
)

/** One original encoder, independent of reusable source/stream generations. */
internal class SurfaceEncoderIdentity internal constructor()

/** Neither a codec-owned buffer nor the caller's writable byte array escapes this snapshot. */
internal class EncodedByteSnapshot(bytes: ByteArray) {
    private val snapshot = bytes.copyOf()
    val size: Int get() = snapshot.size
    fun copyBytes(): ByteArray = snapshot.copyOf()
}

internal fun <K, V> encoderMapSnapshot(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

internal fun <T> encoderListSnapshot(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Detached values only: no mutable MediaFormat or ByteBuffer is retained. */
internal class EncoderFormatObservation(
    val encoder: SurfaceEncoderIdentity,
    val formatGeneration: Long,
    val actualCodecName: String,
    val observedWidth: Int?,
    val observedHeight: Int?,
    csd: Map<String, EncodedByteSnapshot>,
    val receiptMonotonicNs: Long,
    val formatDescription: String? = null,
) {
    val csd: Map<String, EncodedByteSnapshot> = encoderMapSnapshot(csd)
}

/**
 * An actual dequeued output occurrence. Raw flags preserve config/keyframe/zero-byte EOS facts.
 * A null format generation means no output format has yet been observed for this occurrence.
 */
internal data class EncoderAccessUnit(
    val encoder: SurfaceEncoderIdentity,
    val stream: ForwardCameraStream,
    val formatGeneration: Long?,
    val sequence: Long,
    val presentationTimeUs: Long,
    val codecFlags: Int,
    val bytes: EncodedByteSnapshot,
    val outputReceiptMonotonicNs: Long,
) {
    val timestampClockDomain = "ENCODER_PRESENTATION_TIME_US_SOURCE_UNVERIFIED"
}

/** A local pre-call claim is not native entry, native return or resource quiescence. */
internal enum class EncoderStepDisposition { NOT_ATTEMPTED, CLAIMED, RETURNED, FAILED }

internal data class EncoderStepResult(
    val disposition: EncoderStepDisposition = EncoderStepDisposition.NOT_ATTEMPTED,
    val error: Throwable? = null,
)

internal enum class EncoderOperation {
    CREATE_CODEC, CONFIGURE, CREATE_INPUT_SURFACE, START,
    PRODUCER_STOP, SIGNAL_EOS, CODEC_STOP, CODEC_RELEASE, INPUT_SURFACE_RELEASE, WORKER_QUIT,
}

internal enum class EncoderProducerFence {
    /** An admission-closed never-bound encoder may clean its own acquired resources. */
    NO_PRODUCER_BOUND,
    PENDING,
    OUTPUTS_SAFE_RECEIVED,
}

internal data class EncoderFailure(
    val operation: String,
    val error: Throwable,
    val outputSequence: Long? = null,
    val outputBufferIndex: Int? = null,
)

/** Buffer indices are reusable; the original output sequence identifies this occurrence. */
internal data class EncoderOutputBufferKey(val sequence: Long, val bufferIndex: Int)

internal data class EncoderBufferReleaseObservation(
    val buffer: EncoderOutputBufferKey,
    val release: EncoderStepResult,
)

/**
 * Immutable accounting snapshot. Throwable references preserve original failures and suppressed
 * cleanup faults; no Surface, codec, callback, executor or mutable native buffer is exported.
 */
internal class EncoderLifetimeSnapshot(
    val encoder: SurfaceEncoderIdentity,
    val preparationClaimed: Boolean,
    val preparationCompleted: Boolean,
    val prepared: Boolean,
    val admissionClosed: Boolean,
    val retirementRequested: Boolean,
    val stopReason: String?,
    val originalProducer: CameraRunIdentity?,
    val producerFence: EncoderProducerFence,
    val producerOutputsSafe: CameraOutputsSafe?,
    val codecAcquired: Boolean,
    val inputSurfaceAcquired: Boolean,
    val codecStarted: Boolean,
    val eosObserved: Boolean,
    val drainLoopStarted: Boolean,
    val drainLoopReturned: Boolean,
    val finalizationClaimed: Boolean,
    val workerStarted: Boolean,
    val ownerLoopReturned: Boolean,
    val ownerAliveAtLoopReturn: Boolean?,
    val joinOutcomeRecorded: Boolean,
    val workerJoin: EncoderStepResult,
    val workerAliveAtJoin: Boolean?,
    val quitAccepted: Boolean?,
    val outputBuffersObserved: Long,
    val outputBufferReleaseClaims: Long,
    val outputBufferReleaseReturns: Long,
    val outputBufferReleaseFailures: Long,
    val lastOutputBufferRelease: EncoderBufferReleaseObservation?,
    outstandingOutputBuffers: Collection<EncoderBufferReleaseObservation>,
    steps: Map<EncoderOperation, EncoderStepResult>,
    failures: Collection<EncoderFailure>,
) {
    val outstandingOutputBuffers = encoderListSnapshot(outstandingOutputBuffers)
    val steps = encoderMapSnapshot(steps)
    val failures = encoderListSnapshot(failures)
    val primaryFailure: EncoderFailure? get() = failures.firstOrNull()
    val workerThreadClosedObserved: Boolean get() = joinOutcomeRecorded &&
        workerJoin.disposition == EncoderStepDisposition.RETURNED && workerAliveAtJoin == false
}

/** Readiness follows configure/createInputSurface/start returns, before any required output. */
internal data class EncoderPreparedObservation(
    val encoder: SurfaceEncoderIdentity,
    val ready: Boolean,
    val ownership: EncoderLifetimeSnapshot,
    val receiptMonotonicNs: Long,
)

/** Return records local retirement/submission only, never a producer or codec fence. */
internal data class EncoderStopSubmission(
    val encoder: SurfaceEncoderIdentity,
    val firstSubmission: Boolean,
    val posted: Boolean,
    val ownershipAtReturn: EncoderLifetimeSnapshot,
)

/**
 * Settled attempts, not blanket successful release. A failed quit/join remains qualified by the
 * snapshot. Encoder PTS, Camera2 output safety, EOS and actual joined thread stay independent.
 */
internal data class EncoderReleaseResult(
    val encoder: SurfaceEncoderIdentity,
    val ownership: EncoderLifetimeSnapshot,
) {
    val producerOutputsSafe get() = ownership.producerOutputsSafe
    val steps get() = ownership.steps
    val failures get() = ownership.failures
    val workerJoin get() = ownership.workerJoin
    val workerAlive get() = ownership.workerAliveAtJoin
}

internal interface SurfaceEncoderEvents {
    fun prepared(original: CameraSurfaceEncoder, observation: EncoderPreparedObservation) = Unit
    fun format(original: CameraSurfaceEncoder, observation: EncoderFormatObservation) = Unit
    fun packet(original: CameraSurfaceEncoder, packet: EncoderAccessUnit) = Unit
    fun failed(original: CameraSurfaceEncoder, failure: EncoderFailure) = Unit
    fun observed(original: CameraSurfaceEncoder, snapshot: EncoderLifetimeSnapshot) = Unit
}
