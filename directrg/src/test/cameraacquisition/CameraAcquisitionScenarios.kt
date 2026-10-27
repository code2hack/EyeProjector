package com.code2hack.eyeprojector.directrg.localcamera.acquisition

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.ConfigurationCall
import android.hardware.camera2.OpenCall
import android.hardware.camera2.TotalCaptureResult
import android.os.HandlerThreadProbe
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.CameraSampling
import com.code2hack.eyeprojector.directrg.localcamera.CameraSourceKey
import com.code2hack.eyeprojector.directrg.localcamera.CameraUseKey
import com.code2hack.eyeprojector.directrg.localcamera.LocalCameraConfiguration
import com.code2hack.eyeprojector.directrg.localcamera.LocalCameraFailure
import com.code2hack.eyeprojector.directrg.localcamera.LocalCameraInput
import com.code2hack.eyeprojector.directrg.localcamera.LocalTextureLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun await(latch: CountDownLatch, description: String) {
    check(latch.await(2, TimeUnit.SECONDS)) { "Missing $description" }
}

private class LocalInput(override val configuration: LocalCameraConfiguration, label: String) :
    com.code2hack.eyeprojector.directrg.localcamera.ProducerRetirementInput {
    override val surface = Surface("local-$label")
    override var retired = false
    override val lastFailure: LocalCameraFailure? = null
    var closeCount = 0
    private var bound = false
    private var stop: (() -> Unit)? = null
    private val completions = CopyOnWriteArrayList<(com.code2hack.eyeprojector.directrg.localcamera.LocalInputReleaseResult) -> Unit>()
    override var producerFence = com.code2hack.eyeprojector.directrg.localcamera.LocalProducerFence.NOT_BOUND_LEGACY
        private set
    override var releaseResult: com.code2hack.eyeprojector.directrg.localcamera.LocalInputReleaseResult? = null
        private set
    @Synchronized override fun bindProducer(requestStop: () -> Unit):
        com.code2hack.eyeprojector.directrg.localcamera.LocalProducerBinding? {
        if (retired || bound) return null
        bound = true; stop = requestStop
        producerFence = com.code2hack.eyeprojector.directrg.localcamera.LocalProducerFence.PENDING
        return object : com.code2hack.eyeprojector.directrg.localcamera.LocalProducerBinding {
            override fun outputsSafe() {
                producerFence = com.code2hack.eyeprojector.directrg.localcamera.LocalProducerFence.OUTPUTS_SAFE_RECEIVED
                if (retired) finishRelease()
            }
        }
    }
    private fun finishRelease() {
        val result = synchronized(this) {
            if (releaseResult != null) return
            surface.release()
            com.code2hack.eyeprojector.directrg.localcamera.LocalInputReleaseResult(this, producerFence,
                com.code2hack.eyeprojector.directrg.localcamera.LocalGlContextDisposition.NOT_NEEDED,
                mapOf("surface.release" to com.code2hack.eyeprojector.directrg.localcamera.LocalReleaseStep(
                    com.code2hack.eyeprojector.directrg.localcamera.LocalReleaseDisposition.RETURNED)), emptyList())
                .also { releaseResult = it }
        }
        completions.forEach { it(result) }
    }
    override fun close() {
        if (retired) return
        closeCount++; retired = true
        stop?.invoke()
        if (producerFence != com.code2hack.eyeprojector.directrg.localcamera.LocalProducerFence.PENDING) finishRelease()
    }
    override fun closeAsync(completionExecutor: java.util.concurrent.Executor,
        onRelease: (com.code2hack.eyeprojector.directrg.localcamera.LocalInputReleaseResult) -> Unit) {
        val dispatched = java.util.concurrent.atomic.AtomicBoolean()
        val completion: (com.code2hack.eyeprojector.directrg.localcamera.LocalInputReleaseResult) -> Unit = { result ->
            if (dispatched.compareAndSet(false, true)) completionExecutor.execute { onRelease(result) }
        }
        completions.add(completion)
        close()
        releaseResult?.let(completion)
    }
}

private class ForwardInput(
    override val stream: ForwardCameraStream,
    override val requestedOutput: RequestedCameraOutput,
    label: String,
) : ForwardCameraInputLease {
    override val surface = Surface("encoder-$label")
    val notifications = CopyOnWriteArrayList<Pair<CameraRunIdentity, CameraOutputsSafe>>()
    var throwNotification = false
    override fun producerStopped(original: CameraRunIdentity, fact: CameraOutputsSafe) {
        notifications.add(original to fact)
        if (throwNotification) throw IllegalStateException("forward observer fault")
    }
}

private data class Observed(val run: OwnedCameraRun, val fact: CameraRunObservation, val thread: Thread)
private class Events : CameraRunEvents {
    val observations = CopyOnWriteArrayList<Observed>()
    val captures = CopyOnWriteArrayList<Pair<OwnedCameraRun, CameraCaptureObservation>>()
    val failures = CopyOnWriteArrayList<Pair<OwnedCameraRun, CameraRunFailure>>()
    val failureOwnership = CopyOnWriteArrayList<Pair<CameraRunFailure, CameraOwnershipSnapshot>>()
    val fences = CopyOnWriteArrayList<Pair<OwnedCameraRun, CameraOutputsSafe>>()
    private val signals = CameraRunStage.entries.associateWith { CountDownLatch(1) }
    var throwStages = emptySet<CameraRunStage>()
    var throwCapture = false
    var throwSafe = false
    var throwFailure = false
    var holdLoopReturn: CountDownLatch? = null
    var onObserved: ((OwnedCameraRun, CameraRunObservation) -> Unit)? = null
    override fun observed(original: OwnedCameraRun, observation: CameraRunObservation) {
        observations.add(Observed(original, observation, Thread.currentThread()))
        signals.getValue(observation.stage).countDown()
        if (observation.stage == CameraRunStage.OWNER_LOOP_RETURNED) holdLoopReturn?.await()
        onObserved?.invoke(original, observation)
        if (observation.stage in throwStages) throw AssertionError("observation fault ${observation.stage}")
    }
    override fun capture(original: OwnedCameraRun, observation: CameraCaptureObservation) {
        captures.add(original to observation)
        if (throwCapture) throw AssertionError("capture observer fault")
    }
    override fun failed(original: OwnedCameraRun, failure: CameraRunFailure) {
        failures.add(original to failure)
        failureOwnership.add(failure to original.snapshot)
        if (throwFailure) throw AssertionError("failure observer fault")
    }
    override fun outputsSafe(original: OwnedCameraRun, fact: CameraOutputsSafe) {
        fences.add(original to fact)
        if (throwSafe) throw AssertionError("safe observer fault")
    }
    fun await(stage: CameraRunStage) = await(signals.getValue(stage), stage.name)
    fun facts(stage: CameraRunStage) = observations.filter { it.fact.stage == stage }
    fun failure(operation: String) = failures.single { it.second.operation == operation }.second
}

