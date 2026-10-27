package com.code2hack.eyeprojector.directrg.localcamera.acquisition

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.ConfigurationCall
import android.hardware.camera2.OpenCall
import android.opengl.GlProbe
import android.os.Handler
import android.os.HandlerThreadProbe
import android.view.Surface
import com.code2hack.eyeprojector.directrg.RetainedVideoOutput
import com.code2hack.eyeprojector.directrg.localcamera.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun jointAwait(signal: CountDownLatch, label: String) {
    check(signal.await(2, TimeUnit.SECONDS)) { "Missing actual-source $label" }
}
private class JointEvents : CameraRunEvents {
    val facts = CopyOnWriteArrayList<CameraOutputsSafe>()
    val stages = CopyOnWriteArrayList<CameraRunObservation>()
    val failures = CopyOnWriteArrayList<CameraRunFailure>()
    val safe = CountDownLatch(1)
    val loopReturned = CountDownLatch(1)
    val joined = CountDownLatch(1)
    var holdLoopReturn: CountDownLatch? = null
    override fun observed(original: OwnedCameraRun, observation: CameraRunObservation) {
        stages.add(observation)
        if (observation.stage == CameraRunStage.OWNER_LOOP_RETURNED) {
            loopReturned.countDown(); holdLoopReturn?.await()
        }
        if (observation.stage == CameraRunStage.OWNER_THREAD_CLOSED) joined.countDown()
    }
    override fun outputsSafe(original: OwnedCameraRun, fact: CameraOutputsSafe) { facts.add(fact); safe.countDown() }
    override fun failed(original: OwnedCameraRun, failure: CameraRunFailure) { failures.add(failure) }
}
private class JointForward(
    override val stream: ForwardCameraStream,
    override val requestedOutput: RequestedCameraOutput,
) : ForwardCameraInputLease {
    val endpoint = Surface("joint-encoder")
    val surfaceReads = AtomicInteger()
    val facts = CopyOnWriteArrayList<CameraOutputsSafe>()
    override val surface: Surface get() { surfaceReads.incrementAndGet(); return endpoint }
    override fun producerStopped(original: CameraRunIdentity, fact: CameraOutputsSafe) {
        check(original === fact.original)
        facts.add(fact)
    }
}

