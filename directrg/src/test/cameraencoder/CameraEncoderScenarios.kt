package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.ConfigurationCall
import android.hardware.camera2.OpenCall
import android.media.CodecOutput
import android.media.CodecProbe
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.HandlerThreadProbe
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.*
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.*
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private fun await471(signal: CountDownLatch, name: String) {
    check(signal.await(2, TimeUnit.SECONDS)) { "Missing $name" }
}
private fun <T> CompletableFuture<T>.await471(): T = get(2, TimeUnit.SECONDS)
private fun <T> LinkedBlockingQueue<T>.take471(name: String): T =
    checkNotNull(poll(2, TimeUnit.SECONDS)) { "Missing $name" }
private fun equal471(expected: Any?, actual: Any?, name: String) {
    check(expected == actual) { "$name: expected=$expected actual=$actual" }
}
private fun bytes471(expected: List<Int>, actual: EncodedByteSnapshot, name: String) =
    equal471(expected, actual.copyBytes().map { it.toInt() and 255 }, name)
private val inline471 = Executor { it.run() }
private val source471 = CameraSourceKey(CameraUseKey("encoder-original-projection471", 7), 11)
private fun request471() = SurfaceEncoderRequest(ForwardCameraStream(source471, 17),
    RequestedCameraOutput(640, 480, 34), bitRate = 2_000_000, frameRate = 30, iFrameIntervalSeconds = 2)

private class EncoderEvents471 : SurfaceEncoderEvents {
    val prepared = LinkedBlockingQueue<EncoderPreparedObservation>()
    val formats = LinkedBlockingQueue<EncoderFormatObservation>()
    val packets = LinkedBlockingQueue<EncoderAccessUnit>()
    val failures = LinkedBlockingQueue<EncoderFailure>()
    val snapshots = LinkedBlockingQueue<EncoderLifetimeSnapshot>()
    val allPackets = CopyOnWriteArrayList<EncoderAccessUnit>()
    val allFailures = CopyOnWriteArrayList<EncoderFailure>()
    var onPacket: (EncoderAccessUnit) -> Unit = { }
    var onObserved: (EncoderLifetimeSnapshot) -> Unit = { }
    var holdOwnerReturn: CountDownLatch? = null
    val ownerReturnEntered = CountDownLatch(1)
    private val ownerReturnClaimed = AtomicBoolean()
    override fun prepared(original: CameraSurfaceEncoder, observation: EncoderPreparedObservation) { prepared.put(observation) }
    override fun format(original: CameraSurfaceEncoder, observation: EncoderFormatObservation) { formats.put(observation) }
    override fun packet(original: CameraSurfaceEncoder, packet: EncoderAccessUnit) {
        allPackets.add(packet); packets.put(packet); onPacket(packet)
    }
    override fun failed(original: CameraSurfaceEncoder, failure: EncoderFailure) {
        allFailures.add(failure); failures.put(failure)
    }
    override fun observed(original: CameraSurfaceEncoder, snapshot: EncoderLifetimeSnapshot) {
        snapshots.put(snapshot)
        onObserved(snapshot)
        if (snapshot.ownerLoopReturned && !snapshot.joinOutcomeRecorded && ownerReturnClaimed.compareAndSet(false, true)) {
            ownerReturnEntered.countDown(); holdOwnerReturn?.await()
        }
    }
}

/** This local endpoint is a declared boundary fixture; actual renderer tests live separately. */
private class EncoderLocal471(override val configuration: LocalCameraConfiguration) : ProducerRetirementInput {
    val endpoint = Surface("encoder-local471")
    val surfaceReads = AtomicInteger()
    val bindCalls = AtomicInteger()
    val safeCalls = AtomicInteger()
    private var bound = false
    override val surface: Surface get() { surfaceReads.incrementAndGet(); return endpoint }
    override var retired = false
    override val lastFailure: LocalCameraFailure? = null
    override var producerFence = LocalProducerFence.NOT_BOUND_LEGACY
        private set
    override val releaseResult: LocalInputReleaseResult? = null
    override fun bindProducer(requestStop: () -> Unit): LocalProducerBinding? {
        bindCalls.incrementAndGet()
        if (retired || bound) return null
        bound = true; producerFence = LocalProducerFence.PENDING
        return object : LocalProducerBinding {
            override fun outputsSafe() { safeCalls.incrementAndGet(); producerFence = LocalProducerFence.OUTPUTS_SAFE_RECEIVED }
        }
    }
    override fun close() { retired = true }
    override fun closeAsync(completionExecutor: Executor, onRelease: (LocalInputReleaseResult) -> Unit) {
        close()
        completionExecutor.execute { onRelease(LocalInputReleaseResult(this, producerFence,
            LocalGlContextDisposition.NOT_NEEDED, emptyMap(), emptyList())) }
    }
}
private class CameraEvents471 : CameraRunEvents {
    val safe = CountDownLatch(1)
    val joined = CountDownLatch(1)
    val facts = CopyOnWriteArrayList<CameraOutputsSafe>()
    val failures = CopyOnWriteArrayList<CameraRunFailure>()
    override fun outputsSafe(original: OwnedCameraRun, fact: CameraOutputsSafe) { facts.add(fact); safe.countDown() }
    override fun observed(original: OwnedCameraRun, observation: CameraRunObservation) {
        if (observation.stage == CameraRunStage.OWNER_THREAD_CLOSED) joined.countDown()
    }
    override fun failed(original: OwnedCameraRun, failure: CameraRunFailure) { failures.add(failure) }
}

