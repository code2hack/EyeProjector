package com.code2hack.eyeprojector.directrg.localcamera

import java.util.concurrent.Executor

/** A local method-return observation, never native resource or callback quiescence. */
internal enum class LocalReleaseDisposition { RETURNED, FAILED, NOT_ATTEMPTED }
internal enum class LocalProducerFence { NOT_BOUND_LEGACY, PENDING, OUTPUTS_SAFE_RECEIVED }
internal enum class LocalGlContextDisposition { ORIGINAL_CURRENT, CONFIRMED_CONTEXT_LOST, UNAVAILABLE, NOT_NEEDED }

internal data class LocalReleaseStep(
    val disposition: LocalReleaseDisposition,
    val error: Throwable? = null,
)
internal data class LocalReleaseFailure(val operation: String, val error: Throwable)

/** Independent, once-attempted steps on this original input. Failure does not imply release. */
internal data class LocalInputReleaseResult(
    val input: LocalCameraInput,
    val producerFence: LocalProducerFence,
    val glContext: LocalGlContextDisposition,
    val steps: Map<String, LocalReleaseStep>,
    val failures: List<LocalReleaseFailure>,
)

/** Output-thread join is distinct from input fences and the producer's own thread observations. */
internal data class LocalOutputReleaseResult(
    val outputIdentity: Any,
    val inputs: List<LocalInputReleaseResult>,
    val steps: Map<String, LocalReleaseStep>,
    val workerJoin: LocalReleaseStep,
    val workerAlive: Boolean?,
    val failures: List<LocalReleaseFailure>,
)

/** Bound to one original input/run; no generation/source tuple lookup. */
internal interface LocalProducerBinding {
    /** Only the bound run's actual output-use fence may signal this; idempotent. */
    fun outputsSafe()
}

/** Optional capability. Register before any Camera2 work or Surface borrowing. */
internal interface ProducerRetirementInput : LocalCameraInput {
    /** One borrow only. Null means this original input is retired or already bound. */
    fun bindProducer(requestStop: () -> Unit): LocalProducerBinding?
    val producerFence: LocalProducerFence
    /** Null while retirement or its physical finalization remains pending. */
    val releaseResult: LocalInputReleaseResult?
    /** Pending dispatch/cleanup faults are observable even before a release result exists. */
    val retirementFailures: List<LocalReleaseFailure> get() = releaseResult?.failures.orEmpty()
    /** Dispatch only: an executor may run inline or reject; it never owns the output join. */
    fun closeAsync(completionExecutor: Executor, onRelease: (LocalInputReleaseResult) -> Unit)
}

internal interface RetainedOutputRetirement {
    val releaseResult: LocalOutputReleaseResult?
    val retirementFailures: List<LocalReleaseFailure> get() = releaseResult?.failures.orEmpty()
    /**
     * Close return only submits retirement. Actual join runs on an internal off-UI/output path,
     * independently of this callback executor, which may run inline or reject dispatch.
     */
    fun closeAsync(completionExecutor: Executor, onRelease: (LocalOutputReleaseResult) -> Unit)
}