/** Instrumented delegation to the REAL renderer lease, not a model of its retirement. */
private class JointBorrowProbe(val actual: ProducerRetirementInput) : ProducerRetirementInput by actual {
    var reject = false
    var bindReturnedAfter: CountDownLatch? = null
    var stopInsideBind = false
    val bindCalls = AtomicInteger()
    val surfaceReads = AtomicInteger()
    val safeCalls = AtomicInteger()
    val calls = CopyOnWriteArrayList<String>()
    var actualToken: LocalProducerBinding? = null
    override val surface: Surface get() {
        calls.add("surface")
        check(bindCalls.get() == 1 && actualToken != null) { "Borrow before successful original bind" }
        surfaceReads.incrementAndGet()
        return actual.surface
    }
    override fun bindProducer(requestStop: () -> Unit): LocalProducerBinding? {
        bindCalls.incrementAndGet(); calls.add("bind-enter")
        if (reject) return null
        val captured = actual.bindProducer(requestStop) ?: return null
        actualToken = captured
        if (stopInsideBind) requestStop()
        bindReturnedAfter?.let { jointAwait(it, "safe outcome before bind return") }
        calls.add("bind-return")
        return object : LocalProducerBinding {
            override fun outputsSafe() { safeCalls.incrementAndGet(); calls.add("safe"); captured.outputsSafe() }
        }
    }
}
private class JointOutputCompletion {
    val signal = CountDownLatch(1)
    val result = AtomicReference<LocalOutputReleaseResult?>()
    val callbackThread = AtomicReference<Thread?>()
    fun receive(value: LocalOutputReleaseResult) {
        check(result.compareAndSet(null, value)) { "Duplicate output completion" }
        callbackThread.set(Thread.currentThread()); signal.countDown()
    }
    fun await(): LocalOutputReleaseResult { jointAwait(signal, "renderer physical release and join"); return result.get()!! }
}
private class JointFixture {
    val primary = CopyOnWriteArrayList<Exception>()
    private val earlierOwners = HandlerThreadProbe.threads.toSet()
    val output = RetainedVideoOutput({ _, _ -> }, { primary.add(it) })
    val outputThread = HandlerThreadProbe.threads.single {
        it !in earlierOwners && it.name == "eyeprojector-video-output"
    }
    val source = CameraSourceKey(CameraUseKey("joint-original-use", 4), 8)
    val sampling = CameraSampling(1920, 1080)
    val configuration = LocalCameraConfiguration(source, sampling, 960, 540)
    val request = CameraUseDescription(source, "joint-camera", RequestedCameraOutput(960, 540, 34),
        RequestedCameraOutput(1280, 720, 34), sampling, "explicit double fixture; requested, not device support")
    val manager = CameraManager()
    val inputs = mutableListOf<ProducerRetirementInput>()
    val forwards = mutableListOf<JointForward>()
    val runs = mutableListOf<Pair<OwnedCameraRun, JointEvents>>()
    fun input(): ProducerRetirementInput = (output.openLocalCamera(configuration, null) as ProducerRetirementInput)
        .also { inputs.add(it) }
    fun start(input: LocalCameraInput, events: JointEvents = JointEvents()): Triple<OwnedCameraRun, JointEvents, JointForward> {
        val forward = JointForward(ForwardCameraStream(source, 19), request.forwardingOutput).also { forwards.add(it) }
        val run = GlassesCameraProducer(manager).start(request, CameraTargets(input, forward), events)
        runs.add(run to events)
        return Triple(run, events, forward)
    }
    fun closeOutput(): JointOutputCompletion = JointOutputCompletion().also { completion ->
        // Inline callback dispatch deliberately does NOT provide an asynchronous join guarantee.
        (output as RetainedOutputRetirement).closeAsync(Executor { it.run() }, completion::receive)
    }
    /** FIFO barrier on the original retained lane while an unsafe producer keeps it alive. */
    fun pendingOutputBarrier() = Handler(outputThread.looper).drain()
    fun verifyReleased(result: LocalOutputReleaseResult) {
        check(result.workerJoin.disposition == LocalReleaseDisposition.RETURNED && result.workerAlive == false)
        check(result.failures.isEmpty()) { "Unexpected renderer release fault: ${result.failures}" }
        // Actual captured owners must be stopped before failure-only harness cleanup can quit.
        check(!outputThread.isAlive)
        check(runs.all { it.first.ownerThreadClosedObserved })
        check(HandlerThreadProbe.threads.none { it.isAlive })
        check(inputs.all { it.retired && it.releaseResult != null })
        for (input in inputs) {
            check(input.surface.releaseCount == 1 && input.surface.surfaceTexture!!.releaseCount == 1)
        }
        check(forwards.all { it.endpoint.releaseCount == 0 }) { "Camera producer released an encoder-owned endpoint" }
    }
}
private data class JointActive(val run: OwnedCameraRun, val events: JointEvents, val forward: JointForward,
    val open: OpenCall, val device: CameraDevice, val configuration: ConfigurationCall, val session: CameraCaptureSession)