private class EncoderRig471(label: String = "codec471", val request: SurfaceEncoderRequest = request471()) {
    val events = EncoderEvents471()
    val codec = MediaCodec(label)
    val encoder = CameraSurfaceEncoder(request, events)
    val camera = CameraManager()
    val cameraEvents = CameraEvents471()
    val local = EncoderLocal471(LocalCameraConfiguration(request.stream.source, CameraSampling(1920, 1080), 960, 540))
    val cameraRequest = CameraUseDescription(request.stream.source, "requested-camera471", RequestedCameraOutput(960, 540, 34),
        request.output, local.configuration.sampling, "explicit fixture; no native camera/codec capability proof")
    var run: OwnedCameraRun? = null
    var open: OpenCall? = null
    var device: CameraDevice? = null
    var configuration: ConfigurationCall? = null
    var session: CameraCaptureSession? = null
    fun prepare(): EncoderPreparedObservation {
        CodecProbe.supply(codec)
        val ready = CompletableFuture<EncoderPreparedObservation>()
        encoder.prepareAsync(inline471) { ready.complete(it) }
        return ready.await471()
    }
    fun startCamera(forward: ForwardCameraInputLease = encoder): OwnedCameraRun {
        return GlassesCameraProducer(camera).start(cameraRequest, CameraTargets(local, forward), cameraEvents).also { run = it }
    }
    fun opened(): OpenCall = camera.awaitOpen().also { open = it; it.handler.drain() }
    fun configureCamera() {
        val callback = opened()
        val original = CameraDevice("actual-camera471").also { device = it }
        check(callback.opened(original)); callback.handler.drain()
        val config = original.configurations.single().also { configuration = it }
        val actual = CameraCaptureSession("actual-session471").also { session = it }
        check(config.configured(actual)); callback.handler.drain()
        check(config.outputs.size == 2 && config.outputs[0] === local.endpoint && config.outputs[1] === codec.inputSurface)
        check(actual.repeating.single().request.targets == config.outputs)
        check(actual.repeating.single().request.tag === run!!.identity)
    }
    fun ownership(name: String, predicate: (EncoderLifetimeSnapshot) -> Boolean): EncoderLifetimeSnapshot {
        val current = encoder.snapshot
        if (predicate(current)) return current
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            val next = events.snapshots.poll((until - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: error("Missing encoder ownership observation: $name")
            if (predicate(next)) return next
        }
    }
    fun completion(executor: Executor = inline471): CompletableFuture<EncoderReleaseResult> =
        CompletableFuture<EncoderReleaseResult>().also { result -> encoder.closeAsync(executor) { result.complete(it) } }
    fun resolveActualCameraFence() {
        val original = run ?: return
        if (original.snapshot.outputsSafe) { await471(cameraEvents.joined, "already-fenced Camera2 owner join"); return }
        original.stop("fixture original finish471")
        val callback = open
        if (callback != null) {
            callback.handler.drain()
            var actualDevice = device
            if (actualDevice == null) {
                actualDevice = CameraDevice("late-original-camera471").also { device = it }
                check(callback.opened(actualDevice)); callback.handler.drain()
            }
            check(callback.closed(actualDevice))
        }
        await471(cameraEvents.safe, "actual Camera2 original outputsSafe")
        await471(cameraEvents.joined, "actual Camera2 owner join")
    }
    fun enqueueEos(index: Int = 99) {
        codec.output(CodecOutput.Buffer(index, null, 0, 0, 987_654_321L, MediaCodec.BUFFER_FLAG_END_OF_STREAM))
    }
    fun finish() {
        events.holdOwnerReturn?.countDown()
        val release = completion()
        resolveActualCameraFence()
        enqueueEos()
        val result = release.await471()
        if (result.ownership.workerStarted) {
            equal471(EncoderStepDisposition.RETURNED, result.workerJoin.disposition, "actual encoder join")
            equal471(false, result.workerAlive, "actual encoder owner ended")
            val observedOwner = CodecProbe.calls.firstOrNull { it.codec === codec }?.thread
            if (observedOwner != null) check(!observedOwner.isAlive) { "Recorded native owner survived reported join" }
        } else equal471(EncoderStepDisposition.NOT_ATTEMPTED, result.workerJoin.disposition, "no invented never-started worker join")
        check(local.endpoint.releaseCount == 0) { "Encoder/Camera2 adapter released the separately owned local endpoint" }
    }
}
private fun scenario471(action: (EncoderRig471) -> Unit) {
    CodecProbe.reset()
    val rig = EncoderRig471()
    var primary: Throwable? = null
    try { action(rig) } catch (error: Throwable) { primary = error; throw error }
    finally {
        try { rig.finish() } catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
        try { HandlerThreadProbe.cleanup() } catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
    }
    check(HandlerThreadProbe.uncaught.isEmpty())
}

private fun configureSurfaceStartOrdering471() {
    scenario471 { f ->
    check(CodecProbe.calls.isEmpty()) { "Construction performed codec work before original storage/prepareAsync" }
    val prepared = f.prepare()
    check(prepared.ready && prepared.ownership.prepared)
    check(f.events.formats.isEmpty() && f.events.packets.isEmpty()) { "Endpoint readiness must not require first output/CSD" }
    equal471(listOf("createEncoderByType", "configure", "createInputSurface", "start"),
        CodecProbe.calls.take(4).map { it.operation }, "actual setup ordering")
    val format = checkNotNull(f.codec.configuredFormat)
    equal471(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME), "requested AVC")
    equal471(640, format.getInteger(MediaFormat.KEY_WIDTH), "requested width")
    equal471(480, format.getInteger(MediaFormat.KEY_HEIGHT), "requested height")
    equal471(2_000_000, format.getInteger(MediaFormat.KEY_BIT_RATE), "requested bitrate")
    equal471(30, format.getInteger(MediaFormat.KEY_FRAME_RATE), "requested framerate")
    equal471(2, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL), "requested I-frame interval")
    equal471(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface, format.getInteger(MediaFormat.KEY_COLOR_FORMAT), "Surface input color")
    equal471(MediaCodec.CONFIGURE_FLAG_ENCODE, f.codec.configuredFlags, "encode flag")
    f.startCamera(); f.configureCamera()
    check(f.encoder.surface === f.codec.inputSurface)
    }
    for ((operation, step) in listOf("createEncoderByType" to EncoderOperation.CREATE_CODEC,
        "configure" to EncoderOperation.CONFIGURE, "createInputSurface" to EncoderOperation.CREATE_INPUT_SURFACE,
        "start" to EncoderOperation.START)) scenario471 { f ->
        val failure = IllegalStateException("explicit partial $operation failure471")
        if (step == EncoderOperation.CREATE_CODEC) CodecProbe.createFailure = failure else f.codec.failNext(operation, failure)
        val prepared = f.prepare()
        check(!prepared.ready)
        val result = f.completion().await471()
        equal471(EncoderStepDisposition.FAILED, result.steps.getValue(step).disposition, "actual preparation failure $operation")
        check(result.failures.any { it.error === failure })
        val acquiredCodec = step != EncoderOperation.CREATE_CODEC
        equal471(if (acquiredCodec) 1 else 0,
            CodecProbe.calls.count { it.codec === f.codec && it.operation == "release" }, "release only acquired codec after $operation")
        val acquiredSurface = step == EncoderOperation.START
        equal471(if (acquiredSurface) 1 else 0, f.codec.surfaces.sumOf { it.releaseCount }, "release only acquired input Surface after $operation")
        if (!acquiredSurface) equal471(EncoderStepDisposition.NOT_ATTEMPTED,
            result.steps.getValue(EncoderOperation.INPUT_SURFACE_RELEASE).disposition, "unacquired Surface finalizer not attempted")
    }
}

