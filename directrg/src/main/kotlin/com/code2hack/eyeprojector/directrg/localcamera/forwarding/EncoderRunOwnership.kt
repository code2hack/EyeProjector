package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraOutputsSafe
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraRunIdentity

/**
 * Thread-safe original-resource accounting for the real adapter. This core invokes no callback,
 * native operation, executor, clock or wait. The adapter owns a still-live finalizer lane after
 * a drain task returns/fails, and records actual native returns and joined-thread observations.
 */
internal class EncoderRunOwnership(val identity: SurfaceEncoderIdentity) {
    private val lock = Any()
    private var preparationClaimed = false
    private var preparationCompleted = false
    private var prepared = false
    private var admissionClosed = false
    private var retirementRequested = false
    private var stopReason: String? = null
    private var originalProducer: CameraRunIdentity? = null
    private var producerStop: (() -> Unit)? = null
    private var producerOutputsSafe: CameraOutputsSafe? = null
    private var codecAcquired = false
    private var inputSurfaceAcquired = false
    private var codecStarted = false
    private var eosObserved = false
    private var drainLoopStarted = false
    private var drainLoopReturned = false
    private var finalizationClaimed = false
    private var workerStarted = false
    private var ownerLoopReturned = false
    private var ownerAliveAtLoopReturn: Boolean? = null
    private var joinClaimed = false
    private var joinOutcomeRecorded = false
    private var workerJoin = EncoderStepResult()
    private var workerAliveAtJoin: Boolean? = null
    private var quitAccepted: Boolean? = null
    private val steps = EncoderOperation.entries.associateWith { EncoderStepResult() }.toMutableMap()
    private val failures = ArrayList<EncoderFailure>()
    private var lastOutputSequence = -1L
    private var outputBuffersObserved = 0L
    private var outputBufferReleaseClaims = 0L
    private var outputBufferReleaseReturns = 0L
    private var outputBufferReleaseFailures = 0L
    private var lastOutputBufferRelease: EncoderBufferReleaseObservation? = null
    // Successful occurrences are retired; the sequence watermark prevents their re-adoption.
    private val outstandingBuffers = LinkedHashMap<EncoderOutputBufferKey, EncoderStepResult>()

    fun snapshot(): EncoderLifetimeSnapshot = synchronized(lock) { snapshotLocked() }

    private fun producerFenceLocked() = when {
        originalProducer == null -> EncoderProducerFence.NO_PRODUCER_BOUND
        producerOutputsSafe != null -> EncoderProducerFence.OUTPUTS_SAFE_RECEIVED
        else -> EncoderProducerFence.PENDING
    }

    private fun snapshotLocked() = EncoderLifetimeSnapshot(identity, preparationClaimed,
        preparationCompleted, prepared, admissionClosed, retirementRequested, stopReason,
        originalProducer, producerFenceLocked(), producerOutputsSafe, codecAcquired,
        inputSurfaceAcquired, codecStarted, eosObserved, drainLoopStarted, drainLoopReturned,
        finalizationClaimed, workerStarted, ownerLoopReturned, ownerAliveAtLoopReturn,
        joinOutcomeRecorded, workerJoin, workerAliveAtJoin, quitAccepted, outputBuffersObserved,
        outputBufferReleaseClaims, outputBufferReleaseReturns, outputBufferReleaseFailures,
        lastOutputBufferRelease, outstandingBuffers.map { EncoderBufferReleaseObservation(it.key, it.value) },
        steps, failures)

    fun claimPreparation(): Boolean = synchronized(lock) {
        if (admissionClosed || preparationClaimed) false else { preparationClaimed = true; true }
    }

    /** The caller publishes this readiness only after all three actual endpoint setup returns. */
    fun preparationReturned(): Boolean = synchronized(lock) {
        if (!preparationClaimed || preparationCompleted) false else {
            preparationCompleted = true
            prepared = !admissionClosed && steps[EncoderOperation.CONFIGURE]?.disposition == EncoderStepDisposition.RETURNED &&
                inputSurfaceAcquired && codecStarted
            true
        }
    }