private fun jointConfigure(f: JointFixture, input: LocalCameraInput, events: JointEvents = JointEvents()): JointActive {
    val (run, actualEvents, forward) = f.start(input, events)
    val open = f.manager.awaitOpen(); open.handler.drain()
    val device = CameraDevice("joint-actual-device")
    check(open.opened(device)); open.handler.drain()
    val configuration = device.configurations.single()
    val session = CameraCaptureSession("joint-actual-session")
    check(configuration.configured(session)); open.handler.drain()
    check(configuration.outputs.size == 2 && session.repeating.single().request.targets.size == 2)
    check(configuration.outputs[0] === input.surface && configuration.outputs[1] === forward.endpoint)
    check(session.repeating.single().request.targets[0] === input.surface)
    check(session.repeating.single().request.targets[1] === forward.endpoint)
    check(session.repeating.single().request.tag === run.identity)
    return JointActive(run, actualEvents, forward, open, device, configuration, session)
}
private fun jointScenario(action: (JointFixture) -> Unit) {
    GlProbe.reset()
    val fixture = JointFixture()
    var primary: Throwable? = null
    try { action(fixture) }
    catch (error: Throwable) { primary = error; throw error }
    finally {
        fixture.runs.forEach { it.second.holdLoopReturn?.countDown() }
        try { HandlerThreadProbe.cleanup() }
        catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
    }
    check(HandlerThreadProbe.uncaught.isEmpty())
    check(HandlerThreadProbe.threads.none { it.isAlive })
    check(fixture.primary.isEmpty()) { "Unexpected primary renderer failure: ${fixture.primary}" }
}

private fun jointBindBeforeOpenAndSafeIsNotSessionOrThread() = jointScenario { f ->
    val input = f.input()
    val probe = JointBorrowProbe(input)
    val gate = HandlerThreadProbe.pauseNextStart()
    val events = JointEvents().apply { holdLoopReturn = CountDownLatch(1) }
    val (run, _, forward) = f.start(probe, events)
    check(probe.bindCalls.get() == 1 && probe.surfaceReads.get() == 0 && forward.surfaceReads.get() == 0)
    check(f.manager.opens.isEmpty())
    gate.countDown()
    val open = f.manager.awaitOpen(); open.handler.drain()
    check(probe.calls.indexOf("bind-return") < probe.calls.indexOf("surface"))
    val device = CameraDevice("held-owner-device")
    check(open.opened(device)); open.handler.drain()
    val configuration = device.configurations.single()
    val session = CameraCaptureSession("held-owner-session")
    check(configuration.configured(session)); open.handler.drain()
    check(configuration.outputs.size == 2 && session.repeating.single().request.targets.size == 2)
    check(configuration.outputs[0] === input.surface && configuration.outputs[1] === forward.endpoint)
    check(session.repeating.single().request.targets == configuration.outputs)
    input.close(); open.handler.drain()
    check(input.retired && input.producerFence == LocalProducerFence.PENDING)
    check(input.releaseResult == null && input.surface.releaseCount == 0)
    check(session.closeCount == 1 && device.closeCount == 1)
    check(configuration.closed(session)); open.handler.drain()
    check(run.snapshot.sessionClosedObserved && !run.snapshot.outputsSafe)
    check(input.releaseResult == null && input.surface.releaseCount == 0 && probe.safeCalls.get() == 0)
    check(open.closed(device)); jointAwait(events.safe, "original actual Camera2 outputsSafe")
    jointAwait(events.loopReturned, "producer loop-return while join held")
    check(!run.ownerThreadClosedObserved && run.snapshot.sessionClosedObserved)
    // A renderer barrier processes its already-enqueued fence finalizer while the producer remains alive.
    f.output.currentPhoneTextureLatch()
    check(input.releaseResult?.producerFence == LocalProducerFence.OUTPUTS_SAFE_RECEIVED)
    check(input.surface.releaseCount == 1 && probe.safeCalls.get() == 1)
    check(events.facts.single() === forward.facts.single())
    check(events.facts.single().original === run.identity)
    events.holdLoopReturn!!.countDown(); jointAwait(events.joined, "separate producer join")
    check(run.ownerThreadClosedObserved)
    val caller = Thread.currentThread()
    val completion = f.closeOutput()
    f.verifyReleased(completion.await())
    check(completion.callbackThread.get() !== caller)
    check(completion.callbackThread.get()?.name != "eyeprojector-video-output")
}