private fun exactOutputCopyBeforeRecycle471() = scenario471 { f ->
    check(f.prepare().ready)
    val backing = ByteBuffer.wrap(byteArrayOf(91, 92, 93, 10, 20, 30, 40, 94, 95, 96))
    backing.position(7)
    val output = CodecOutput.Buffer(7, backing, 3, 4, -123_456_789L, MediaCodec.BUFFER_FLAG_KEY_FRAME,
        recycle = { for (i in 0 until backing.capacity()) backing.put(i, 99) })
    f.codec.output(output)
    val packet = f.events.packets.take471("actual copied output occurrence")
    await471(f.codec.settled("releaseOutputBuffer"), "native output release settlement")
    bytes471(listOf(10, 20, 30, 40), packet.bytes, "BufferInfo range survives release recycling")
    equal471(-123_456_789L, packet.presentationTimeUs, "raw PTS preserved")
    equal471("ENCODER_PRESENTATION_TIME_US_SOURCE_UNVERIFIED", packet.timestampClockDomain, "PTS qualification")
    equal471(null, packet.formatGeneration, "no invented prior format")
    val released = CodecProbe.bufferReleases.single()
    check(released.codec === f.codec && released.index == 7 && released.occurrence == output.occurrence && !released.render)
    val copy = packet.bytes.copyBytes(); copy[0] = 77
    bytes471(listOf(10, 20, 30, 40), packet.bytes, "consumer cannot mutate snapshot")
}