private class Fixture(
    val label: String,
    val manager: CameraManager = CameraManager(),
    val events: Events = Events(),
    val outcomes: CameraCallOutcomeResolver? = null,
) {
    val source = CameraSourceKey(CameraUseKey("same-projection", 7), 11)
    val framing = CameraSampling(1920, 1080)
    val localOutput = RequestedCameraOutput(960, 540, 34)
    val encoderOutput = RequestedCameraOutput(1280, 720, 34)
    val request = CameraUseDescription(source, "requested-camera-0", localOutput, encoderOutput, framing,
        "explicit host fixture request; requested format/size is not support evidence")
    val local = LocalInput(LocalCameraConfiguration(source, framing, 960, 540), label)
    val forward = ForwardInput(ForwardCameraStream(source, 17), encoderOutput, label)
    lateinit var run: OwnedCameraRun
    fun start(): Fixture {
        val producer = outcomes?.let { GlassesCameraProducer(manager, it) } ?: GlassesCameraProducer(manager)
        run = producer.start(request, CameraTargets(local, forward), events)
        return this
    }
    fun open(): OpenCall = manager.awaitOpen().also { it.handler.drain() }
    fun finished(fence: CameraOutputFence = CameraOutputFence.ORIGINAL_DEVICE_ON_CLOSED) {
        events.await(CameraRunStage.OWNER_THREAD_CLOSED)
        check(run.ownerThreadClosedObserved)
        check(run.snapshot.outputsSafe)
        check(events.fences.size == 1)
        check(events.fences.single().first === run)
        check(events.fences.single().second.original === run.identity)
        check(events.fences.single().second.fence == fence)
        check(forward.notifications.size == 1)
        check(forward.notifications.single().first === run.identity)
        check(forward.notifications.single().second === events.fences.single().second)
        check(local.closeCount == 0 && !local.retired)
        check(local.surface.releaseCount == 0 && forward.surface.releaseCount == 0)
        val loopReturn = events.facts(CameraRunStage.OWNER_LOOP_RETURNED).single()
        check(loopReturn.fact.ownerThreadAlive == true)
        val closure = events.facts(CameraRunStage.OWNER_THREAD_CLOSED).single()
        check(closure.fact.ownerThreadAlive == false)
        check(!loopReturn.thread.isAlive)
        check(closure.thread !== loopReturn.thread)
        check(events.facts(CameraRunStage.OUTPUTS_SAFE).size == 1)
        check(events.facts(CameraRunStage.QUIT_REQUESTED).single().fact.quitAccepted == true)
    }
}

private data class Active(val fixture: Fixture, val open: OpenCall, val device: CameraDevice,
    val configuration: ConfigurationCall, val session: CameraCaptureSession)

private fun configure(f: Fixture): Active {
    val open = f.open()
    val device = CameraDevice("actual-device-${f.label}")
    check(open.opened(device)); open.handler.drain()
    val configuration = device.configurations.single()
    val session = CameraCaptureSession("session-${f.label}")
    check(configuration.configured(session)); open.handler.drain()
    check(configuration.handler === open.handler)
    check(configuration.outputs.size == 2)
    check(configuration.outputs[0] === f.local.surface && configuration.outputs[1] === f.forward.surface)
    val repeating = session.repeating.single()
    check(repeating.handler === open.handler)
    check(repeating.request.targets.size == 2)
    check(repeating.request.targets[0] === f.local.surface && repeating.request.targets[1] === f.forward.surface)
    check(repeating.request.tag === f.run.identity)
    check(device.templates.single() == CameraDevice.TEMPLATE_RECORD)
    check(open.cameraId == f.request.cameraId)
    check(f.events.facts(CameraRunStage.OPENED).single().fact.actualCameraId == device.id)
    check(f.events.facts(CameraRunStage.REPEATING_SUBMITTED).single().fact.repeatingSequenceId == 73)
    val opened = f.events.facts(CameraRunStage.OPENED).single().fact.ownership
    check(opened.hasDevice && opened.openArrivalObserved && !opened.openPending && !opened.hasSession)
    check(opened.openDisposition == CameraArrivalDisposition.ARRIVED)
    val configured = f.events.facts(CameraRunStage.CONFIGURED).single().fact.ownership
    check(configured.hasDevice && configured.hasSession && configured.configurationArrivalObserved)
    check(!configured.configurationPending && configured.configurationDisposition == CameraArrivalDisposition.ARRIVED)
    val repeatingOwnership = f.events.facts(CameraRunStage.REPEATING_SUBMITTED).single().fact.ownership
    check(repeatingOwnership == configured && f.run.snapshot == configured)
    check(open.deliveryThreads.all { it === open.handler.looper.owner })
    check(configuration.deliveryThreads.all { it === open.handler.looper.owner })
    return Active(f, open, device, configuration, session)
}

