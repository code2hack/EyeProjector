package com.code2hack.eyeprojector.directrg.localcamera.acquisition

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.LocalProducerBinding
import com.code2hack.eyeprojector.directrg.localcamera.ProducerRetirementInput
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual Camera2 adapter. Each call owns a separate original run/thread; a future use owner must
 * wait for the original output-use fence before reusing either borrowed target. This component
 * neither selects an alternate camera nor owns permission UI, encoder, transport or Phone capture.
 */
internal class GlassesCameraProducer(
    private val manager: CameraManager,
    private val outcomes: CameraCallOutcomeResolver = CameraCallOutcomeResolver { null },
) {
    fun start(request: CameraUseDescription, targets: CameraTargets, events: CameraRunEvents): OwnedCameraRun =
        Run(request, targets, events).also { it.begin() }

    private inner class Run(
        override val request: CameraUseDescription,
        private val targets: CameraTargets,
        private val events: CameraRunEvents,
    ) : OwnedCameraRun {
        override val identity = CameraRunIdentity()
        private val stopSubmitted = AtomicBoolean()
        @Volatile private var published = CameraOwnershipSnapshot()
        @Volatile override var ownerThreadClosedObserved = false
            private set
        @Volatile override var lastFailure: CameraRunFailure? = null
            private set
        override val snapshot get() = published
        private val bindingGate = Any()
        private var localBinding: LocalProducerBinding? = null
        private var actualLocalFence: CameraOutputsSafe? = null
        private var localFenceClaimed = false
        private var borrowed: Pair<Surface, Surface>? = null
        private val ownership = CameraRunOwnership<CameraDevice, CameraCaptureSession>(
            closeDevice = { it.close() }, closeSession = { it.close() },
            changed = { stage, state -> published = state; observe(stage) },
            failed = { operation, error -> failure(CameraRunFailure(operation, error)) },
            safe = { fence, state -> outputsSafe(fence, state) },
        )
        private val thread = object : HandlerThread("eyeprojector-camera-${System.identityHashCode(identity)}") {
            override fun run() {
                try { super.run() }
                finally {
                    // super.run() returned. This is NOT actual Thread termination yet.
                    observe(CameraRunStage.OWNER_LOOP_RETURNED, alive = isAlive)
                }
            }
        }.apply { start() }
        private val handler = Handler(thread.looper)

        fun begin() {
            observeThreadTermination()
            // Bind capable forwarding FIRST. Even a throwing bind may have claimed this
            // original; the existing genuine no-open fence still notifies that exact lease.
            val forwardBound = try {
                (targets.forwarding as? ProducerBoundForwardCameraInputLease)
                    ?.bindProducer(identity) { stop("forward-input-retired") } ?: true
            } catch (error: Throwable) {
                failure(CameraRunFailure("forward-producer-binding", error as? Exception
                    ?: RuntimeException("Forward producer registration failed", error)))
                false
            }
            if (!forwardBound) {
                failure(CameraRunFailure("forward-producer-binding-unavailable",
                    IllegalStateException("Forward input cannot bind this original producer")))
                stopSubmitted.set(true)
                if (!snapshot.outputsSafe && !handler.post { ownership.requestStop() }) ownership.requestStop()
                return
            }
            // Do not skip local binding after an early forward stop: installLocalBinding must
            // deliver the cached actual no-open fence even if the camera owner already quit.
            // The original run, stop callback and ownership records already exist. No Camera2
            // work is posted and neither Surface is borrowed until this exact lease binds.
            val binding = try {
                (targets.local as? ProducerRetirementInput)?.bindProducer { stop("local-input-retired") }
            } catch (error: Throwable) {
                failure(CameraRunFailure("local-producer-binding", error as? Exception
                    ?: RuntimeException("Local producer registration failed", error)))
                null
            }
            if (binding == null) {
                // Preserve the original association failure observation for a retired endpoint;
                // registering the optional capability correctly rejects it before any borrow.
                try {
                    if (targets.local.retired) failure(CameraRunFailure("target-association",
                        IllegalArgumentException("Local lease does not name original source")))
                } catch (error: Exception) { failure(CameraRunFailure("target-association", error)) }
                failure(CameraRunFailure("local-producer-binding-unavailable",
                    IllegalStateException("Local input cannot bind this original producer")))
                stopSubmitted.set(true)
                if (!snapshot.outputsSafe && !handler.post { ownership.requestStop() }) ownership.requestStop()
                return
            }
            installLocalBinding(binding)
            // Retirement can request stop before bindProducer returns. Its genuine no-open
            // output fence is retained/delivered below even if the owner has already quit.
            if (stopSubmitted.get() && snapshot.outputsSafe) return
            if (!handler.post { open() }) {
                failure(CameraRunFailure("open-submission-rejected", IllegalStateException("Camera owner Handler stopped")))
                ownership.requestStop() // No open invocation or acquired Camera2 resource exists.
            }
        }

        private fun installLocalBinding(binding: LocalProducerBinding) {
            val notify = synchronized(bindingGate) {
                localBinding = binding
                if (actualLocalFence != null && !localFenceClaimed) {
                    localFenceClaimed = true
                    binding
                } else null
            }
            notifyLocalFence(notify)
        }

        private fun notifyLocalFence(binding: LocalProducerBinding?) {
            if (binding == null) return
            try { binding.outputsSafe() }
            catch (error: Throwable) { failure(CameraRunFailure("local-fence-notification", error as? Exception
                ?: RuntimeException("Original local fence notification failed", error))) }
        }

        override fun stop(reason: String): CameraStopSubmission {
            val first = !stopSubmitted.getAndSet(true)
            val posted = if (first) handler.post { ownership.requestStop() } else false
            if (first && !posted && !snapshot.outputsSafe)
                failure(CameraRunFailure("stop-submission-rejected:$reason", IllegalStateException("Camera owner Handler stopped")))
            return CameraStopSubmission(identity, first, posted, snapshot)
        }

        private fun observe(stage: CameraRunStage, cameraId: String? = null, sequence: Int? = null,
            quit: Boolean? = null, alive: Boolean? = null) {
            // Accepted arrivals also change ownership without entering the close driver.
            published = ownership.snapshot()
            try { events.observed(this, CameraRunObservation(stage, published, System.nanoTime(),
                cameraId, sequence, quit, alive)) }
            catch (error: Throwable) { observerFailure(error) }
        }

        private fun observerFailure(error: Throwable) = failure(CameraRunFailure("observer",
            error as? Exception ?: RuntimeException("Camera observation failed", error)))

        private fun failure(fact: CameraRunFailure) {
            lastFailure = fact
            try { events.failed(this, fact) } catch (error: Throwable) {
                if (fact.error != null && error !== fact.error) fact.error.addSuppressed(error)
            }
        }

        private fun outputsSafe(fence: CameraOutputFence, state: CameraOwnershipSnapshot) {
            val fact = CameraOutputsSafe(identity, fence, state)
            val notify = synchronized(bindingGate) {
                actualLocalFence = fact
                val original = localBinding
                if (original != null && !localFenceClaimed) {
                    localFenceClaimed = true
                    original
                } else null
            }
            // No callbacks, Camera2 operations, executor submissions or waits under bindingGate.
            notifyLocalFence(notify)
            try { targets.forwarding.producerStopped(identity, fact) }
            catch (error: Throwable) { failure(CameraRunFailure("forward-fence-notification",
                error as? Exception ?: RuntimeException("Forward fence observer failed", error))) }
            try { events.outputsSafe(this, fact) } catch (error: Throwable) { observerFailure(error) }
            val accepted = thread.quitSafely()
            observe(CameraRunStage.QUIT_REQUESTED, quit = accepted)
        }

        private fun observeThreadTermination() {
            // Begins before open: abnormal loop return is observed too, WITHOUT inventing an
            // output-use fence. The local join observer is neither a server nor a timeout policy.
            Thread({
                try {
                    thread.join()
                    ownerThreadClosedObserved = !thread.isAlive
                    observe(CameraRunStage.OWNER_THREAD_CLOSED, alive = thread.isAlive)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failure(CameraRunFailure("thread-join-unobserved", error))
                }
            }, "eyeprojector-camera-join-${System.identityHashCode(identity)}").apply { isDaemon = true; start() }
        }

        private fun callFailed(operation: CameraCallOperation, error: Exception) {
            failure(CameraRunFailure("${operation.name.lowercase()}-call-exception", error))
            ownership.indeterminate(operation) // Never re-arms a callback-before-catch arrival.
            val context = CameraCallFailureContext(identity, operation, request, error)
            val evidence = try { outcomes.rejection(context) }
                catch (classifierError: Throwable) {
                    failure(CameraRunFailure("outcome-classification-unconfirmed", classifierError as? Exception
                        ?: RuntimeException("Camera outcome resolver failed", classifierError))); null
                }
            if (evidence != null) {
                if (evidence.original === identity && evidence.operation == operation && evidence.originalFailure === error &&
                    evidence.provenance.isNotBlank())
                    ownership.rejected(operation, evidence.provenance)
                else failure(CameraRunFailure("outcome-evidence-incomplete-or-mismatched", error))
            }
            ownership.requestStop()
        }

        private fun open() {
            if (stopSubmitted.get()) { ownership.requestStop(); return }
            try {
                val local = targets.local.configuration
                require(!targets.local.retired && local.source == request.source) { "Local lease does not name original source" }
                require(targets.forwarding.stream.source == request.source) { "Forward lease does not name original source" }
                require(local.requestedBufferWidth == request.localOutput.width &&
                    local.requestedBufferHeight == request.localOutput.height && local.sampling == request.framing) {
                    "Local requested output/framing does not match camera request"
                }
                require(targets.forwarding.requestedOutput == request.forwardingOutput) { "Forward requested output differs" }
                borrowed = targets.local.surface to targets.forwarding.surface
            } catch (error: Exception) {
                failure(CameraRunFailure("target-association", error)); ownership.requestStop(); return
            }
            ownership.beforeOpenInvocation()
            try {
                manager.openCamera(request.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) = callback("opened") {
                        val admitted = ownership.deviceArrived(camera)
                        observe(CameraRunStage.OPENED, cameraId = camera.id)
                        if (!admitted) return@callback
                        if (stopSubmitted.get()) { ownership.requestStop(); return@callback }
                        configure(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) = callback("disconnected") {
                        ownership.deviceArrived(camera)
                        observe(CameraRunStage.DEVICE_DISCONNECTED, cameraId = camera.id)
                        failure(CameraRunFailure("disconnected", null))
                        ownership.requestStop()
                    }
                    override fun onError(camera: CameraDevice, error: Int) = callback("device-error") {
                        ownership.deviceArrived(camera)
                        observe(CameraRunStage.DEVICE_ERROR, cameraId = camera.id)
                        failure(CameraRunFailure("device-error", null, cameraErrorCode = error))
                        ownership.requestStop()
                    }
                    override fun onClosed(camera: CameraDevice) = callback("device-closed") { ownership.deviceClosed(camera) }
                }, handler)
            } catch (error: Exception) {
                // Public exception names alone cannot distinguish known no-acceptance rejection
                // from a queued error callback or an unknown vendor path. Use original-operation
                // evidence when supplied; otherwise preserve indeterminate ownership honestly.
                callFailed(CameraCallOperation.OPEN, error)
            }
        }

        private fun callback(operation: String, action: () -> Unit) {
            try { action() } catch (error: Exception) {
                failure(CameraRunFailure(operation, error)); ownership.requestStop()
            }
        }

        private fun configure(camera: CameraDevice) {
            val originalTargets = checkNotNull(borrowed)
            ownership.beforeConfigurationInvocation()
            try {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(originalTargets.first, originalTargets.second),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) = callback("configured") {
                            val admitted = ownership.sessionArrived(session)
                            observe(CameraRunStage.CONFIGURED)
                            if (!admitted) return@callback
                            if (stopSubmitted.get()) { ownership.requestStop(); return@callback }
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(originalTargets.first); addTarget(originalTargets.second)
                                setTag(identity)
                            }
                            val sequence = session.setRepeatingRequest(builder.build(), captureCallback(), handler)
                            observe(CameraRunStage.REPEATING_SUBMITTED, sequence = sequence)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) = callback("configuration-failed") {
                            failure(CameraRunFailure("configuration-failed", null))
                            ownership.configurationFailed(session)
                        }
                        override fun onClosed(session: CameraCaptureSession) = callback("session-closed") {
                            ownership.sessionClosed(session)
                        }
                    }, handler)
            } catch (error: Exception) {
                callFailed(CameraCallOperation.CONFIGURE, error)
            }
        }

        private fun captureCallback() = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (ownership.snapshot().closing || ownership.session !== session || request.tag !== identity) return
                val fact = try { CameraCaptureObservation(result.frameNumber,
                    result.get(CaptureResult.SENSOR_TIMESTAMP), System.nanoTime()) }
                catch (error: Exception) { failure(CameraRunFailure("capture-result", error)); return }
                try { events.capture(this@Run, fact) } catch (error: Throwable) { observerFailure(error) }
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (ownership.snapshot().closing || ownership.session !== session || request.tag !== identity) return
                this@Run.failure(CameraRunFailure("capture-failed", null, captureFrameNumber = failure.frameNumber,
                    captureFailureReason = failure.reason))
            }
        }
    }
}