    fun preparationFailed(error: Throwable): Boolean = synchronized(lock) {
        if (!preparationClaimed || preparationCompleted) false else {
            preparationCompleted = true
            prepared = false
            failureLocked(EncoderFailure("prepare", error))
            retireLocked("prepare-failed")
            true
        }
    }

    /** Claim before an adapter observer can reenter stop; the callback itself is never invoked. */
    fun bindProducer(original: CameraRunIdentity, requestStop: () -> Unit): Boolean = synchronized(lock) {
        if (admissionClosed || !prepared || originalProducer != null) false else {
            originalProducer = original
            producerStop = requestStop
            true
        }
    }

    private fun retireLocked(reason: String): Boolean {
        admissionClosed = true
        if (retirementRequested) return false
        retirementRequested = true
        stopReason = reason
        return true
    }

    /** Atomically closes never-bound admission; no later caller may attach a producer. */
    fun requestStop(reason: String): Boolean = synchronized(lock) { retireLocked(reason) }

    /** The returned action is a once claim. Invoke it outside this lock and record its outcome. */
    fun claimProducerStop(): (() -> Unit)? = synchronized(lock) {
        if (!retirementRequested || producerOutputsSafe != null || originalProducer == null ||
            steps[EncoderOperation.PRODUCER_STOP]?.disposition != EncoderStepDisposition.NOT_ATTEMPTED) null
        else {
            steps[EncoderOperation.PRODUCER_STOP] = EncoderStepResult(EncoderStepDisposition.CLAIMED)
            producerStop
        }
    }

    /** A stale original or mismatched fact never resolves this encoder's original input use. */
    fun producerStopped(original: CameraRunIdentity, fact: CameraOutputsSafe): Boolean = synchronized(lock) {
        if (originalProducer !== original || fact.original !== original ||
            !fact.ownership.outputsSafe || producerOutputsSafe != null) false
        else {
            producerOutputsSafe = fact
            retireLocked("producer-outputs-safe")
            true
        }
    }

    val producerFenceResolved: Boolean get() = synchronized(lock) {
        admissionClosed && (originalProducer == null || producerOutputsSafe != null)
    }

    fun claimStep(operation: EncoderOperation): Boolean = synchronized(lock) {
        if (steps[operation]?.disposition != EncoderStepDisposition.NOT_ATTEMPTED) return@synchronized false
        val allowed = when (operation) {
            EncoderOperation.CREATE_CODEC -> preparationClaimed && !admissionClosed
            EncoderOperation.CONFIGURE -> codecAcquired && !admissionClosed
            EncoderOperation.CREATE_INPUT_SURFACE -> codecAcquired && !admissionClosed &&
                steps[EncoderOperation.CONFIGURE]?.disposition == EncoderStepDisposition.RETURNED
            EncoderOperation.START -> codecAcquired && inputSurfaceAcquired && !admissionClosed
            EncoderOperation.PRODUCER_STOP -> false // Its original action comes from claimProducerStop().
            EncoderOperation.SIGNAL_EOS -> !finalizationClaimed && retirementRequested && producerOutputsSafe != null && codecStarted
            EncoderOperation.CODEC_STOP -> finalizationClaimed && codecAcquired &&
                steps[EncoderOperation.START]?.disposition != EncoderStepDisposition.NOT_ATTEMPTED
            EncoderOperation.CODEC_RELEASE -> finalizationClaimed && codecAcquired
            EncoderOperation.INPUT_SURFACE_RELEASE -> finalizationClaimed && inputSurfaceAcquired
            EncoderOperation.WORKER_QUIT -> finalizationClaimed && workerStarted
        }
        if (!allowed) false else {
            steps[operation] = EncoderStepResult(EncoderStepDisposition.CLAIMED)
            true
        }
    }