private fun Active.stopAwaitCalls() {
    val receipt = fixture.run.stop("host scenario")
    check(receipt.original === fixture.run.identity && receipt.firstSubmission && receipt.posted)
    val repeated = fixture.run.stop("same original stop again")
    check(!repeated.firstSubmission && !repeated.posted)
    open.handler.drain()
    check(session.closeCount == 1 && device.closeCount == 1)
    check(fixture.run.snapshot.sessionCloseCallReturned && fixture.run.snapshot.deviceCloseCallReturned)
    check(!fixture.run.snapshot.sessionClosedObserved && !fixture.run.snapshot.deviceClosedObserved)
    check(!fixture.run.snapshot.outputsSafe && !fixture.run.ownerThreadClosedObserved)
    check(fixture.events.fences.isEmpty() && fixture.forward.notifications.isEmpty())
}

private fun happyTargetsCaptureAndActualSessionClosure() {
    val f = Fixture("happy").start()
    val a = configure(f)
    check(f.events.captures.isEmpty()) // Repeating submission is not a capture or local texture latch.
    val repeating = a.session.repeating.single()
    check(repeating.completed(TotalCaptureResult(601, 7_000_000_123)))
    check(repeating.completed(TotalCaptureResult(602, null)))
    check(repeating.failed(CaptureFailure(603, 4)))
    check(repeating.completed(TotalCaptureResult(604, 99), from = CameraCaptureSession("wrong-original")))
    val wrongTag = CaptureRequest.Builder().apply { setTag(CameraRunIdentity()) }.build()
    check(repeating.completed(TotalCaptureResult(605, 99), originalRequest = wrongTag))
    a.open.handler.drain()
    check(f.events.captures.map { it.second.frameNumber } == listOf(601L, 602L))
    check(f.events.captures[0].second.sensorTimestampNanos == 7_000_000_123L)
    check(f.events.captures[1].second.sensorTimestampNanos == null)
    check(f.events.captures.all { it.first === f.run &&
        it.second.timestampClockDomain == "CAMERA2_SENSOR_TIMESTAMP_SOURCE_UNOBSERVED" })
    check(LocalTextureLatch(1, 7_000_000_123).timestampClockDomain == "SURFACE_TEXTURE_PRODUCER_UNVERIFIED")
    check(f.events.failure("capture-failed").captureFrameNumber == 603L)
    check(f.events.failure("capture-failed").captureFailureReason == 4)
    a.stopAwaitCalls()
    check(repeating.completed(TotalCaptureResult(606, 99))); a.open.handler.drain()
    check(f.events.captures.size == 2)
    check(a.configuration.closed(a.session)); a.open.handler.drain()
    check(f.run.snapshot.sessionClosedObserved)
    check(f.run.snapshot.sessionRetirement == SessionRetirement.SESSION_ON_CLOSED)
    check(!f.run.snapshot.outputsSafe) // Session callback alone does not fence the original parent.
    check(a.open.closed(a.device)); f.finished()
}

private fun parentFenceDoesNotInventSessionClosed() {
    val f = Fixture("parent-fence").start()
    val a = configure(f)
    a.stopAwaitCalls()
    check(a.open.closed(a.device)); f.finished()
    check(f.run.snapshot.sessionRetirement == SessionRetirement.PARENT_DEVICE_ON_CLOSED)
    check(!f.run.snapshot.sessionClosedObserved)
    check(f.events.facts(CameraRunStage.SESSION_CLOSED).isEmpty())
    check(!a.configuration.closed(a.session)) // Original Handler is shut; no delivery is silently rerouted.
    check(a.session.closeCount == 1 && a.device.closeCount == 1)
}

private fun stopBeforeOpenInvocation() {
    val gate = HandlerThreadProbe.pauseNextStart()
    try {
        val f = Fixture("stop-before-invoke").start()
        val receipt = f.run.stop("before actual invocation")
        check(receipt.firstSubmission && receipt.posted && !receipt.ownershipAtReturn.openInvoked)
        check(!f.run.ownerThreadClosedObserved && f.events.fences.isEmpty())
        gate.countDown()
        f.finished(CameraOutputFence.NO_OPEN_INVOKED)
        check(f.manager.opens.isEmpty() && !f.run.snapshot.openInvoked)
    } finally { gate.countDown() }
}

private fun stopBeforeOpenedCallback() {
    val f = Fixture("stop-before-opened").start()
    val open = f.open()
    f.run.stop("open callback pending"); open.handler.drain()
    check(f.run.snapshot.openPending && !f.run.snapshot.outputsSafe)
    val device = CameraDevice("late-opened")
    check(open.opened(device)); open.handler.drain()
    check(device.closeCount == 1 && device.configurations.isEmpty())
    check(!f.run.snapshot.openPending && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
}

private fun stopBeforeConfiguredAndLateHandleAfterParentClosure() {
    val f = Fixture("late-configured").start()
    val open = f.open()
    val device = CameraDevice("late-config-parent")
    check(open.opened(device)); open.handler.drain()
    val configuration = device.configurations.single()
    f.run.stop("configuration callback pending"); open.handler.drain()
    check(device.closeCount == 1 && f.run.snapshot.configurationPending)
    val next = Fixture("late-config-successor", f.manager).start()
    val nextOpen = next.open()
    check(next.request == f.request && next.run.identity !== f.run.identity)
    val successorBefore = next.run.snapshot
    val lateSession = CameraCaptureSession("late-after-parent")
    // Both original callbacks are accepted before parent onClosed requests a draining quit.
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    check(open.handler.post { entered.countDown(); release.await() })
    await(entered, "original owner queue gate")
    try {
        check(open.closed(device))
        check(configuration.configured(lateSession))
    } finally { release.countDown() }
    f.finished()
    val safeOwnership = f.events.fences.single().second.ownership
    check(safeOwnership.deviceClosedObserved && !safeOwnership.configurationPending)
    check(!safeOwnership.configurationArrivalObserved && !safeOwnership.hasSession)
    check(safeOwnership.configurationDisposition == CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE)
    check(lateSession.closeCount == 1 && lateSession.repeating.isEmpty())
    check(f.run.snapshot.configurationArrivalObserved && f.run.snapshot.hasSession)
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE)
    check(f.run.snapshot.sessionRetirement == SessionRetirement.PARENT_DEVICE_ON_CLOSED)
    check(!f.run.snapshot.sessionClosedObserved)
    check(f.events.facts(CameraRunStage.LATE_CONFIGURATION_ARRIVED).size == 1)
    check(configuration.deliveryThreads.single() === open.handler.looper.owner)
    check(!configuration.configured(CameraCaptureSession("after-dead-original-handler")))
    check(next.run.snapshot == successorBefore && next.forward.notifications.isEmpty())
    next.run.stop("finish independent successor"); nextOpen.handler.drain()
    val nextDevice = CameraDevice("late-config-parent")
    check(nextOpen.opened(nextDevice)); nextOpen.handler.drain()
    check(nextDevice.configurations.isEmpty() && nextDevice.closeCount == 1)
    check(nextOpen.closed(nextDevice)); next.finished()
}