private fun csdAndObservedFormatSnapshot471() = scenario471 { f ->
    check(f.prepare().ready)
    val writable = ByteBuffer.wrap(byteArrayOf(90, 1, 2, 3, 91)).apply { position(1); limit(4) }
    val readOnly = writable.asReadOnlyBuffer()
    val original = MediaFormat.createVideoFormat("video/avc", 642, 482).apply {
        setByteBuffer("csd-0", readOnly); setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(4, 5)))
    }
    f.codec.output(CodecOutput.Format(original))
    val format = f.events.formats.take471("actual output format")
    equal471(642, format.observedWidth, "observed width differs from request")
    equal471(482, format.observedHeight, "observed height differs from request")
    equal471(f.codec.name, format.actualCodecName, "observed codec name")
    equal471(1, writable.position(), "CSD source position unchanged")
    equal471(1, readOnly.position(), "actual native read-only buffer position unchanged")
    equal471(4, readOnly.limit(), "actual native CSD limit unchanged")
    readOnly.position(3)
    writable.put(1, 88); original.setInteger(MediaFormat.KEY_WIDTH, 999)
    original.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(77)))
    bytes471(listOf(1, 2, 3), format.csd.getValue("csd-0"), "read-only CSD copied")
    bytes471(listOf(4, 5), format.csd.getValue("csd-1"), "CSD replacement cannot change observation")
    equal471(642, format.observedWidth, "format detached from mutable native object")
}

private fun zeroConfigKeyAndEosOccurrences471() = scenario471 { f ->
    check(f.prepare().ready)
    val records = listOf(
        CodecOutput.Buffer(3, ByteBuffer.allocate(0), 0, 0, 10, 0),
        CodecOutput.Buffer(3, ByteBuffer.wrap(byteArrayOf(1, 2)), 0, 2, 11, MediaCodec.BUFFER_FLAG_CODEC_CONFIG),
        CodecOutput.Buffer(3, ByteBuffer.wrap(byteArrayOf(3, 4)), 0, 2, 12, MediaCodec.BUFFER_FLAG_KEY_FRAME),
        CodecOutput.Buffer(3, null, 0, 0, 13, MediaCodec.BUFFER_FLAG_END_OF_STREAM),
    )
    records.forEach(f.codec::output)
    val packets = records.map { f.events.packets.take471("distinct raw output occurrence") }
    equal471(listOf(0, 2, 2, 0), packets.map { it.bytes.size }, "zero byte and nonzero occurrences preserved")
    equal471(records.map { it.flags }, packets.map { it.codecFlags }, "raw config/key/EOS flags preserved")
    equal471(listOf(10L, 11L, 12L, 13L), packets.map { it.presentationTimeUs }, "raw PTS occurrences")
    check(packets.zipWithNext().all { (a, b) -> b.sequence > a.sequence })
    f.ownership("all native output occurrences released") { it.outputBufferReleaseReturns == 4L }
    equal471(records.map { it.occurrence }, CodecProbe.bufferReleases.map { it.occurrence }, "same numeric index has distinct exact releases")
    equal471(0, CodecProbe.calls.count { it.operation == "signalEndOfInputStream" }, "no synthetic signal for never-bound output EOS")
}

/** Observe adapter borrowing; delegate all actual encoder ownership to production. */
private class ForwardProbe471(val encoder: CameraSurfaceEncoder) : ProducerBoundForwardCameraInputLease by encoder {
    val surfaceReads = AtomicInteger()
    val binds = CopyOnWriteArrayList<CameraRunIdentity>()
    var onBound: (CameraRunIdentity, Boolean) -> Unit = { _, _ -> }
    override val surface: Surface get() {
        check(encoder.snapshot.originalProducer != null) { "Surface borrowed before original forward binding" }
        surfaceReads.incrementAndGet(); return encoder.surface
    }
    override fun bindProducer(original: CameraRunIdentity, requestStop: () -> Unit): Boolean {
        binds.add(original)
        val accepted = encoder.bindProducer(original, requestStop)
        onBound(original, accepted)
        return accepted
    }
}

private fun oneOriginalBindingRejectsEqualTupleRebind471() = scenario471 { f ->
    check(f.prepare().ready)
    f.startCamera(); f.configureCamera()
    val original = f.run!!
    val nextLocal = EncoderLocal471(f.local.configuration)
    val nextEvents = CameraEvents471()
    val probe = ForwardProbe471(f.encoder)
    val next = GlassesCameraProducer(f.camera).start(f.cameraRequest, CameraTargets(nextLocal, probe), nextEvents)
    await471(nextEvents.joined, "rejected equal-tuple successor join")
    check(next.request == original.request && next.identity !== original.identity)
    check(!next.snapshot.openInvoked && next.snapshot.outputsSafe)
    equal471(1, f.camera.opens.size, "only original Camera2 open")
    equal471(0, probe.surfaceReads.get(), "rebound forwarding Surface never borrowed")
    equal471(0, nextLocal.surfaceReads.get(), "rejected new local endpoint never borrowed")
    equal471(0, nextLocal.bindCalls.get(), "forward binding happens before local binding")
    check(f.encoder.snapshot.originalProducer === original.identity)
    equal471(EncoderProducerFence.PENDING, f.encoder.snapshot.producerFence, "rejected successor's no-open fence cannot resolve original")
}

