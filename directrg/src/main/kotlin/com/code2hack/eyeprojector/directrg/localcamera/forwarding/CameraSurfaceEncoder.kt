package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraOutputsSafe
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.CameraRunIdentity
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.ProducerBoundForwardCameraInputLease
import java.util.concurrent.Executor

/**
 * Actual surface-input AVC adapter. Construction creates only the original ownership record.
 * The private codec worker is also its control/finalizer lane: a failed drain task does not
 * discard the lane while the original Camera2 producer may still use the input Surface.
 * No caller, UI/GL lane, Camera2 callback or caller-supplied Executor performs a join.
 */
internal class CameraSurfaceEncoder(
    val request: SurfaceEncoderRequest,
    private val events: SurfaceEncoderEvents = object : SurfaceEncoderEvents {},
) : ProducerBoundForwardCameraInputLease {
    val identity = SurfaceEncoderIdentity()
    private val ownership = EncoderRunOwnership(identity)
    private val wake = Object()
    private val subscriptions = Any()
    private data class PreparedSubscriber(val executor: Executor, val callback: (EncoderPreparedObservation) -> Unit)
    private data class ClosedSubscriber(val executor: Executor, val callback: (EncoderReleaseResult) -> Unit)
    private val preparedSubscribers = ArrayList<PreparedSubscriber>()
    private val closedSubscribers = ArrayList<ClosedSubscriber>()
    private var preparedEventSent = false
    @Volatile private var ownerThread: Thread? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    private var outputSequence = 0L
    private var formatGeneration: Long? = null
    private var restoreInterrupt = false

    override val stream get() = request.stream
    override val requestedOutput get() = request.output
    override val surface: Surface get() {
        val state = ownership.snapshot()
        check(state.prepared && !state.admissionClosed) { "Encoder input is not available" }
        return inputSurface ?: error("Encoder input Surface not acquired")
    }
    val snapshot get() = ownership.snapshot()
    val releaseResult get() = ownership.releaseResult()

    /** Readiness requires actual configure/createInputSurface/start returns, never first output. */
    fun prepareAsync(completionExecutor: Executor, completion: (EncoderPreparedObservation) -> Unit) {
        synchronized(subscriptions) { preparedSubscribers.add(PreparedSubscriber(completionExecutor, completion)) }
        val claimed = ownership.claimPreparation()
        val state = ownership.snapshot()
        if (state.preparationCompleted || !claimed && !state.preparationClaimed) {
            notification { deliverPrepared() }
        } else ensureWorker()
        signal()
    }

    override fun bindProducer(original: CameraRunIdentity, requestStop: () -> Unit): Boolean {
        // Ownership claims before any later observer/stop callback; this never invokes one here.
        val bound = ownership.bindProducer(original, requestStop)
        signal()
        return bound
    }

    override fun producerStopped(original: CameraRunIdentity, fact: CameraOutputsSafe) {
        // Both references are validated by the original ownership record, not by source tuples.
        if (ownership.producerStopped(original, fact)) signal()
    }

    /** Submission/retirement only. Native work and foreign stop callbacks stay on the worker. */
    fun stop(reason: String): EncoderStopSubmission {
        val first = ownership.requestStop(reason)
        val started = ensureWorker()
        signal()
        return EncoderStopSubmission(identity, first, started, ownership.snapshot())
    }

    fun closeAsync(completionExecutor: Executor, completion: (EncoderReleaseResult) -> Unit) {
        synchronized(subscriptions) { closedSubscribers.add(ClosedSubscriber(completionExecutor, completion)) }
        stop("close")
        // A late subscriber must also be dispatched off its caller, even for a direct Executor.
        if (ownership.releaseResult() != null) notification { deliverClosed() }
    }

    private fun signal() = synchronized(wake) { wake.notifyAll() }

    private fun ensureWorker(): Boolean {
        val next = synchronized(wake) {
            if (ownerThread != null) null else Thread(::runOwner,
                "eyeprojector-encoder-" + System.identityHashCode(identity)).also {
                it.isDaemon = true
                ownerThread = it
            }
        }
        if (next != null) {
            try { next.start() }
            catch (error: Throwable) {
                // No foreign observer/Executor runs on this public submission caller.
                ownership.recordFailure(EncoderFailure("worker-start", error))
                ownership.requestStop("worker-start")
                // No native worker entry is inferred from a failed Thread.start invocation.
                if (next.state == Thread.State.NEW && !ownership.snapshot().workerStarted) {
                    ownership.preparationFailed(error)
                    ownership.claimFinalization()
                    ownership.workerJoinNotAttempted(false, error)
                    notification { deliverPrepared(); deliverClosed() }
                }
                return false
            }
        }
        return next != null || ownerThread?.isAlive == true
    }

    private fun runOwner() {
        ownership.workerStarted() // actual run entry, independent of the caller's start return
        startJoinObserver(Thread.currentThread())
        try { runControlLoop() }
        finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
            // The control-loop method has actually left; Thread.run has not ended yet.
            ownership.ownerLoopReturned(Thread.currentThread().isAlive)
            observe()
            // If joining failed or could not be submitted, report a qualified result only
            // after native attempts settle. Never relabel it as a successful join.
            val state = ownership.snapshot()
            if (state.joinOutcomeRecorded && state.workerJoin.disposition != EncoderStepDisposition.RETURNED)
                notification { deliverClosed() }
        }
    }

    private fun runControlLoop() {
        try {
            if (ownership.snapshot().preparationClaimed) prepareNative()
            deliverPrepared()
            if (ownership.snapshot().codecStarted) drainTask()
            awaitOriginalFence()
            // A failed drain still has this independent control/finalizer path.
            signalEOS()
            finalizeNative()
        } catch (error: Throwable) {
            failure(EncoderFailure("owner-task", error), retire = true)
            val state = ownership.snapshot()
            if (state.drainLoopStarted && !state.drainLoopReturned) ownership.drainLoopReturned()
            // Preserve actual duties after a task fault. No timeout, invented fence or retry of
            // a once-claimed native cleanup operation is used to make this lane look closed.
            awaitOriginalFence()
            signalEOS()
            finalizeNative()
        }
    }

    private fun prepareNative() {
        try {
            if (ownership.snapshot().retirementRequested) {
                ownership.preparationFailed(IllegalStateException("Preparation retired before native work"))
                return
            }
            if (!ownership.claimStep(EncoderOperation.CREATE_CODEC)) return
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                ownership.stepReturned(EncoderOperation.CREATE_CODEC)
            } catch (error: Throwable) { stepFailure(EncoderOperation.CREATE_CODEC, error); throw error }
            val original = codec ?: error("Encoder was not acquired")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                request.output.width, request.output.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, request.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, request.iFrameIntervalSeconds)
            }
            if (!ownership.claimStep(EncoderOperation.CONFIGURE)) return
            try { original.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ownership.stepReturned(EncoderOperation.CONFIGURE)
            } catch (error: Throwable) { stepFailure(EncoderOperation.CONFIGURE, error); throw error }
            if (!ownership.claimStep(EncoderOperation.CREATE_INPUT_SURFACE)) return
            try { inputSurface = original.createInputSurface()
                ownership.stepReturned(EncoderOperation.CREATE_INPUT_SURFACE)
            } catch (error: Throwable) { stepFailure(EncoderOperation.CREATE_INPUT_SURFACE, error); throw error }
            if (!ownership.claimStep(EncoderOperation.START)) return
            try { original.start(); ownership.stepReturned(EncoderOperation.START) }
            catch (error: Throwable) { stepFailure(EncoderOperation.START, error); throw error }
            ownership.preparationReturned()
        } catch (error: Throwable) {
            ownership.preparationFailed(error)
            failure(EncoderFailure("prepare", error), retire = true)
        } finally {
            // A concurrent stop may cancel the next setup claim after an earlier actual return.
            if (!ownership.snapshot().preparationCompleted)
                ownership.preparationFailed(IllegalStateException("Preparation retired during native setup"))
            observe()
        }
    }

    private fun drainTask() {
        if (!ownership.drainLoopStarted()) return
        var problem: EncoderFailure? = null
        try {
            // Continue consuming while the stopped producer remains unsafe, so normal codec
            // backpressure does not prevent Camera2 from reaching its original close fence.
            while (true) {
                driveProducerStop()
                if (ownership.snapshot().retirementRequested && ownership.producerFenceResolved) break
                drainOne(20_000)
            }
            signalEOS()
            // Observe output available now; absence is NOT EOS/drain success. Shutdown never
            // waits for a new first frame, a native EOS deadline or a fabricated completion.
            while (!ownership.snapshot().eosObserved && drainOne(0)) Unit
        } catch (error: Throwable) {
            problem = EncoderFailure("drain", error)
            failure(problem, retire = true)
        } finally {
            ownership.drainLoopReturned(problem)
            observe()
        }
    }

    /** True for an actual output/format occurrence, false for current TRY_AGAIN availability. */
    private fun drainOne(waitUs: Long): Boolean {
        val original = codec ?: error("Missing original encoder")
        val info = MediaCodec.BufferInfo()
        val index = original.dequeueOutputBuffer(info, waitUs)
        when (index) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return true
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val actual = original.outputFormat
                val csd = LinkedHashMap<String, EncodedByteSnapshot>()
                for (key in actual.keys.sorted().filter { it.startsWith("csd-") }) {
                    actual.getByteBuffer(key)?.let { buffer ->
                        val view = buffer.duplicate()
                        val bytes = ByteArray(view.remaining()); view.get(bytes)
                        csd[key] = EncodedByteSnapshot(bytes)
                    }
                }
                formatGeneration = (formatGeneration ?: 0L) + 1
                val fact = EncoderFormatObservation(identity, formatGeneration!!, original.name,
                    if (actual.containsKey(MediaFormat.KEY_WIDTH)) actual.getInteger(MediaFormat.KEY_WIDTH) else null,
                    if (actual.containsKey(MediaFormat.KEY_HEIGHT)) actual.getInteger(MediaFormat.KEY_HEIGHT) else null,
                    csd, System.nanoTime(), actual.toString())
                event("format") { events.format(this, fact) }
                return true
            }
        }
        check(index >= 0) { "Unexpected encoder output result" }
        val sequence = ++outputSequence
        val key = EncoderOutputBufferKey(sequence, index)
        ownership.outputBufferAcquired(sequence, index)
        var packet: EncoderAccessUnit? = null
        var primary: Throwable? = null
        try {
            val bytes = ByteArray(info.size)
            if (info.size != 0) {
                val view = (original.getOutputBuffer(index) ?: error("Missing encoder output buffer")).duplicate()
                view.limit(info.offset + info.size); view.position(info.offset); view.get(bytes)
            }
            packet = EncoderAccessUnit(identity, stream, formatGeneration, sequence,
                info.presentationTimeUs, info.flags, EncodedByteSnapshot(bytes), System.nanoTime())
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ownership.eosObserved()
        } catch (error: Throwable) {
            primary = error
            failure(EncoderFailure("output-copy", error, sequence, index), retire = true)
            throw error
        } finally {
            if (ownership.claimOutputBufferRelease(key)) {
                try { original.releaseOutputBuffer(index, false); ownership.outputBufferReleaseReturned(key) }
                catch (error: Throwable) {
                    ownership.outputBufferReleaseFailed(key, error)
                    failure(EncoderFailure("output-buffer-release", error, sequence, index), retire = true)
                    if (primary == null) throw error
                }
            }
        }
        // No codec-owned buffer survives into user code, including a throwing/reentrant observer.
        packet?.let { fact -> event("packet") { events.packet(this, fact) } }
        return true
    }

    private fun driveProducerStop() {
        val action = ownership.claimProducerStop() ?: return
        try { action(); ownership.stepReturned(EncoderOperation.PRODUCER_STOP) }
        catch (error: Throwable) { stepFailure(EncoderOperation.PRODUCER_STOP, error) }
        observe()
    }

    private fun awaitOriginalFence() {
        while (!ownership.producerFenceResolved) {
            driveProducerStop()
            var interrupted: InterruptedException? = null
            synchronized(wake) {
                if (!ownership.producerFenceResolved) {
                    try { wake.wait() }
                    catch (error: InterruptedException) {
                        restoreInterrupt = true
                        interrupted = error
                    }
                }
            }
            interrupted?.let { failure(EncoderFailure("owner-wait-interrupted", it), retire = true) }
        }
    }

    private fun signalEOS() {
        if (ownership.snapshot().eosObserved || !ownership.claimStep(EncoderOperation.SIGNAL_EOS)) return
        try { codec!!.signalEndOfInputStream(); ownership.stepReturned(EncoderOperation.SIGNAL_EOS) }
        catch (error: Throwable) { stepFailure(EncoderOperation.SIGNAL_EOS, error) }
        observe()
    }

    private fun finalizeNative() {
        driveProducerStop()
        if (!ownership.claimFinalization()) return
        fun step(operation: EncoderOperation, action: () -> Unit) {
            if (!ownership.claimStep(operation)) return
            try { action(); ownership.stepReturned(operation) }
            catch (error: Throwable) { stepFailure(operation, error) }
            observe()
        }
        step(EncoderOperation.CODEC_STOP) { codec!!.stop() }
        step(EncoderOperation.CODEC_RELEASE) { codec!!.release() }
        step(EncoderOperation.INPUT_SURFACE_RELEASE) { inputSurface!!.release() }
        // Plain Thread control-loop exit is a local accepted request, not an OS/thread fence.
        step(EncoderOperation.WORKER_QUIT) { ownership.quitReturned(true) }
    }

    private fun startJoinObserver(original: Thread) {
        if (!ownership.claimWorkerJoin()) return
        try {
            Thread({
                try { original.join(); ownership.workerJoinReturned(original.isAlive) }
                catch (error: Throwable) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    ownership.workerJoinFailed(error, original.isAlive)
                }
                observe()
                deliverClosed()
            }, "eyeprojector-encoder-join-" + System.identityHashCode(identity)).apply {
                isDaemon = true; start()
            }
        } catch (error: Throwable) {
            ownership.workerJoinNotAttempted(original.isAlive, error)
        }
    }

    private fun deliverPrepared() {
        val state = ownership.snapshot()
        if (!state.preparationCompleted && state.preparationClaimed) return
        val (listeners, publishEvent) = synchronized(subscriptions) {
            val claimed = !preparedEventSent && state.preparationCompleted
            if (claimed) preparedEventSent = true
            Pair(preparedSubscribers.toList().also { preparedSubscribers.clear() }, claimed)
        }
        val fact = EncoderPreparedObservation(identity, state.prepared && !state.admissionClosed,
            state, System.nanoTime())
        if (publishEvent) event("prepared") { events.prepared(this, fact) }
        for (listener in listeners) dispatch("prepared-completion", listener.executor, retireOnFailure = true) {
            val current = ownership.snapshot()
            listener.callback(EncoderPreparedObservation(identity, current.prepared && !current.admissionClosed,
                current, System.nanoTime()))
        }
    }

    private fun deliverClosed() {
        if (ownership.releaseResult() == null) return
        val listeners = synchronized(subscriptions) {
            closedSubscribers.toList().also { closedSubscribers.clear() }
        }
        for (listener in listeners) dispatch("close-completion", listener.executor, retireOnFailure = false) {
            ownership.releaseResult()?.let(listener.callback)
        }
    }

    private fun dispatch(operation: String, executor: Executor, retireOnFailure: Boolean, action: () -> Unit) {
        try { executor.execute { try { action() }
            catch (error: Throwable) { failure(EncoderFailure(operation + "-callback", error), retireOnFailure) } } }
        catch (error: Throwable) { failure(EncoderFailure(operation + "-dispatch", error), retireOnFailure) }
    }

    /** Late subscribers still dispatch away from UI/GL/camera/caller lanes with a direct Executor. */
    private fun notification(action: () -> Unit) {
        try { Thread(action, "eyeprojector-encoder-notify-" + System.identityHashCode(identity)).apply {
            isDaemon = true; start()
        } } catch (error: Throwable) { ownership.recordFailure(EncoderFailure("notification-start", error)) }
    }

    private fun event(operation: String, action: () -> Unit) {
        try { action() }
        catch (error: Throwable) { failure(EncoderFailure("observer-" + operation, error), retire = true) }
    }

    private fun observe() = event("state") { events.observed(this, ownership.snapshot()) }

    private fun stepFailure(operation: EncoderOperation, error: Throwable) {
        ownership.stepFailed(operation, error)
        failure(EncoderFailure(operation.name.lowercase(), error), retire = false)
    }

    private fun failure(fact: EncoderFailure, retire: Boolean) {
        ownership.recordFailure(fact)
        if (retire) ownership.requestStop(fact.operation)
        try { events.failed(this, fact) }
        catch (error: Throwable) { ownership.recordFailure(EncoderFailure("observer-failed", error)) }
        signal()
    }
}