private fun invocationExceptionRemainsPending(permission: Boolean) {
    val manager = CameraManager().apply {
        afterInvocationException = if (permission) SecurityException("permission denied after invocation recorded")
            else IllegalStateException("open invocation failed after callback leg created")
    }
    val f = Fixture(if (permission) "permission" else "open-exception", manager).start()
    val open = f.open()
    check(f.run.snapshot.openInvoked && f.run.snapshot.openPending && f.run.snapshot.closing)
    check(f.run.snapshot.openDisposition == CameraArrivalDisposition.INDETERMINATE)
    check(f.run.snapshot.openRejectionProvenance == null && !f.run.snapshot.openArrivalObserved)
    check(!f.run.snapshot.outputsSafe && !f.run.ownerThreadClosedObserved)
    check(f.events.fences.isEmpty() && f.events.facts(CameraRunStage.QUIT_REQUESTED).isEmpty())
    check(f.events.failure("open-call-exception").error === manager.afterInvocationException)
    // Explicit later callback resolves the unresolved invocation; no timeout or synthetic closure does.
    val device = CameraDevice("late-exception-device")
    check(open.error(device, 5)); open.handler.drain()
    check(device.closeCount == 1 && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
}

private fun unknownConfigurationInvocationRetiredByOriginalParentFence() {
    val f = Fixture("configuration-exception").start()
    val open = f.open()
    val device = CameraDevice("configuration-throw").apply {
        afterConfigurationInvocationException = IllegalArgumentException("configuration invoked then threw")
    }
    check(open.opened(device)); open.handler.drain()
    f.events.failure("configure-call-exception")
    check(f.run.snapshot.configurationPending && device.closeCount == 1)
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.INDETERMINATE)
    check(!f.run.snapshot.outputsSafe && f.forward.notifications.isEmpty())
    check(f.events.facts(CameraRunStage.QUIT_REQUESTED).isEmpty())
    check(open.closed(device)); f.finished()
    check(!f.run.snapshot.configurationPending && !f.run.snapshot.configurationArrivalObserved)
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE)
    check(!f.run.snapshot.hasSession && !f.run.snapshot.sessionClosedObserved)
    check(device.configurations.single().deliveryThreads.isEmpty()) // No metadata callback is required for this fence.
}

private fun configurationFailureIsNotSessionClosed() {
    val f = Fixture("configuration-failed").start()
    val open = f.open()
    val device = CameraDevice("configuration-failed-device")
    check(open.opened(device)); open.handler.drain()
    val configuration = device.configurations.single()
    val session = CameraCaptureSession("failed-session")
    check(configuration.failed(session)); open.handler.drain()
    check(f.run.snapshot.sessionRetirement == SessionRetirement.CONFIGURATION_FAILED)
    check(!f.run.snapshot.sessionClosedObserved && !f.run.snapshot.outputsSafe)
    check(session.closeCount == 0 && session.repeating.isEmpty() && device.closeCount == 1)
    check(open.closed(device)); f.finished()
}