private fun retiredForwardNeverBorrowsOrOpens471() = scenario471 { f ->
    check(f.prepare().ready)
    val closed = f.completion(); closed.await471()
    val probe = ForwardProbe471(f.encoder)
    val run = f.startCamera(probe)
    await471(f.cameraEvents.joined, "retired forwarding rejection join")
    check(!run.snapshot.openInvoked && run.snapshot.outputsSafe && f.camera.opens.isEmpty())
    equal471(0, probe.surfaceReads.get(), "retired encoder never borrowed")
    equal471(0, f.local.bindCalls.get(), "retired forward rejected before local registration")
    equal471(0, f.local.surfaceReads.get(), "retired forward never borrows local Surface")
    equal471(1, f.codec.inputSurface.releaseCount, "already-closed endpoint not released again")
}

private fun pendingOpenAndConfigureHoldEncoder471() {
    for (pendingConfigure in listOf(false, true)) scenario471 { f ->
        check(f.prepare().ready)
        val eosCall = f.codec.holdNext("signalEndOfInputStream")
        try {
        f.startCamera()
        val callback = f.opened()
        if (pendingConfigure) {
            val device = CameraDevice("pending-configuration471").also { f.device = it }
            check(callback.opened(device)); callback.handler.drain()
            f.configuration = device.configurations.single()
        }
        val complete = f.completion()
        f.ownership("original stop submission returned") {
            it.steps.getValue(EncoderOperation.PRODUCER_STOP).disposition == EncoderStepDisposition.RETURNED
        }
        callback.handler.drain()
        check(!complete.isDone && f.encoder.snapshot.producerFence == EncoderProducerFence.PENDING)
        equal471(0, f.codec.inputSurface.releaseCount, "pending Camera2 use retains input Surface")
        equal471(0, CodecProbe.calls.count { it.codec === f.codec && it.operation in listOf("signalEndOfInputStream", "stop", "release") },
            "no encoder native close before actual original fence")
        if (pendingConfigure) {
            val late = CameraCaptureSession("late-configured471").also { f.session = it }
            check(f.configuration!!.configured(late)); callback.handler.drain()
            check(late.closeCount == 1 && late.repeating.isEmpty())
            check(!complete.isDone && f.codec.inputSurface.releaseCount == 0)
        }
        f.resolveActualCameraFence()
        await471(eosCall.entered, "held actual EOS invocation after original fence")
        equal471(EncoderStepDisposition.CLAIMED, f.encoder.snapshot.steps.getValue(EncoderOperation.SIGNAL_EOS).disposition,
            "actual native EOS return unresolved")
        check(!f.encoder.snapshot.eosObserved && !complete.isDone)
        f.enqueueEos(); eosCall.release.countDown(); complete.await471()
        check(f.encoder.snapshot.producerOutputsSafe === f.cameraEvents.facts.single())
        check(f.encoder.snapshot.eosObserved)
        } finally { eosCall.release.countDown() }
    }
}

private fun genuineNoOpenFenceOnlyOriginal471() = scenario471 { f ->
    check(f.prepare().ready)
    val gate = HandlerThreadProbe.pauseNextStart()
    val eosCall = f.codec.holdNext("signalEndOfInputStream")
    try {
        val original = f.startCamera()
        val completion = f.completion()
        f.ownership("retirement claimed before open") { it.retirementRequested }
        check(!original.snapshot.openInvoked && !completion.isDone && f.codec.inputSurface.releaseCount == 0)
        gate.countDown()
        await471(f.cameraEvents.safe, "genuine original no-open fence")
        await471(f.cameraEvents.joined, "no-open Camera2 join")
        val fact = f.cameraEvents.facts.single()
        check(fact.original === original.identity && fact.fence == CameraOutputFence.NO_OPEN_INVOKED)
        check(f.camera.opens.isEmpty())
        await471(eosCall.entered, "held native EOS signal after no-open fence")
        check(!completion.isDone && !f.encoder.snapshot.eosObserved)
        f.enqueueEos(); eosCall.release.countDown(); completion.await471()
        check(f.encoder.snapshot.producerOutputsSafe === fact)
        check(f.encoder.snapshot.eosObserved)
    } finally { gate.countDown(); eosCall.release.countDown() }
}

private fun wrongAndStaleActualFencesCannotRetireSuccessor471() = scenario471 { f ->
    check(f.prepare().ready)
    f.startCamera(); f.configureCamera()
    val other = EncoderRig471("other-original471", f.request)
    try {
        check(other.prepare().ready)
        val gate = HandlerThreadProbe.pauseNextStart()
        try {
            other.startCamera(); other.encoder.stop("known no-open other471"); gate.countDown()
            await471(other.cameraEvents.safe, "other original actual no-open fence")
        } finally { gate.countDown() }
        val wrong = other.cameraEvents.facts.single()
        f.encoder.producerStopped(f.run!!.identity, wrong)
        f.encoder.producerStopped(other.run!!.identity, wrong)
        check(f.encoder.snapshot.originalProducer === f.run!!.identity)
        equal471(EncoderProducerFence.PENDING, f.encoder.snapshot.producerFence, "wrong fact and wrong original both ignored")
        check(!f.encoder.snapshot.retirementRequested)
        equal471(0, f.codec.inputSurface.releaseCount, "wrong safe cannot release current endpoint")
        other.enqueueEos()
    } finally { other.finish() }
}