private fun jointStopBeforeArrivalsRetainsActualInput() {
    for (configuredPending in listOf(false, true)) jointScenario { f ->
        val input = f.input()
        val (run, events, _) = f.start(JointBorrowProbe(input))
        val open = f.manager.awaitOpen(); open.handler.drain()
        val device = CameraDevice("late-device")
        var configuration: ConfigurationCall? = null
        if (configuredPending) {
            check(open.opened(device)); open.handler.drain()
            configuration = device.configurations.single()
        }
        val completion = f.closeOutput()
        f.pendingOutputBarrier()
        open.handler.drain()
        check(input.retired && input.surface.releaseCount == 0 && input.releaseResult == null)
        check(completion.signal.count == 1L && !run.snapshot.outputsSafe)
        check(f.outputThread.isAlive)
        if (configuration == null) {
            check(open.opened(device)); open.handler.drain()
            check(device.configurations.isEmpty())
        } else {
            val late = CameraCaptureSession("late-configured")
            check(configuration.configured(late)); open.handler.drain()
            check(late.closeCount == 1 && late.repeating.isEmpty())
        }
        check(device.closeCount == 1 && input.surface.releaseCount == 0)
        check(open.closed(device)); jointAwait(events.safe, "original parent fence after late arrival")
        jointAwait(events.joined, "late-arrival owner join")
        val result = completion.await()
        f.verifyReleased(result)
        check(!run.snapshot.sessionClosedObserved) { "Parent fence cannot invent session onClosed" }
        check(events.facts.single().ownership.deviceClosedObserved)
    }
}

private fun jointSameTupleParentCloseWaitsEveryOriginal() {
    for (oldFirst in listOf(true, false)) jointScenario { f ->
        val old = f.input()
        val oldRun = jointConfigure(f, JointBorrowProbe(old))
        val next = f.input()
        f.pendingOutputBarrier()
        oldRun.open.handler.drain()
        check(old.retired && old.releaseResult == null && old.surface.releaseCount == 0)
        check(oldRun.device.closeCount == 1 && oldRun.session.closeCount == 1)
        val nextRun = jointConfigure(f, JointBorrowProbe(next))
        check(old.configuration == next.configuration && old !== next && oldRun.run.identity !== nextRun.run.identity)
        val completion = f.closeOutput()
        f.pendingOutputBarrier()
        nextRun.open.handler.drain()
        check(next.retired && next.surface.releaseCount == 0 && old.surface.releaseCount == 0)
        val first = if (oldFirst) oldRun else nextRun
        val second = if (oldFirst) nextRun else oldRun
        check(first.open.closed(first.device)); jointAwait(first.events.joined, "first original producer join")
        check(completion.signal.count == 1L) { "One original safe cannot finalize the whole output" }
        check(second.run.snapshot.outputsSafe.not())
        val pending = if (oldFirst) next else old
        check(pending.releaseResult == null && pending.surface.releaseCount == 0)
        check(second.open.closed(second.device)); jointAwait(second.events.joined, "second original producer join")
        val result = completion.await(); f.verifyReleased(result)
        check(result.inputs.size == 2 && result.inputs.map { it.input }.toSet() == setOf(old, next))
        check(oldRun.forward.facts.single().original === oldRun.run.identity)
        check(nextRun.forward.facts.single().original === nextRun.run.identity)
        check(!oldRun.configuration.closed(oldRun.session)) { "Late old callback cannot move to a successor Handler" }
    }
}