private fun deviceFailure(error: Boolean) {
    val f = Fixture(if (error) "device-error" else "disconnect").start()
    val open = f.open()
    val device = CameraDevice("original-failed-device")
    if (error) check(open.error(device, 4)) else check(open.disconnected(device))
    open.handler.drain()
    val failure = f.events.failure(if (error) "device-error" else "disconnected")
    check(failure.cameraErrorCode == if (error) 4 else null)
    val failureSnapshot = f.events.failureOwnership.single { it.first === failure }.second
    check(failureSnapshot.hasDevice && failureSnapshot.openArrivalObserved && !failureSnapshot.openPending)
    val observation = f.events.facts(if (error) CameraRunStage.DEVICE_ERROR else CameraRunStage.DEVICE_DISCONNECTED).single()
    check(observation.fact.ownership.hasDevice && observation.fact.ownership.openArrivalObserved)
    check(!observation.fact.ownership.openPending && observation.fact.ownership.openDisposition == CameraArrivalDisposition.ARRIVED)
    check(f.events.facts(CameraRunStage.OPENED).isEmpty() && f.events.facts(CameraRunStage.REPEATING_SUBMITTED).isEmpty())
    check(device.closeCount == 1 && device.configurations.isEmpty() && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
}

private fun sameTupleReplacementKeepsOriginalCallbacks() {
    val manager = CameraManager()
    val first = Fixture("first", manager).start()
    val oldOpen = first.open()
    first.run.stop("original pending open"); oldOpen.handler.drain()
    val next = Fixture("successor", manager).start()
    val nextOpen = next.open()
    check(first.request == next.request && first.run.identity !== next.run.identity)
    check(oldOpen.handler !== nextOpen.handler)
    val successorBefore = next.run.snapshot
    val originalDevice = CameraDevice("same-reusable-camera-id")
    check(oldOpen.opened(originalDevice)); oldOpen.handler.drain()
    check(originalDevice.closeCount == 1 && originalDevice.configurations.isEmpty())
    check(oldOpen.closed(originalDevice)); first.finished()
    check(next.run.snapshot == successorBefore)
    check(next.forward.notifications.isEmpty() && next.events.fences.isEmpty())
    check(!oldOpen.error(originalDevice, 4))
    val nextDevice = CameraDevice("same-reusable-camera-id")
    check(nextOpen.opened(nextDevice)); nextOpen.handler.drain()
    val configuration = nextDevice.configurations.single()
    val nextSession = CameraCaptureSession("successor-original-session")
    check(configuration.configured(nextSession)); nextOpen.handler.drain()
    check(nextSession.repeating.single().request.tag === next.run.identity)
    next.run.stop("successor stop"); nextOpen.handler.drain()
    check(nextOpen.closed(nextDevice)); next.finished()
    check(nextSession.closeCount == 1 && nextDevice.closeCount == 1)
}

private fun localStopReceiptIsNotThreadJoin() {
    val releaseLoop = CountDownLatch(1)
    val events = Events().apply { holdLoopReturn = releaseLoop }
    try {
        val f = Fixture("thread-facts", events = events).start()
        val a = configure(f)
        a.stopAwaitCalls()
        check(a.open.closed(a.device))
        events.await(CameraRunStage.OWNER_LOOP_RETURNED)
        check(f.run.snapshot.outputsSafe && !f.run.ownerThreadClosedObserved)
        check(events.facts(CameraRunStage.OWNER_LOOP_RETURNED).single().thread.isAlive)
        check(events.facts(CameraRunStage.OWNER_THREAD_CLOSED).isEmpty())
        releaseLoop.countDown()
        f.finished()
    } finally { releaseLoop.countDown() }
}

private fun observerFaultsDoNotOwnEndpointsOrStrandThread() {
    val events = Events().apply {
        throwStages = setOf(CameraRunStage.OPENED, CameraRunStage.OUTPUTS_SAFE,
            CameraRunStage.OWNER_LOOP_RETURNED, CameraRunStage.OWNER_THREAD_CLOSED)
        throwCapture = true; throwSafe = true; throwFailure = true
    }
    val f = Fixture("observer-faults", events = events)
    f.forward.throwNotification = true
    f.start()
    val a = configure(f)
    check(a.session.repeating.single().completed(TotalCaptureResult(77, 123)))
    a.open.handler.drain()
    check(events.captures.single().second.frameNumber == 77L)
    check(a.session.repeating.single().completed(TotalCaptureResult(78, null,
        IllegalStateException("capture metadata read fault"))))
    a.open.handler.drain()
    events.failure("capture-result")
    a.stopAwaitCalls()
    check(a.open.closed(a.device)); f.finished()
    check(events.failures.any { it.second.operation == "observer" })
    events.failure("forward-fence-notification")
    check(events.failures.any { it.second.error?.suppressed?.isNotEmpty() == true })
}

private fun targetAssociationFailureNeverInvokesCameraOrReleasesBorrowedSurfaces() {
    val f = Fixture("invalid-local")
    f.local.retired = true
    f.start()
    f.events.await(CameraRunStage.OWNER_THREAD_CLOSED)
    check(f.manager.opens.isEmpty() && !f.run.snapshot.openInvoked)
    check(f.run.snapshot.outputsSafe && f.run.ownerThreadClosedObserved)
    check(f.events.fences.single().second.fence == CameraOutputFence.NO_OPEN_INVOKED)
    check(f.forward.notifications.size == 1)
    check(f.local.closeCount == 0 && f.local.surface.releaseCount == 0 && f.forward.surface.releaseCount == 0)
    f.events.failure("target-association")
}

private fun repeatingInvocationFailureDoesNotReportSubmittedOrCaptured() {
    val f = Fixture("repeating-exception").start()
    val open = f.open()
    val device = CameraDevice("repeating-failed-device")
    check(open.opened(device)); open.handler.drain()
    val configuration = device.configurations.single()
    val session = CameraCaptureSession("repeating-failed-session").apply {
        repeatingException = IllegalStateException("setRepeatingRequest invoked then threw")
    }
    check(configuration.configured(session)); open.handler.drain()
    check(session.repeating.size == 1) // Invocation exists; a returned sequence does not.
    check(f.events.facts(CameraRunStage.REPEATING_SUBMITTED).isEmpty() && f.events.captures.isEmpty())
    f.events.failure("configured")
    check(session.closeCount == 1 && device.closeCount == 1 && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
    check(!f.run.snapshot.sessionClosedObserved)
}

private fun closeExceptionsRequireOriginalCallbacks() {
    val f = Fixture("close-exceptions").start()
    val a = configure(f)
    a.session.closeException = IllegalStateException("session close did not return")
    a.device.closeException = IllegalStateException("device close did not return")
    f.run.stop("close exception scenario"); a.open.handler.drain()
    check(f.run.snapshot.sessionCloseInvoked && f.run.snapshot.deviceCloseInvoked)
    check(!f.run.snapshot.sessionCloseCallReturned && !f.run.snapshot.deviceCloseCallReturned)
    check(!f.run.snapshot.sessionClosedObserved && !f.run.snapshot.deviceClosedObserved)
    check(!f.run.snapshot.outputsSafe && f.forward.notifications.isEmpty())
    check(f.events.facts(CameraRunStage.QUIT_REQUESTED).isEmpty())
    f.events.failure("session-close-unconfirmed"); f.events.failure("device-close-unconfirmed")
    check(a.configuration.closed(a.session)); a.open.handler.drain()
    check(!f.run.snapshot.outputsSafe)
    check(a.open.closed(a.device)); f.finished()
    check(a.session.closeCount == 1 && a.device.closeCount == 1)
    check(f.run.snapshot.sessionClosedObserved && f.run.snapshot.deviceClosedObserved)
}

private fun reentrantObserverStopDoesNotConfigureOrReleaseEndpoints() {
    val receipts = CopyOnWriteArrayList<CameraStopSubmission>()
    val events = Events().apply {
        onObserved = { original, observation ->
            if (observation.stage == CameraRunStage.OPENED) receipts.add(original.stop("observer reentrant stop"))
        }
    }
    val f = Fixture("reentrant-observer", events = events).start()
    val open = f.open()
    val device = CameraDevice("reentrant-stop-device")
    check(open.opened(device)); open.handler.drain()
    check(receipts.single().original === f.run.identity && receipts.single().firstSubmission)
    check(device.configurations.isEmpty() && device.closeCount == 1 && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
}

private fun knownOrdinaryPermissionDenialHasExplicitNoAcceptanceWitness() {
    val denied = SecurityException("controlled ordinary permission-check rejection")
    // The exact same exception object remains unknown without its supported operation-path witness.
    val unknownManager = CameraManager().apply { afterInvocationException = denied }
    val unknown = Fixture("same-permission-exception-unknown", unknownManager).start()
    val unknownOpen = unknown.open()
    check(unknown.run.snapshot.openPending && unknown.run.snapshot.openDisposition == CameraArrivalDisposition.INDETERMINATE)
    check(!unknown.run.snapshot.outputsSafe && unknown.forward.notifications.isEmpty())
    val unknownDevice = CameraDevice("resolve-same-permission-exception-unknown")
    check(unknownOpen.error(unknownDevice, 5)); unknownOpen.handler.drain()
    check(unknownOpen.closed(unknownDevice)); unknown.finished()
    val manager = CameraManager().apply { afterInvocationException = denied }
    val contexts = CopyOnWriteArrayList<CameraCallFailureContext>()
    val provenance = "HOST_DOUBLE/openCamera/ordinary-permission-check-before-device-acceptance/no-callback-branch"
    val resolver = CameraCallOutcomeResolver { context ->
        contexts.add(context)
        check(context.operation == CameraCallOperation.OPEN && context.failure === denied)
        CameraRejectedBeforeAcceptance(context.original, context.operation, denied, provenance)
    }
    val f = Fixture("known-permission-rejection", manager, outcomes = resolver).start()
    val open = manager.awaitOpen() // The known rejected run may already have quit; do not post a new barrier.
    f.finished(CameraOutputFence.KNOWN_OPEN_REJECTED_BEFORE_ACCEPTANCE)
    val context = contexts.single()
    check(context.original === f.run.identity && context.request === f.request && context.failure === denied)
    check(f.events.failure("open-call-exception").error === denied)
    check(f.run.snapshot.openInvoked && !f.run.snapshot.openPending)
    check(f.run.snapshot.openDisposition == CameraArrivalDisposition.REJECTED_BEFORE_ACCEPTANCE)
    check(f.run.snapshot.openRejectionProvenance == provenance)
    check(!f.run.snapshot.hasDevice && !f.run.snapshot.openArrivalObserved && !f.run.snapshot.deviceClosedObserved)
    check(f.events.facts(CameraRunStage.DEVICE_CLOSED).isEmpty() && open.deliveryThreads.isEmpty())
    check(f.events.facts(CameraRunStage.REJECTED_BEFORE_ACCEPTANCE).size == 1)
}

private fun openThrowWithQueuedOriginalDeviceError() {
    val thrown = CameraAccessException(3, "open path queued original error, then threw")
    val device = CameraDevice("queued-open-error-device")
    val manager = CameraManager().apply {
        duringInvocation = { original -> check(original.error(device, 6)) }
        afterInvocationException = thrown
    }
    val f = Fixture("open-throw-queued-error", manager).start()
    val open = f.open()
    check(f.events.failure("open-call-exception").error === thrown)
    check(f.events.failure("device-error").cameraErrorCode == 6)
    check(f.events.facts(CameraRunStage.OUTCOME_INDETERMINATE).size == 1)
    check(!f.run.snapshot.openPending && f.run.snapshot.openArrivalObserved)
    check(f.run.snapshot.openDisposition == CameraArrivalDisposition.ARRIVED)
    check(device.closeCount == 1 && !f.run.snapshot.outputsSafe)
    check(f.events.facts(CameraRunStage.REJECTED_BEFORE_ACCEPTANCE).isEmpty())
    check(open.deliveryThreads.single() === open.handler.looper.owner)
    check(open.closed(device)); f.finished()
}

private fun configureThrowWithQueuedOriginalConfigureFailed() {
    val thrown = CameraAccessException(3, "configuration path queued failure, then threw")
    val failedSession = CameraCaptureSession("queued-configure-failed")
    val device = CameraDevice("queued-configure-error-device").apply {
        duringConfigurationInvocation = { original -> check(original.failed(failedSession)) }
        afterConfigurationInvocationException = thrown
    }
    val f = Fixture("configure-throw-queued-failure").start()
    val open = f.open()
    check(open.opened(device)); open.handler.drain(); open.handler.drain()
    check(f.events.failure("configure-call-exception").error === thrown)
    f.events.failure("configuration-failed")
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.ARRIVED)
    check(!f.run.snapshot.configurationPending && f.run.snapshot.configurationArrivalObserved)
    check(f.run.snapshot.sessionRetirement == SessionRetirement.CONFIGURATION_FAILED)
    check(!f.run.snapshot.sessionClosedObserved && failedSession.closeCount == 0)
    check(failedSession.repeating.isEmpty() && !f.run.snapshot.outputsSafe && device.closeCount == 1)
    check(device.configurations.single().deliveryThreads.single() === open.handler.looper.owner)
    check(open.closed(device)); f.finished()
    check(f.events.facts(CameraRunStage.SESSION_CLOSED).isEmpty())
}

private fun openCallbackBeforeCatchDoesNotRearmArrival() {
    val thrown = IllegalStateException("throw after reentrant original device error")
    val device = CameraDevice("inline-open-error-device")
    val manager = CameraManager().apply {
        duringInvocation = { original -> original.errorInlineBeforeReturn(device, 8) }
        afterInvocationException = thrown
    }
    val f = Fixture("open-callback-before-catch", manager).start()
    val open = f.open()
    check(f.events.failure("open-call-exception").error === thrown)
    check(f.run.snapshot.openArrivalObserved && !f.run.snapshot.openPending)
    check(f.run.snapshot.openDisposition == CameraArrivalDisposition.ARRIVED)
    check(f.events.facts(CameraRunStage.OUTCOME_INDETERMINATE).isEmpty())
    check(f.events.facts(CameraRunStage.REJECTED_BEFORE_ACCEPTANCE).isEmpty())
    check(device.closeCount == 1 && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
}

private fun configurationCallbackBeforeCatchDoesNotRearmArrival() {
    val thrown = IllegalArgumentException("throw after reentrant original configure failure")
    val failedSession = CameraCaptureSession("inline-configure-failed")
    val device = CameraDevice("inline-configure-error-device").apply {
        duringConfigurationInvocation = { original -> original.failedInlineBeforeReturn(failedSession) }
        afterConfigurationInvocationException = thrown
    }
    val f = Fixture("configuration-callback-before-catch").start()
    val open = f.open()
    check(open.opened(device)); open.handler.drain()
    check(f.events.failure("configure-call-exception").error === thrown)
    check(f.run.snapshot.configurationArrivalObserved && !f.run.snapshot.configurationPending)
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.ARRIVED)
    check(f.run.snapshot.sessionRetirement == SessionRetirement.CONFIGURATION_FAILED)
    check(!f.run.snapshot.sessionClosedObserved && failedSession.closeCount == 0)
    check(f.events.facts(CameraRunStage.OUTCOME_INDETERMINATE).isEmpty())
    check(!f.run.snapshot.outputsSafe && device.closeCount == 1)
    check(open.closed(device)); f.finished()
}

private fun knownPreSessionRejectionStillWaitsOwnedDeviceFence() {
    val thrown = IllegalArgumentException("controlled validation rejection before new session acceptance")
    val provenance = "HOST_DOUBLE/createCaptureSession/known-pre-new-session-validation-rejection"
    val contexts = CopyOnWriteArrayList<CameraCallFailureContext>()
    val resolver = CameraCallOutcomeResolver { context ->
        contexts.add(context)
        check(context.operation == CameraCallOperation.CONFIGURE && context.failure === thrown)
        CameraRejectedBeforeAcceptance(context.original, context.operation, thrown, provenance)
    }
    val f = Fixture("known-configure-rejection", outcomes = resolver).start()
    val open = f.open()
    val device = CameraDevice("owned-before-configure-rejection").apply {
        afterConfigurationInvocationException = thrown
    }
    check(open.opened(device)); open.handler.drain()
    val context = contexts.single()
    check(context.original === f.run.identity && context.request === f.request)
    check(f.run.snapshot.openDisposition == CameraArrivalDisposition.ARRIVED && f.run.snapshot.hasDevice)
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.REJECTED_BEFORE_ACCEPTANCE)
    check(f.run.snapshot.configurationRejectionProvenance == provenance)
    check(!f.run.snapshot.configurationPending && !f.run.snapshot.configurationArrivalObserved && !f.run.snapshot.hasSession)
    check(device.closeCount == 1 && !f.run.snapshot.outputsSafe && !f.run.snapshot.deviceClosedObserved)
    check(f.forward.notifications.isEmpty() && f.events.facts(CameraRunStage.QUIT_REQUESTED).isEmpty())
    check(open.closed(device)); f.finished()
    check(f.run.snapshot.configurationDisposition == CameraArrivalDisposition.REJECTED_BEFORE_ACCEPTANCE)
}

private fun invalidRejectionEvidenceRemainsIndeterminate(kind: String) {
    val thrown = SecurityException("same exception class does not establish no acceptance")
    val manager = CameraManager().apply { afterInvocationException = thrown }
    val resolver = CameraCallOutcomeResolver { context ->
        CameraRejectedBeforeAcceptance(
            if (kind == "run") CameraRunIdentity() else context.original,
            if (kind == "operation") CameraCallOperation.CONFIGURE else context.operation,
            if (kind == "error") SecurityException(thrown.message) else thrown,
            if (kind == "provenance") " \t" else "HOST_DOUBLE/explicit-original-operation-witness",
        )
    }
    val f = Fixture("invalid-evidence-$kind", manager, outcomes = resolver).start()
    val open = f.open()
    check(f.events.failure("outcome-evidence-incomplete-or-mismatched").error === thrown)
    check(f.run.snapshot.openPending && f.run.snapshot.openDisposition == CameraArrivalDisposition.INDETERMINATE)
    check(f.run.snapshot.openRejectionProvenance == null && !f.run.snapshot.outputsSafe)
    check(f.events.facts(CameraRunStage.REJECTED_BEFORE_ACCEPTANCE).isEmpty())
    val device = CameraDevice("resolve-invalid-evidence-$kind")
    check(open.error(device, 9)); open.handler.drain()
    check(device.closeCount == 1 && !f.run.snapshot.outputsSafe)
    check(open.closed(device)); f.finished()
}

private fun abnormalOwnerLoopExitIsJoinedWithoutOutputSafety() {
    val f = Fixture("abnormal-loop-exit").start()
    val open = f.open()
    val expected = AssertionError("explicit host owner-loop exit control")
    check(open.handler.post { throw expected })
    f.events.await(CameraRunStage.OWNER_THREAD_CLOSED)
    check(f.run.ownerThreadClosedObserved && !open.handler.looper.owner.isAlive)
    check(f.run.snapshot.openPending && f.run.snapshot.openDisposition == CameraArrivalDisposition.PENDING)
    check(!f.run.snapshot.outputsSafe && !f.run.snapshot.deviceClosedObserved)
    check(f.events.fences.isEmpty() && f.forward.notifications.isEmpty())
    check(f.events.facts(CameraRunStage.OUTPUTS_SAFE).isEmpty() && f.events.facts(CameraRunStage.QUIT_REQUESTED).isEmpty())
    check(f.events.facts(CameraRunStage.OWNER_LOOP_RETURNED).single().fact.ownerThreadAlive == true)
    check(f.events.facts(CameraRunStage.OWNER_THREAD_CLOSED).single().fact.ownerThreadAlive == false)
    check(!open.closed(CameraDevice("cannot-route-after-dead-loop")))
    check(f.local.closeCount == 0 && f.local.surface.releaseCount == 0 && f.forward.surface.releaseCount == 0)
    HandlerThreadProbe.acknowledgeExpectedLoopFailure(open.handler.looper.owner, expected)
}

/** Actual ownership core additionally exercises same-valued/different-object callbacks without a Handler reroute. */
private fun ownershipOriginalObjectsAndReentrantClose() {
    data class Handle(val scalar: Int)
    val firstDevice = Handle(1); val wrongDevice = Handle(1)
    val firstSession = Handle(2); val wrongSession = Handle(2)
    val devicesClosed = mutableListOf<Handle>(); val sessionsClosed = mutableListOf<Handle>()
    val safe = mutableListOf<CameraOwnershipSnapshot>()
    lateinit var ownership: CameraRunOwnership<Handle, Handle>
    ownership = CameraRunOwnership(
        closeDevice = { devicesClosed.add(it); ownership.deviceClosed(it) },
        closeSession = { sessionsClosed.add(it); ownership.sessionClosed(it) },
        changed = { _, _ -> }, failed = { operation, fault -> error("Unexpected $operation: $fault") },
        safe = { _, snapshot -> safe.add(snapshot) },
    )
    ownership.beforeOpenInvocation(); check(ownership.deviceArrived(firstDevice))
    ownership.beforeConfigurationInvocation(); check(ownership.sessionArrived(firstSession))
    check(!ownership.deviceArrived(wrongDevice))
    check(!ownership.sessionArrived(wrongSession))
    check(ownership.device === firstDevice && ownership.session === firstSession)
    check(!ownership.snapshot().deviceClosedObserved && !ownership.snapshot().sessionClosedObserved)
    ownership.requestStop(); ownership.requestStop()
    check(safe.size == 1 && safe.single().deviceClosedObserved && safe.single().sessionClosedObserved)
    check(devicesClosed.size == 2 && devicesClosed[0] === wrongDevice && devicesClosed[1] === firstDevice)
    check(sessionsClosed.size == 2 && sessionsClosed[0] === wrongSession && sessionsClosed[1] === firstSession)
    check(ownership.snapshot().sessionRetirement == SessionRetirement.SESSION_ON_CLOSED)
    check(ownership.snapshot().deviceCloseCallReturned && ownership.snapshot().sessionCloseCallReturned)
}

// SOURCE ONLY423: 30 prepared actual-adapter/ownership scenarios; this count is not an execution result.
fun main() {
    val cases = listOf<Pair<String, () -> Unit>>(
        "both original targets, capture labels, actual session closure" to ::happyTargetsCaptureAndActualSessionClosure,
        "parent resource fence without synthetic session callback" to ::parentFenceDoesNotInventSessionClosed,
        "stop before open invocation" to ::stopBeforeOpenInvocation,
        "stop before opened callback" to ::stopBeforeOpenedCallback,
        "late configured handle after parent closure" to ::stopBeforeConfiguredAndLateHandleAfterParentClosure,
        "permission invocation remains unresolved" to { invocationExceptionRemainsPending(true) },
        "open invocation exception remains unresolved" to { invocationExceptionRemainsPending(false) },
        "unknown configuration invocation retired by actual original parent fence" to ::unknownConfigurationInvocationRetiredByOriginalParentFence,
        "configure failure separate from session onClosed" to ::configurationFailureIsNotSessionClosed,
        "device onError" to { deviceFailure(true) },
        "device onDisconnected" to { deviceFailure(false) },
        "same tuple successor and original Handler callbacks" to ::sameTupleReplacementKeepsOriginalCallbacks,
        "local stop receipt, loop return and actual joined thread" to ::localStopReceiptIsNotThreadJoin,
        "observer and capture metadata faults" to ::observerFaultsDoNotOwnEndpointsOrStrandThread,
        "target association failure before open" to ::targetAssociationFailureNeverInvokesCameraOrReleasesBorrowedSurfaces,
        "repeating invocation failure is not submission or capture" to ::repeatingInvocationFailureDoesNotReportSubmittedOrCaptured,
        "close exceptions require original callbacks" to ::closeExceptionsRequireOriginalCallbacks,
        "observer reentrant stop before configuration" to ::reentrantObserverStopDoesNotConfigureOrReleaseEndpoints,
        "known ordinary permission rejection with explicit supported-path witness" to ::knownOrdinaryPermissionDenialHasExplicitNoAcceptanceWitness,
        "open throw plus queued original device error" to ::openThrowWithQueuedOriginalDeviceError,
        "configuration throw plus queued original configure failure" to ::configureThrowWithQueuedOriginalConfigureFailed,
        "open callback before catch does not rearm" to ::openCallbackBeforeCatchDoesNotRearmArrival,
        "configuration callback before catch does not rearm" to ::configurationCallbackBeforeCatchDoesNotRearmArrival,
        "known pre-session rejection waits actual owned device fence" to ::knownPreSessionRejectionStillWaitsOwnedDeviceFence,
        "wrong original run evidence stays indeterminate" to { invalidRejectionEvidenceRemainsIndeterminate("run") },
        "wrong operation evidence stays indeterminate" to { invalidRejectionEvidenceRemainsIndeterminate("operation") },
        "different exception object evidence stays indeterminate" to { invalidRejectionEvidenceRemainsIndeterminate("error") },
        "blank supported-path provenance stays indeterminate" to { invalidRejectionEvidenceRemainsIndeterminate("provenance") },
        "abnormal owner loop exit joined without output safety" to ::abnormalOwnerLoopExitIsJoinedWithoutOutputSafety,
        "actual ownership original identity and reentrant closes" to ::ownershipOriginalObjectsAndReentrantClose,
    )
    var primary: Throwable? = null
    try {
        for ((name, scenario) in cases) {
            println("START $name")
            scenario()
            check(HandlerThreadProbe.uncaught.isEmpty()) { "Uncaught owner thread failure: ${HandlerThreadProbe.uncaught}" }
            check(HandlerThreadProbe.threads.none { it.isAlive }) { "Scenario returned before its owner thread closed" }
            println("PASS $name")
        }
        println("PASS ${cases.size} actual-adapter/ownership host scenarios; Camera2/Surface/Handler doubles, no device, encoded-media or optical proof")
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try { HandlerThreadProbe.cleanup() }
        catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
    }
}