private fun eosSignalFailureKeepsActualFenceAndIndependentCleanup471() = scenario471 { f ->
    check(f.prepare().ready)
    f.startCamera(); f.configureCamera()
    val failure = IllegalStateException("explicit EOS signal failure471")
    f.codec.failNext("signalEndOfInputStream", failure)
    val completed = f.completion()
    f.resolveActualCameraFence()
    val result = completed.await471()
    check(result.producerOutputsSafe === f.cameraEvents.facts.single())
    check(!result.ownership.eosObserved)
    equal471(EncoderStepDisposition.FAILED, result.steps.getValue(EncoderOperation.SIGNAL_EOS).disposition, "EOS signal failure")
    for (step in listOf(EncoderOperation.CODEC_STOP, EncoderOperation.CODEC_RELEASE, EncoderOperation.INPUT_SURFACE_RELEASE))
        equal471(EncoderStepDisposition.RETURNED, result.steps.getValue(step).disposition, "independent $step after signal fault")
    check(result.failures.any { it.error === failure })
    equal471(1, f.codec.inputSurface.releaseCount, "safe endpoint cleanup still once")
}

private fun eachPhysicalFailureAttemptedOnce471() {
    scenario471 { f ->
    check(f.prepare().ready)
    val stop = IllegalStateException("codec stop fault471")
    val release = IllegalStateException("codec release fault471")
    val surface = IllegalStateException("Surface release fault471")
    f.codec.failNext("stop", stop); f.codec.failNext("release", release)
    f.codec.inputSurface.releaseFailure = surface
    val result = f.completion().await471()
    for (step in listOf(EncoderOperation.CODEC_STOP, EncoderOperation.CODEC_RELEASE, EncoderOperation.INPUT_SURFACE_RELEASE))
        equal471(EncoderStepDisposition.FAILED, result.steps.getValue(step).disposition, "$step failed independently")
    check(result.failures.any { it.error === stop } && result.failures.any { it.error === release } && result.failures.any { it.error === surface })
    check(result.ownership.primaryFailure!!.error === stop)
    check(stop.suppressed.any { it === release } && stop.suppressed.any { it === surface })
    equal471(1, f.codec.inputSurface.releaseCount, "failed Surface release attempted once")
    equal471(0, f.codec.inputSurface.releaseReturnedCount, "failure is not a returned Surface release")
    f.encoder.stop("duplicate failed cleanup471"); f.completion().await471()
    for (operation in listOf("stop", "release"))
        equal471(1, CodecProbe.calls.count { it.codec === f.codec && it.operation == operation }, "failed $operation never retried")
    equal471(1, f.codec.inputSurface.releaseCount, "failed Surface release never retried")
    }
    scenario471 { f ->
        check(f.prepare().ready)
        val failure = IllegalStateException("output buffer release failed471")
        f.codec.failNext("releaseOutputBuffer", failure)
        f.codec.output(CodecOutput.Buffer(7, ByteBuffer.wrap(byteArrayOf(4)), 0, 1, 78, 0))
        f.ownership("failed output release and drain independently returned") { it.outputBufferReleaseFailures == 1L && it.drainLoopReturned }
        val result = f.completion().await471()
        equal471(1L, result.ownership.outputBufferReleaseClaims, "one output occurrence release claim")
        equal471(0L, result.ownership.outputBufferReleaseReturns, "failed native release is not returned")
        val outstanding = result.ownership.outstandingOutputBuffers.single()
        equal471(7, outstanding.buffer.bufferIndex, "original failed buffer index")
        equal471(EncoderStepDisposition.FAILED, outstanding.release.disposition, "failed occurrence retained honestly")
        check(result.failures.any { it.error === failure })
        equal471(EncoderStepDisposition.RETURNED, result.steps.getValue(EncoderOperation.CODEC_RELEASE).disposition,
            "independent codec release despite drain/buffer failure")
        f.completion().await471()
        equal471(1, CodecProbe.calls.count { it.codec === f.codec && it.operation == "releaseOutputBuffer" }, "failed output occurrence never retried")
    }
}

private fun throwingPacketObserverRetainsUntilSafe471() = scenario471 { f ->
    check(f.prepare().ready)
    f.startCamera(); f.configureCamera()
    val thrown = AssertionError("packet observer fault471")
    f.events.onPacket = { throw thrown }
    f.codec.output(CodecOutput.Buffer(4, ByteBuffer.wrap(byteArrayOf(8)), 0, 1, 17, 0))
    f.events.packets.take471("packet before observer fault")
    f.ownership("observer failure stops original producer") {
        it.retirementRequested && it.steps.getValue(EncoderOperation.PRODUCER_STOP).disposition == EncoderStepDisposition.RETURNED
    }
    f.open!!.handler.drain()
    check(f.encoder.snapshot.failures.any { it.error === thrown })
    check(f.encoder.snapshot.producerFence == EncoderProducerFence.PENDING)
    check(f.codec.inputSurface.releaseCount == 0 && f.device!!.closeCount == 1)
    await471(f.codec.settled("releaseOutputBuffer"), "observer failure still releases original output occurrence")
    equal471(1, CodecProbe.bufferReleases.count { it.codec === f.codec && it.index == 4 }, "output buffer released once despite observer fault")
}