    fun stepReturned(operation: EncoderOperation): Boolean = synchronized(lock) {
        if (steps[operation]?.disposition != EncoderStepDisposition.CLAIMED) false else {
            steps[operation] = EncoderStepResult(EncoderStepDisposition.RETURNED)
            when (operation) {
                EncoderOperation.CREATE_CODEC -> codecAcquired = true
                EncoderOperation.CREATE_INPUT_SURFACE -> inputSurfaceAcquired = true
                EncoderOperation.START -> codecStarted = true
                else -> Unit
            }
            true
        }
    }

    fun stepFailed(operation: EncoderOperation, error: Throwable): Boolean = synchronized(lock) {
        if (steps[operation]?.disposition != EncoderStepDisposition.CLAIMED) false else {
            steps[operation] = EncoderStepResult(EncoderStepDisposition.FAILED, error)
            failureLocked(EncoderFailure(operation.name.lowercase(), error))
            when (operation) {
                EncoderOperation.CREATE_CODEC, EncoderOperation.CONFIGURE,
                EncoderOperation.CREATE_INPUT_SURFACE, EncoderOperation.START -> retireLocked("prepare-failed")
                else -> Unit
            }
            true
        }
    }

    fun recordFailure(failure: EncoderFailure) = synchronized(lock) { failureLocked(failure) }

    private fun failureLocked(failure: EncoderFailure) {
        if (failures.any { it.operation == failure.operation && it.error === failure.error &&
                it.outputSequence == failure.outputSequence && it.outputBufferIndex == failure.outputBufferIndex }) return
        val primary = failures.firstOrNull()?.error
        if (primary != null && primary !== failure.error && primary.suppressed.none { it === failure.error })
            primary.addSuppressed(failure.error)
        failures.add(failure)
    }

    /** Sequence is a local actual-output occurrence, never a sensor frame number or PTS. */
    fun outputBufferAcquired(sequence: Long, bufferIndex: Int): Boolean = synchronized(lock) {
        if (sequence <= lastOutputSequence) false else {
            lastOutputSequence = sequence
            outputBuffersObserved++
            outstandingBuffers[EncoderOutputBufferKey(sequence, bufferIndex)] = EncoderStepResult()
            true
        }
    }

    fun claimOutputBufferRelease(buffer: EncoderOutputBufferKey): Boolean = synchronized(lock) {
        if (outstandingBuffers[buffer]?.disposition != EncoderStepDisposition.NOT_ATTEMPTED) false else {
            outstandingBuffers[buffer] = EncoderStepResult(EncoderStepDisposition.CLAIMED)
            outputBufferReleaseClaims++
            true
        }
    }

    fun outputBufferReleaseReturned(buffer: EncoderOutputBufferKey): Boolean = synchronized(lock) {
        if (outstandingBuffers[buffer]?.disposition != EncoderStepDisposition.CLAIMED) false else {
            val result = EncoderStepResult(EncoderStepDisposition.RETURNED)
            lastOutputBufferRelease = EncoderBufferReleaseObservation(buffer, result)
            outstandingBuffers.remove(buffer)
            outputBufferReleaseReturns++
            true
        }
    }

    fun outputBufferReleaseFailed(buffer: EncoderOutputBufferKey, error: Throwable): Boolean = synchronized(lock) {
        if (outstandingBuffers[buffer]?.disposition != EncoderStepDisposition.CLAIMED) false else {
            val result = EncoderStepResult(EncoderStepDisposition.FAILED, error)
            outstandingBuffers[buffer] = result
            lastOutputBufferRelease = EncoderBufferReleaseObservation(buffer, result)
            outputBufferReleaseFailures++
            failureLocked(EncoderFailure("output-buffer-release", error, buffer.sequence, buffer.bufferIndex))
            true
        }
    }

    fun drainLoopStarted(): Boolean = synchronized(lock) {
        if (drainLoopStarted || finalizationClaimed || !codecStarted) false
        else { drainLoopStarted = true; true }
    }

    /** A drain-task return does not end its Handler/codec finalizer lane. */
    fun drainLoopReturned(failure: EncoderFailure? = null): Boolean = synchronized(lock) {
        if (!drainLoopStarted || drainLoopReturned) false else {
            drainLoopReturned = true
            if (failure != null) { failureLocked(failure); retireLocked("drain-failed") }
            true
        }
    }