private fun jointBindingRejectionNeverBorrows() {
    for (mode in listOf("retired", "bound", "missing", "explicit-null")) jointScenario { f ->
        val actual = f.input()
        var prior: LocalProducerBinding? = null
        if (mode == "retired") actual.close()
        if (mode == "bound") prior = actual.bindProducer { }
        val probe = JointBorrowProbe(actual).apply { reject = mode == "explicit-null" }
        val local: LocalCameraInput = if (mode == "missing") object : LocalCameraInput by actual {
            override val surface: Surface get() = error("Missing-capability input was borrowed")
        } else probe
        val (run, events, forward) = f.start(local)
        jointAwait(events.joined, "binding-rejected run join")
        check(!run.snapshot.openInvoked && run.snapshot.outputsSafe && f.manager.opens.isEmpty())
        check(probe.surfaceReads.get() == 0 && forward.surfaceReads.get() == 0)
        check(events.facts.single().fence == CameraOutputFence.NO_OPEN_INVOKED)
        check(events.failures.isNotEmpty())
        prior?.outputsSafe()
        val completion = f.closeOutput(); f.verifyReleased(completion.await())
    }
}

private fun jointActualSafeBeforeBindingPublicationDeliveredOnce() = jointScenario { f ->
    val actual = f.input()
    val events = JointEvents()
    val probe = JointBorrowProbe(actual).apply { stopInsideBind = true; bindReturnedAfter = events.safe }
    val (run, _, forward) = f.start(probe, events)
    jointAwait(events.joined, "no-open original producer join")
    check(run.snapshot.outputsSafe && !run.snapshot.openInvoked && f.manager.opens.isEmpty())
    check(probe.surfaceReads.get() == 0 && forward.surfaceReads.get() == 0)
    check(probe.safeCalls.get() == 1 && events.facts.size == 1)
    check(actual.producerFence == LocalProducerFence.OUTPUTS_SAFE_RECEIVED)
    check(!actual.retired && actual.releaseResult == null && actual.surface.releaseCount == 0)
    check(events.facts.single().fence == CameraOutputFence.NO_OPEN_INVOKED)
    val completion = f.closeOutput(); f.verifyReleased(completion.await())
}

private fun jointNativeCloseFailuresStillRequireActualOriginalFence() = jointScenario { f ->
    val input = f.input()
    val active = jointConfigure(f, JointBorrowProbe(input))
    active.session.closeException = IllegalStateException("explicit fake session close fault")
    active.device.closeException = IllegalStateException("explicit fake device close fault")
    val completion = f.closeOutput(); f.pendingOutputBarrier(); active.open.handler.drain()
    check(active.session.closeCount == 1 && active.device.closeCount == 1)
    check(!active.run.snapshot.outputsSafe && input.surface.releaseCount == 0 && completion.signal.count == 1L)
    check(active.events.failures.size >= 2)
    check(active.open.closed(active.device)); jointAwait(active.events.joined, "actual fence after failed close calls")
    check(active.events.facts.single().ownership.deviceClosedObserved)
    check(!active.events.facts.single().ownership.sessionClosedObserved)
    f.verifyReleased(completion.await())
}

/** Runs the unchanged old30 bodies first, then the new joint actual-source controls. */
object JointRetirement439Main {
    @JvmStatic fun main(args: Array<String>) {
        com.code2hack.eyeprojector.directrg.localcamera.acquisition.main()
        val cases = listOf(
            "bind before open/borrow, both outputs, actual safe versus session and producer join" to ::jointBindBeforeOpenAndSafeIsNotSessionOrThread,
            "parent close before actual original open/configure arrivals" to ::jointStopBeforeArrivalsRetainsActualInput,
            "same tuple parent waits all original leases in either safe order" to ::jointSameTupleParentCloseWaitsEveryOriginal,
            "rejected/missing/already-bound original inputs never borrow" to ::jointBindingRejectionNeverBorrows,
            "actual no-open safe races binding publication exactly once" to ::jointActualSafeBeforeBindingPublicationDeliveredOnce,
            "native close call faults do not manufacture an output-use fence" to ::jointNativeCloseFailuresStillRequireActualOriginalFence,
        )
        for ((label, run) in cases) { println("START439 $label"); run(); println("PASS439 $label") }
        println("PASS439 ${cases.size} joint actual renderer/Camera2 producer groups with explicit doubles; no live EGL/Camera2, native quiescence, media forwarding or Phone capture proof")
    }
}