private fun inlineAndReentrantCompletionNeverSelfJoins471() = scenario471 { f ->
    check(f.prepare().ready)
    f.startCamera(); f.configureCamera()
    val done = CompletableFuture<EncoderReleaseResult>()
    val duplicate = CompletableFuture<EncoderReleaseResult>()
    var packetThread: Thread? = null
    var callbackThread: Thread? = null
    val once = AtomicBoolean()
    f.events.onPacket = {
        if (once.compareAndSet(false, true)) {
            packetThread = Thread.currentThread()
            f.encoder.closeAsync(inline471) { result ->
                callbackThread = Thread.currentThread()
                f.encoder.closeAsync(inline471) { duplicate.complete(it) }
                done.complete(result)
            }
        }
    }
    f.codec.output(CodecOutput.Buffer(1, ByteBuffer.wrap(byteArrayOf(1)), 0, 1, 18, 0))
    f.events.packets.take471("reentrant packet callback")
    f.ownership("reentrant close submission") { it.retirementRequested }
    check(!done.isDone && f.codec.inputSurface.releaseCount == 0)
    f.resolveActualCameraFence(); f.enqueueEos()
    val result = done.await471(); duplicate.await471()
    check(callbackThread !== packetThread && callbackThread !== Thread.currentThread())
    equal471(EncoderStepDisposition.RETURNED, result.workerJoin.disposition, "actual off-codec-lane join")
    equal471(false, result.workerAlive, "callback follows actual thread termination")
    equal471(1, f.codec.inputSurface.releaseCount, "reentrant completion does not double-release")
}

private fun rejectedCompletionDoesNotInventDelivery471() = scenario471 { f ->
    check(f.prepare().ready)
    val rejected = RejectedExecutionException("completion dispatch rejected471")
    var delivered = false
    f.encoder.closeAsync(Executor { throw rejected }) { delivered = true }
    val result = f.completion().await471()
    check(!delivered)
    equal471(EncoderStepDisposition.RETURNED, result.workerJoin.disposition, "native closure independent of rejected callback")
    check(f.encoder.snapshot.failures.any { it.error === rejected })
    equal471(1, f.codec.inputSurface.releaseCount, "dispatch failure does not retry cleanup")
}

private fun nativePendingLoopReturnAndJoinAreSeparate471() = scenario471 { f ->
    check(f.prepare().ready)
    val native = f.codec.holdNext("stop")
    val ownerReturn = CountDownLatch(1)
    f.events.holdOwnerReturn = ownerReturn
    try {
        val result = f.completion()
        await471(native.entered, "explicit native stop invocation held")
        equal471(EncoderStepDisposition.CLAIMED, f.encoder.snapshot.steps.getValue(EncoderOperation.CODEC_STOP).disposition,
            "once claim while actual native return unresolved")
        check(!result.isDone && !f.encoder.snapshot.joinOutcomeRecorded)
        native.release.countDown()
        await471(f.events.ownerReturnEntered, "actual owner-loop return observation held")
        val observed = f.encoder.snapshot
        check(observed.ownerLoopReturned && observed.ownerAliveAtLoopReturn == true)
        check(!observed.workerThreadClosedObserved && !observed.joinOutcomeRecorded && !result.isDone)
        val actualOwner = CodecProbe.calls.first { it.codec === f.codec && it.operation == "configure" }.thread
        check(actualOwner.isAlive) { "Held owner-loop callback must precede actual Thread termination" }
        ownerReturn.countDown()
        val released = result.await471()
        check(released.ownership.workerThreadClosedObserved)
        equal471(false, released.workerAlive, "separate actual joined-thread observation")
        check(!actualOwner.isAlive)
    } finally { native.release.countDown(); ownerReturn.countDown() }
}

private fun equalTupleEncoderObjectsKeepResourcesSeparate471() = scenario471 { f ->
    check(f.prepare().ready)
    f.startCamera(); f.configureCamera()
    val next = EncoderRig471("same-source-successor471", f.request)
    try {
        check(next.prepare().ready)
        next.startCamera(); next.configureCamera()
        check(f.request == next.request && f.encoder !== next.encoder)
        check(f.encoder.snapshot.encoder !== next.encoder.snapshot.encoder)
        val completed = f.completion()
        f.resolveActualCameraFence(); f.enqueueEos(); completed.await471()
        val old = f.cameraEvents.facts.single()
        next.encoder.producerStopped(f.run!!.identity, old)
        check(next.encoder.snapshot.originalProducer === next.run!!.identity)
        equal471(EncoderProducerFence.PENDING, next.encoder.snapshot.producerFence, "stale old completion cannot fence successor")
        equal471(0, next.codec.inputSurface.releaseCount, "same tuple successor endpoint still owned")
        equal471(0, CodecProbe.calls.count { it.codec === next.codec && it.operation in listOf("stop", "release") }, "old close cannot release successor codec")
        equal471(1, f.codec.inputSurface.releaseCount, "only old endpoint released")
    } finally { next.finish() }
}