    fun eosObserved(): Boolean = synchronized(lock) {
        if (eosObserved) false else { eosObserved = true; retireLocked("output-eos"); true }
    }

    /** Independent cleanup may proceed after a failed drain, without claiming observed EOS. */
    fun claimFinalization(): Boolean = synchronized(lock) {
        val fenced = admissionClosed && (originalProducer == null || producerOutputsSafe != null)
        val pendingCall = steps.any { it.key != EncoderOperation.PRODUCER_STOP &&
            it.value.disposition == EncoderStepDisposition.CLAIMED }
        val pendingOutput = outstandingBuffers.values.any {
            it.disposition == EncoderStepDisposition.NOT_ATTEMPTED || it.disposition == EncoderStepDisposition.CLAIMED
        }
        if (!retirementRequested || !fenced || finalizationClaimed || pendingCall || pendingOutput ||
            drainLoopStarted && !drainLoopReturned) false
        else { finalizationClaimed = true; true }
    }

    fun workerStarted(): Boolean = synchronized(lock) {
        if (workerStarted) false else { workerStarted = true; true }
    }

    fun ownerLoopReturned(alive: Boolean): Boolean = synchronized(lock) {
        if (ownerLoopReturned) false else { ownerLoopReturned = true; ownerAliveAtLoopReturn = alive; true }
    }

    fun quitReturned(accepted: Boolean) = synchronized(lock) { quitAccepted = accepted }

    /** Claims an internal join observer, not the caller's potentially inline executor. */
    fun claimWorkerJoin(): Boolean = synchronized(lock) {
        if (!workerStarted || joinClaimed) false else { joinClaimed = true; true }
    }

    fun workerJoinReturned(alive: Boolean): Boolean = synchronized(lock) {
        recordJoinLocked(EncoderStepResult(EncoderStepDisposition.RETURNED), alive)
    }

    fun workerJoinFailed(error: Throwable, alive: Boolean?): Boolean = synchronized(lock) {
        failureLocked(EncoderFailure("worker-join", error))
        recordJoinLocked(EncoderStepResult(EncoderStepDisposition.FAILED, error), alive)
    }

    fun workerJoinNotAttempted(alive: Boolean?, error: Throwable? = null): Boolean = synchronized(lock) {
        if (error != null) failureLocked(EncoderFailure("worker-join-not-attempted", error))
        recordJoinLocked(EncoderStepResult(EncoderStepDisposition.NOT_ATTEMPTED, error), alive)
    }

    private fun recordJoinLocked(result: EncoderStepResult, alive: Boolean?): Boolean {
        if (joinOutcomeRecorded) return false
        joinOutcomeRecorded = true
        workerJoin = result
        workerAliveAtJoin = alive
        return true
    }

    /** Null while any owned physical attempt, native call, original fence or join is unresolved. */
    fun releaseResult(): EncoderReleaseResult? = synchronized(lock) {
        if (!finalizationClaimed || !joinOutcomeRecorded || steps.values.any {
                it.disposition == EncoderStepDisposition.CLAIMED
            } || outstandingBuffers.values.any {
                it.disposition == EncoderStepDisposition.NOT_ATTEMPTED || it.disposition == EncoderStepDisposition.CLAIMED
            }) return@synchronized null
        fun settled(operation: EncoderOperation) = steps[operation]?.disposition.let {
            it == EncoderStepDisposition.RETURNED || it == EncoderStepDisposition.FAILED
        }
        val stopNeeded = codecAcquired && steps[EncoderOperation.START]?.disposition != EncoderStepDisposition.NOT_ATTEMPTED
        if (codecAcquired && !settled(EncoderOperation.CODEC_RELEASE) ||
            stopNeeded && !settled(EncoderOperation.CODEC_STOP) ||
            inputSurfaceAcquired && !settled(EncoderOperation.INPUT_SURFACE_RELEASE) ||
            workerStarted && !settled(EncoderOperation.WORKER_QUIT)) null
        else EncoderReleaseResult(identity, snapshotLocked())
    }
}