private fun forwardRetirementDuringBindKeepsCachedNoOpenFence471() = scenario471 { f ->
    check(f.prepare().ready)
    val probe = ForwardProbe471(f.encoder)
    probe.onBound = { _, accepted ->
        check(accepted)
        equal471(0, f.local.bindCalls.get(), "forward producer claim precedes any local bind")
        f.encoder.stop("retired inside original forward bind471")
        f.ownership("actual no-open fence before forward bind returns") { it.producerOutputsSafe != null }
    }
    val original = f.startCamera(probe)
    await471(f.cameraEvents.joined, "early actual no-open Camera2 owner join")
    check(!original.snapshot.openInvoked && f.camera.opens.isEmpty())
    equal471(0, probe.surfaceReads.get(), "forward retire before any Surface borrow")
    equal471(0, f.local.surfaceReads.get(), "early stop before local Surface borrow")
    equal471(1, f.local.bindCalls.get(), "original local binding still installed")
    equal471(1, f.local.safeCalls.get(), "existing cached local fence delivered exactly once")
    check(f.encoder.snapshot.producerOutputsSafe === f.cameraEvents.facts.single())
    check(f.encoder.snapshot.producerOutputsSafe!!.original === original.identity)
    f.enqueueEos(); f.completion().await471()
}

private fun throwingForwardBindAfterClaimRetainsActualDuty471() = scenario471 { f ->
    check(f.prepare().ready)
    val thrown = IllegalStateException("forward bind throws after original claim471")
    val probe = ForwardProbe471(f.encoder).apply { onBound = { _, accepted -> check(accepted); throw thrown } }
    val original = f.startCamera(probe)
    await471(f.cameraEvents.safe, "actual no-open fence after throwing original bind")
    await471(f.cameraEvents.joined, "rejected binding original Camera2 join")
    check(f.encoder.snapshot.originalProducer === original.identity)
    check(f.encoder.snapshot.producerOutputsSafe === f.cameraEvents.facts.single())
    check(f.cameraEvents.facts.single().fence == CameraOutputFence.NO_OPEN_INVOKED)
    check(f.cameraEvents.failures.any { it.error === thrown })
    check(f.camera.opens.isEmpty() && probe.surfaceReads.get() == 0 && f.local.surfaceReads.get() == 0)
    f.enqueueEos(); f.completion().await471()
}

/** One actual-source JVM: unchanged historical case bodies, then the new encoder boundary cases. */
object CameraEncoder471Scenarios {
    @JvmStatic fun main(args: Array<String>) {
        com.code2hack.eyeprojector.directrg.localcamera.acquisition.main()
        val cases = listOf(
            "configure/createInputSurface/start ordering and original targets" to ::configureSurfaceStartOrdering471,
            "BufferInfo offset/size copied before native recycle" to ::exactOutputCopyBeforeRecycle471,
            "read-only CSD and observed format detached from mutable source" to ::csdAndObservedFormatSnapshot471,
            "zero/config/key/EOS and reused index occurrences stay distinct" to ::zeroConfigKeyAndEosOccurrences471,
            "one original binding rejects equal-tuple rebind" to ::oneOriginalBindingRejectsEqualTupleRebind471,
            "retired forward rejects before Camera2 call and either borrow" to ::retiredForwardNeverBorrowsOrOpens471,
            "pending original open/configure retains codec and Surface" to ::pendingOpenAndConfigureHoldEncoder471,
            "genuine original no-open fence resolves only that input" to ::genuineNoOpenFenceOnlyOriginal471,
            "wrong and stale real fences cannot retire successor" to ::wrongAndStaleActualFencesCannotRetireSuccessor471,
            "EOS signal failure retains actual fence and independent cleanup" to ::eosSignalFailureKeepsActualFenceAndIndependentCleanup471,
            "codec stop/release/Surface failure attempts are independent and once" to ::eachPhysicalFailureAttemptedOnce471,
            "throwing packet observer stops original and retains until safe" to ::throwingPacketObserverRetainsUntilSafe471,
            "inline reentrant completion never self-joins or double-releases" to ::inlineAndReentrantCompletionNeverSelfJoins471,
            "rejected completion dispatch cannot invent delivery" to ::rejectedCompletionDoesNotInventDelivery471,
            "pending native call, owner-loop return and actual join are separate" to ::nativePendingLoopReturnAndJoinAreSeparate471,
            "equal-tuple encoder objects preserve original resources" to ::equalTupleEncoderObjectsKeepResourcesSeparate471,
            "forward retirement inside bind preserves cached original no-open fence" to ::forwardRetirementDuringBindKeepsCachedNoOpenFence471,
            "bind throws after claim retains actual original duty" to ::throwingForwardBindAfterClaimRetainsActualDuty471,
        )
        for ((name, run) in cases) { println("START encoder471: $name"); run(); println("PASS encoder471: $name") }
        println("PASS encoder471: ${cases.size} actual encoder/Camera2 adapter host groups with explicit doubles; no native AVC, actual Camera2, network, Phone capture, or callback quiescence proof")
    }
}
