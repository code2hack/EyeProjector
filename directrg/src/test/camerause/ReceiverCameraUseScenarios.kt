package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.*
import android.util.Size
import com.code2hack.eyeprojector.directrg.localcamera.*
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.*
import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual S4 owner + S3 ledger/role + S1 adapters; only the declared platform/output boundaries. */
private class ReceiverUseFixture(contextFactory: (CameraManager) -> Context = { Context(it) },
    roleLaunch: (Thread) -> Unit = { it.start() }) {
    val manager = CameraManager()
    val context = contextFactory(manager)
    val lane = CameraOwnerLane()
    val foreground = CameraForegroundProbe()
    val output = CameraOwnerOutput()
    val progress = CopyOnWriteArrayList<Frame>()
    val sockets = CopyOnWriteArrayList<CameraRoleSocket>()
    private val arrivals = LinkedBlockingQueue<CameraRoleSocket>()
    val client = ReceiverCameraRoleClient(File("unused-camera-owner-host-boundary"), "phone", progress::add,
        connector = { _, opened -> arrivals.take().also { opened(it) } }, launch = roleLaunch)
    val owner = ReceiverCameraOwner(context, lane::deliver, foreground::promote)
    val projectionIdentity = Any()
    val projection = owner.attachCapturedProjection(projectionIdentity, client, output, output)
    val permission = CameraPermissionHostProbe(owner)
    val request = CameraStreamRequest(640, 480, 35, "video/avc")
    private val devices = IdentityHashMap<OpenCall, CameraDevice>()
    private val closedCalls = Collections.newSetFromMap(IdentityHashMap<OpenCall, Boolean>())

    init {
        val map = StreamConfigurationMap()
        map.outputs[SurfaceTexture::class.java] = arrayOf(Size(640, 480))
        map.outputs[MediaCodec::class.java] = arrayOf(Size(640, 480))
        val characteristics = CameraCharacteristics()
        characteristics.values[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] = map
        characteristics.values[CameraCharacteristics.LENS_FACING] = CameraCharacteristics.LENS_FACING_BACK
        manager.catalog["actual-back-source"] = characteristics
    }

    fun prepare(generation: Long, requested: CameraStreamRequest = request): CapturedPhoneCameraUse =
        client.observe(CameraUseMessage.Prepare(CameraUseRef("phone", "actual-session", generation), requested)).original!!

    fun start(original: CapturedPhoneCameraUse): CameraRoleSocket {
        val socket = CameraRoleSocket(original.ref)
        sockets.add(socket); arrivals.add(socket)
        client.observe(CameraUseMessage.Start(original.ref, original.request))
        return socket
    }

    /** Supply a fresh ingress boundary without another authenticated use event. */
    fun offerSocket(original: CapturedPhoneCameraUse,
        welcome: CameraAttachDisposition = CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE): CameraRoleSocket =
        CameraRoleSocket(original.ref, welcome).also { sockets.add(it); arrivals.add(it) }

    fun readyNativeCount(count: Int): Triple<OpenCall, CameraDevice, CameraCaptureSession> {
        lane.await("original native open count $count") { manager.opens.size == count }
        return readyNative()
    }

    fun readyNative(): Triple<OpenCall, CameraDevice, CameraCaptureSession> {
        lane.await("actual CameraManager.openCamera") { manager.opens.isNotEmpty() }
        val call = manager.opens.last()
        val device = CameraDevice(call.cameraId)
        devices[call] = device
        check(call.opened(device))
        lane.await("actual createCaptureSession") { device.configurations.isNotEmpty() }
        val session = CameraCaptureSession("original-camera-session")
        check(device.configurations.single().configured(session))
        lane.await("actual repeating request") { session.repeating.isNotEmpty() }
        return Triple(call, device, session)
    }

    fun fence(call: OpenCall, device: CameraDevice) { check(call.closed(device)); closedCalls.add(call) }

    fun finish() {
        var completed: ReceiverCameraCloseObservation? = null
        try {
            owner.closeAsync(Executor { it.run() }) { completed = it }
            // Explicit original callback controls for successful host teardown, never native proof.
            lane.pump()
            if (owner.hasResources()) manager.opens.forEach { call ->
                if (call in closedCalls) return@forEach
                val original = devices[call] ?: CameraDevice(call.cameraId).also {
                    devices[call] = it; check(call.opened(it))
                }
                if (owner.snapshot().sources.any { it.cameraOutputsSafe == null } && call.closed(original)) closedCalls.add(call)
            }
            lane.await("actual owner resource accounting") { completed?.resourcesHeld == false }
            check(!owner.hasResources())
        } finally { client.retireAll(CameraEndReason.PROJECTION_CLOSED) }
    }
}

private fun receiverFixture(contextFactory: (CameraManager) -> Context = { Context(it) },
    body: (ReceiverUseFixture) -> Unit) {
    CodecProbe.reset()
    val fixture = ReceiverUseFixture(contextFactory)
    var primary: Throwable? = null
    try { body(fixture) } catch (error: Throwable) { primary = error; throw error }
    finally {
        try { fixture.finish() } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup else if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }
}

/** S3's actual event lane posts every earlier use observer before sending this local marker. */
private fun awaitPriorUseDelivery(fixture: ReceiverUseFixture, original: CapturedPhoneCameraUse) {
    val marker = CameraProgress(original.ref, null, CameraStage.SOURCE_PREPARATION, CameraFact.PENDING,
        CameraClock.JAVA_SYSTEM_NANOTIME_NS, System.nanoTime())
    fixture.client.reportProgress(original, marker)
    fixture.lane.await("actual S3 prior-use delivery marker") {
        fixture.progress.any { it.channel == Wire.CAMERA_PROGRESS && CameraFeed.readProgress(it.payload) == marker }
    }
}

private fun prepareIsPermissionOnlyAndLegacyCloseStaysSynchronous() = receiverFixture { f ->
    f.permission.attach()
    val original = f.prepare(1)
    f.lane.await("captured permission request") { f.permission.requests.isNotEmpty() }
    val token = f.permission.requests.single()
    check(token.original === original && token.hostIdentity === f.permission.identity)
    check(f.owner.permissionRequestCurrent(token))
    check(!f.projection.hasPhysicalDuty && !f.owner.hasResources())
    check(f.manager.opens.isEmpty() && CodecProbe.codecs.isEmpty() && f.output.inputs.isEmpty())
    var returned = false
    f.projection.closeAsync(Executor { it.run() }) { result ->
        check(!result.resourcesHeld); check(result.sources.isEmpty()); returned = true
        f.owner.snapshot() // Inline observer reentry is outside owner locks.
    }
    check(returned)
    check(!f.owner.permissionRequestCurrent(token))
}

private fun staleActivityResultCannotOpenReplacement() = receiverFixture { f ->
    f.permission.attach()
    val original = f.prepare(2)
    f.start(original)
    f.lane.await("first Activity request") { f.permission.requests.isNotEmpty() }
    val old = f.permission.requests.single()
    f.permission.detach()
    val nextHost = CameraPermissionHostProbe(f.owner)
    nextHost.attach()
    f.lane.await("replacement Activity request") { nextHost.requests.isNotEmpty() }
    val next = nextHost.requests.single()
    check(next !== old && next.requestCode != old.requestCode)
    check(!f.owner.permissionRequestCurrent(old) && f.owner.permissionRequestCurrent(next))
    f.owner.permissionResult(old, true)
    f.lane.pump()
    check(f.manager.opens.isEmpty() && CodecProbe.codecs.isEmpty())
    f.owner.permissionResult(next, false)
    f.lane.pump()
    check(!f.projection.hasPhysicalDuty && f.manager.opens.isEmpty())
}

private fun requestInvocationFailureIsNotUserDenial() = receiverFixture { f ->
    val thrown = IllegalStateException("requestPermissions invocation failed")
    f.permission.onRequest = { throw thrown }
    f.permission.attach(); f.prepare(3)
    f.lane.await("invocation failure retained") {
        f.owner.snapshot().failures.any { it.operation == "permission-request-invocation" && it.error === thrown }
    }
    val submissions = f.client.progressSubmissions()
    check(submissions.any { it.value.stage == CameraStage.PERMISSION && it.value.fact == CameraFact.FAILED })
    check(submissions.none { it.value.stage == CameraStage.PERMISSION && it.value.fact == CameraFact.UNAVAILABLE })
    check(f.manager.opens.isEmpty() && !f.projection.hasPhysicalDuty)
}

private fun realSourceAndEncodedRecordsAreSharedByCompatibleMembers() = receiverFixture { f ->
    f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
    val codec = MediaCodec("actual-host-s1-encoder")
    CodecProbe.supply(codec)
    val first = f.prepare(10); val firstSocket = f.start(first)
    val (call, device, session) = f.readyNative()
    f.lane.await("first real role welcome") { firstSocket.awaitWelcome.count == 0L }
    val second = f.prepare(11, f.request.copy(requestedWidth = 1280, requestedHeight = 720, requestedPixelFormat = 256))
    val secondSocket = f.start(second)
    f.lane.await("second real role welcome") { secondSocket.awaitWelcome.count == 0L }
    check(f.manager.opens.size == 1 && CodecProbe.codecs.size == 1 && f.output.inputs.size == 1)
    val originalSource = f.owner.snapshot().sources.single().sourceIdentity
    f.client.observe(CameraUseMessage.Update(first.ref,
        f.request.copy(requestedWidth = 1920, requestedHeight = 1080, requestedPixelFormat = 17)))
    awaitPriorUseDelivery(f, first)
    check(f.client.snapshot().size == 2 && f.client.snapshot().none { it.retirementRequested })
    check(f.owner.snapshot().sources.single().let { it.sourceIdentity === originalSource && it.roles.size == 2 })
    check(f.manager.opens.size == 1 && CodecProbe.codecs.size == 1 && f.output.inputs.size == 1)
    val local = f.output.inputs.single()
    val outputs = device.configurations.single().outputs
    check(outputs.size == 2 && outputs[0] === local.surface && outputs[1] === codec.inputSurface)
    check(session.repeating.single().request.targets == outputs)
    codec.output(CodecOutput.Format(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
    }))
    codec.output(CodecOutput.Buffer(7, ByteBuffer.wrap(byteArrayOf(9, 8, 7, 6, 5)), 1, 3, -19, MediaCodec.BUFFER_FLAG_KEY_FRAME))
    f.lane.await("actual S3 writes for both captured roles") { f.client.snapshot().size == 2 && f.client.snapshot().all { it.framesWritten >= 2 } }
    for (socket in listOf(firstSocket, secondSocket)) {
        val input = DataInputStream(socket.written.inputStream())
        check(Wire.readRequest(input) == Wire.CAMERA)
        check(CameraFeed.readHello(input) == socket.ref)
        val assembler = CameraFragmentAssembler()
        var bytes: ByteArray? = null
        while (input.available() > 0) {
            val frame = Wire.readFrame(input)
            when (frame.channel) {
                Wire.CAMERA_STREAM -> assembler.description(CameraFeed.readStream(frame.payload))
                Wire.CAMERA_MEDIA -> assembler.media(CameraFeed.readMedia(frame.payload))?.let {
                    check(it.ptsUs == -19L && it.codecFlags == MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    bytes = it.data
                }
            }
        }
        check(bytes?.contentEquals(byteArrayOf(8, 7, 6)) == true)
    }
    f.client.observe(CameraUseMessage.Close(first.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    f.lane.await("first role original retirement") { f.client.snapshot().first().retirementRequested }
    f.lane.pump()
    check(device.closeCount == 0 && !local.retired && f.owner.hasResources())
    f.client.observe(CameraUseMessage.Close(second.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    f.lane.await("last member direct Camera2 stop") { device.closeCount == 1 }
    check(local.releaseResult == null && codec.inputSurface.releaseCount == 0)
    f.fence(call, device)
    f.lane.await("original source fence and joins") { !f.owner.hasResources() }
    check(f.projection.hasPhysicalDuty) // Sticky even after source resources are physically retired.
    check(f.owner.snapshot().sources.single().cameraOwnerThreadClosedObserved == true)
}

private fun overlappingSizeSelectionsRetainFirstCompatibleNativeSource() {
    val firstEntered = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
    val secondEntered = CountDownLatch(1); val releaseSecond = CountDownLatch(1)
    val catalogCalls = AtomicInteger()
    try { receiverFixture({ manager -> object : Context(manager) {
        override fun getSystemService(name: String): Any? {
            when (catalogCalls.incrementAndGet()) {
                1 -> { firstEntered.countDown(); releaseFirst.await() }
                2 -> { secondEntered.countDown(); releaseSecond.await() }
            }
            return super.getSystemService(name)
        }
    } }) { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        val map = f.manager.catalog.values.single()[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        map.outputs[SurfaceTexture::class.java] = arrayOf(Size(640, 480), Size(1280, 720))
        map.outputs[MediaCodec::class.java] = arrayOf(Size(640, 480), Size(1280, 720))
        CodecProbe.supply(MediaCodec("overlapping-size-selection-encoder"))
        val first = f.prepare(12); val firstSocket = f.start(first)
        f.lane.await("first original catalog selection held") { firstEntered.count == 0L }
        val second = f.prepare(13,
            f.request.copy(requestedWidth = 1280, requestedHeight = 720, requestedPixelFormat = 256))
        val secondSocket = f.start(second)
        f.lane.await("second original catalog selection overlaps") { secondEntered.count == 0L }
        check(f.owner.snapshot().sources.isEmpty() && f.manager.opens.isEmpty())
        releaseFirst.countDown()
        val (call, device, _) = f.readyNative()
        f.lane.await("first overlapping role welcome") { firstSocket.awaitWelcome.count == 0L }
        val installed = f.owner.snapshot().sources.single()
        check(installed.profile?.localSize == ReceiverCameraSize(640, 480))
        check(installed.profile?.encoderSize == ReceiverCameraSize(640, 480))
        releaseSecond.countDown()
        f.lane.await("both overlapping selections accounted and roles welcomed") {
            f.owner.snapshot().pendingOperations.isEmpty() && secondSocket.awaitWelcome.count == 0L
        }
        val retained = f.owner.snapshot().sources.single()
        check(retained.sourceIdentity === installed.sourceIdentity)
        check(retained.profile?.localSize == ReceiverCameraSize(640, 480))
        check(retained.profile?.encoderSize == ReceiverCameraSize(640, 480))
        check(f.manager.characteristicReads.size == 2)
        check(f.manager.opens.size == 1 && CodecProbe.codecs.size == 1 && f.output.inputs.size == 1)
        check(f.client.snapshot().size == 2 && f.client.snapshot().none { it.retirementRequested })
        f.client.observe(CameraUseMessage.Close(first.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        f.client.observe(CameraUseMessage.Close(second.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        f.lane.await("overlapping members directly stop original device") { device.closeCount == 1 }
        f.fence(call, device)
        f.lane.await("overlapping original source joined") { !f.owner.hasResources() }
    } } finally { releaseFirst.countDown(); releaseSecond.countDown() }
}

private fun ledgerUpdateDisqualifiesSelectionBeforeQueuedOwnerUpdate() {
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val firstCatalog = AtomicBoolean(true)
    try { receiverFixture({ manager -> object : Context(manager) {
        override fun getSystemService(name: String): Any? {
            if (firstCatalog.compareAndSet(true, false)) { entered.countDown(); release.await() }
            return super.getSystemService(name)
        }
    } }) { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        val map = f.manager.catalog.values.single()[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        map.outputs[SurfaceTexture::class.java] = arrayOf(Size(640, 480), Size(1280, 720))
        map.outputs[MediaCodec::class.java] = arrayOf(Size(640, 480), Size(1280, 720))
        CodecProbe.supply(MediaCodec("updated-selection-encoder"))
        val original = f.prepare(14); val socket = f.start(original)
        f.lane.await("original catalog selection held before return") { entered.count == 0L }
        awaitPriorUseDelivery(f, original)
        release.countDown()
        // Selection posts its completion before its actual join observer can post evaluation.
        val originalCompletion = f.lane.take("completed original selection held before owner entry")
        val updated = f.request.copy(requestedWidth = 1280, requestedHeight = 720,
            requestedPixelFormat = 256, frameRate = 60)
        f.client.observe(CameraUseMessage.Update(original.ref, updated))
        check(original.request == updated) // Actual ledger already changed; Owner Update stays queued.
        originalCompletion()
        check(f.owner.snapshot().sources.isEmpty())
        check(f.output.inputs.isEmpty() && f.manager.opens.isEmpty() && CodecProbe.codecs.isEmpty())
        val (call, device, _) = f.readyNative()
        f.lane.await("updated original role welcome") { socket.awaitWelcome.count == 0L }
        f.lane.await("both original and updated selection joins") { f.owner.snapshot().pendingOperations.isEmpty() }
        val selected = f.owner.snapshot().sources.single().profile!!
        check(selected.requestedSize == ReceiverCameraSize(1280, 720))
        check(selected.localSize == ReceiverCameraSize(1280, 720) && selected.encoderSize == ReceiverCameraSize(1280, 720))
        check(selected.frameRate == 60)
        check(f.manager.opens.size == 1 && CodecProbe.codecs.size == 1 && f.output.inputs.size == 1)
        f.client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        f.lane.await("updated original direct device stop") { device.closeCount == 1 }
        f.fence(call, device)
        f.lane.await("updated original source joined") { !f.owner.hasResources() }
    } } finally { release.countDown() }
}

private fun stopBeforeOpenArrivalRetainsOriginalEndpoints() = receiverFixture { f ->
    f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
    val codec = MediaCodec("pending-open-encoder"); CodecProbe.supply(codec)
    val use = f.prepare(20); f.start(use)
    f.lane.await("pending original open") { f.manager.opens.isNotEmpty() }
    val call = f.manager.opens.single()
    f.client.observe(CameraUseMessage.Close(use.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    f.lane.await("original source logical retirement") { f.output.inputs.single().retired }
    check(f.owner.hasResources() && f.output.inputs.single().releaseResult == null && codec.inputSurface.releaseCount == 0)
    val device = CameraDevice(call.cameraId)
    check(call.opened(device))
    f.lane.await("late original device close invoked") { device.closeCount == 1 }
    check(device.configurations.isEmpty())
    f.fence(call, device)
    f.lane.await("late original fence and joined thread") { !f.owner.hasResources() }
}

private fun nativeCatalogWaitCannotBlockRetirementOrInstallLateSource() {
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val first = AtomicBoolean(true); val threads = CopyOnWriteArrayList<Thread>()
    try { receiverFixture({ manager -> object : Context(manager) {
        override fun getSystemService(name: String): Any? {
            threads.add(Thread.currentThread())
            if (first.compareAndSet(true, false)) { entered.countDown(); release.await() }
            return super.getSystemService(name)
        }
    } }) { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        val original = f.prepare(30); f.start(original)
        f.lane.await("actual catalog Binder boundary entered") { entered.count == 0L }
        check(threads.single() !== Thread.currentThread())
        var completion: ReceiverCameraCloseObservation? = null
        f.projection.closeAsync(Executor { it.run() }) { completion = it }
        check(completion == null && f.owner.hasResources() && f.projection.hasPhysicalDuty)
        check(f.owner.snapshot().pendingOperations.any { it.contains("source-selection") })
        release.countDown()
        f.lane.await("selection original joined without late source") { completion?.resourcesHeld == false }
        check(f.manager.opens.isEmpty() && f.output.inputs.isEmpty() && CodecProbe.codecs.isEmpty())
    } } finally { release.countDown() }
}

private fun foregroundReentryCannotCreateASecondOrRetiredSource() = receiverFixture { f ->
    f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
    f.foreground.onPromote = { f.projection.retire(CameraEndReason.PROJECTION_CLOSED) }
    val original = f.prepare(40); f.start(original)
    f.lane.await("retired pending source accounted") { f.foreground.calls.isNotEmpty() && !f.owner.hasResources() }
    check(f.foreground.calls.size == 1 && f.manager.opens.isEmpty() && f.output.inputs.isEmpty())
    check(f.projection.hasPhysicalDuty)
}

private fun blockedMediaCannotDelayDirectOriginalCameraStop() {
    val writing = CountDownLatch(1); val releaseWrite = CountDownLatch(1)
    val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
    val blockOnce = AtomicBoolean(true)
    try { receiverFixture { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        val codec = MediaCodec("blocked-media-encoder"); CodecProbe.supply(codec)
        val original = f.prepare(50); val socket = f.start(original)
        val (call, device, _) = f.readyNative()
        f.lane.await("actual role welcome") { socket.awaitWelcome.count == 0L }
        socket.onWrite = { if (blockOnce.compareAndSet(true, false)) { writing.countDown(); releaseWrite.await() } }
        socket.onClose = { closing.countDown(); releaseClose.await() }
        codec.output(CodecOutput.Format(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480)))
        f.lane.await("blocked actual role write") { writing.count == 0L }
        codec.output(CodecOutput.Buffer(8, ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0, 3, 123, 0))
        f.lane.await("actual native buffer released before rendezvous") { CodecProbe.bufferReleases.isNotEmpty() }
        f.client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        f.lane.await("direct producer stop independent of blocked role close") { device.closeCount == 1 }
        check(f.owner.hasResources() && codec.inputSurface.releaseCount == 0)
        releaseWrite.countDown(); releaseClose.countDown()
        f.fence(call, device)
        f.lane.await("retired media and native source joined") { !f.owner.hasResources() }
    } } finally { releaseWrite.countDown(); releaseClose.countDown() }
}

private fun rejectedCompletionDoesNotInventDelivery() = receiverFixture { f ->
    var called = false
    f.projection.closeAsync(Executor { throw RejectedExecutionException("explicit callback boundary") }) { called = true }
    check(!called && !f.owner.hasResources())
    check(f.owner.snapshot().failures.any { it.operation == "completion-dispatch" })
}

private fun unsupportedLocalLeaseNeverReachesCameraBorrow() {
    CodecProbe.reset()
    val fixture = ReceiverUseFixture()
    fixture.context.cameraPermission = PackageManager.PERMISSION_GRANTED
    val endpoint = android.view.Surface("unsupported-local-boundary")
    var borrowCount = 0; var closeCount = 0
    val unsupported = object : LocalPreviewOutput {
        override fun currentPhoneTextureLatch(): PhoneTextureLatchKey? = null
        override fun bindFixturePhoneImage(latch: PhoneTextureLatchKey, image: PhoneImageKey, layout: ViewfinderLayout?) = false
        override fun openLocalCamera(configuration: LocalCameraConfiguration, observer: LocalCameraObserver?): LocalCameraInput =
            object : LocalCameraInput {
                override val configuration = configuration
                override val surface get() = endpoint.also { borrowCount++ }
                override var retired = false
                override val lastFailure: LocalCameraFailure? = null
                override fun close() { closeCount++; retired = true; endpoint.release() }
            }
    }
    val client = ReceiverCameraRoleClient(File("unused-unsupported-local-boundary"), "phone", {})
    val owner = ReceiverCameraOwner(fixture.context, fixture.lane::deliver, fixture.foreground::promote)
    owner.attachCapturedProjection(Any(), client, unsupported, unsupported)
    val ref = CameraUseRef("phone", "unsupported-original", 60)
    try {
        client.observe(CameraUseMessage.Prepare(ref, fixture.request))
        client.observe(CameraUseMessage.Start(ref, fixture.request))
        fixture.lane.await("unsupported never-bound lease close returned") { closeCount == 1 && owner.snapshot().sources.isNotEmpty() }
        fixture.lane.await("unsupported original helper joins") {
            owner.snapshot().pendingOperations.isEmpty() && owner.snapshot().sources.single().pending.none { it.endsWith("-join") || it == "source-setup" }
        }
        check(fixture.manager.opens.isEmpty() && CodecProbe.codecs.isEmpty() && borrowCount == 0)
        val observed = owner.snapshot().sources.single()
        check(observed.failures.any { it.operation == "local-retirement-capability" })
        check(observed.localRelease == null && observed.pending.contains("legacy-local-close-returned-release-unobserved"))
        check(owner.hasResources()) // A close return cannot invent the unsupported physical result.
    } finally {
        val completed = AtomicBoolean(false)
        try {
            owner.closeAsync(Executor { it.run() }) { completed.set(true) }
            fixture.lane.pump()
            check(!completed.get()) { "Unsupported physical result was fabricated" }
            check(owner.hasResources())
            check(owner.snapshot().sources.single().pending.contains("legacy-local-close-returned-release-unobserved"))
        } finally {
            client.retireAll(CameraEndReason.PROJECTION_CLOSED)
            fixture.finish()
        }
    }
}

private fun knownRoleFailuresRecoverLiveUseAfterOriginalFences() {
    val releaseWelcome = CountDownLatch(1)
    try { receiverFixture { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        val firstCodec = MediaCodec("recovery-first"); CodecProbe.supply(firstCodec)
        val original = f.prepare(70); val firstSocket = f.start(original)
        val (firstCall, firstDevice, _) = f.readyNative()
        f.lane.await("first recovery role welcome") { firstSocket.awaitWelcome.count == 0L }
        val control = CameraOwnerRoleControl(f.client)
        val firstRole = control.roles().single()
        val firstSource = f.owner.snapshot().sources.single().sourceIdentity
        val rejected = f.offerSocket(original, CameraAttachDisposition.PHONE_CONSUMER_UNAVAILABLE)
        val readingWelcome = CountDownLatch(1)
        rejected.onRead = { readingWelcome.countDown(); releaseWelcome.await() }
        val secondCodec = MediaCodec("recovery-rejected-welcome"); CodecProbe.supply(secondCodec)
        firstSocket.remoteEof()
        f.lane.await("EOF stops last-member original camera directly") { firstDevice.closeCount == 1 }
        f.lane.await("known EOF original role actual close") {
            f.owner.snapshot().sources.first().roles.single().let { !it.pending && it.observation?.writerJoined == true }
        }
        check(original.live && original.started && f.client.receivedUses().single() === original)
        check(f.manager.opens.size == 1 && f.output.inputs.first().releaseResult == null)
        check(firstCodec.inputSurface.releaseCount == 0)
        f.fence(firstCall, firstDevice)
        val (secondCall, secondDevice, _) = f.readyNativeCount(2)
        f.lane.await("actual rejected welcome read held") { readingWelcome.count == 0L }
        check(f.output.inputs.first().releaseResult != null && firstCodec.inputSurface.releaseCount == 1)
        check(control.roles().size == 2 && control.roles()[1] !== firstRole)
        check(control.roles()[1].original === original && control.roles()[1].source !== firstRole.source)
        check(f.owner.snapshot().sources.last().sourceIdentity !== firstSource)
        val thirdSocket = f.offerSocket(original)
        CodecProbe.supply(MediaCodec("recovery-after-rejected-welcome"))
        releaseWelcome.countDown()
        f.lane.await("rejected welcome stops its original native source") { secondDevice.closeCount == 1 }
        f.lane.await("rejected original role actual close") {
            f.owner.snapshot().sources[1].roles.single().let { !it.pending && it.observation?.welcome == CameraAttachDisposition.PHONE_CONSUMER_UNAVAILABLE }
        }
        check(f.manager.opens.size == 2 && secondCodec.inputSurface.releaseCount == 0)
        f.fence(secondCall, secondDevice)
        val (thirdCall, thirdDevice, _) = f.readyNativeCount(3)
        f.lane.await("same live use fresh role after rejected attach") { thirdSocket.awaitWelcome.count == 0L }
        check(control.roles().size == 3 && control.roles().all { it.original === original })
        check(f.client.receivedUses().single() === original)
        f.client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        f.lane.await("final recovered source direct stop") { thirdDevice.closeCount == 1 }
        f.fence(thirdCall, thirdDevice)
        f.lane.await("all failed attempts and recovered source actually joined") { !f.owner.hasResources() }
    } } finally { releaseWelcome.countDown() }
}

private fun sharedSiblingSurvivesOneChildRecovery() = receiverFixture { f ->
    f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
    CodecProbe.supply(MediaCodec("shared-child-recovery"))
    val original = f.prepare(71); val firstSocket = f.start(original)
    val (call, device, _) = f.readyNative()
    val sibling = f.prepare(72); val siblingSocket = f.start(sibling)
    f.lane.await("both sibling roles welcomed") { firstSocket.awaitWelcome.count == 0L && siblingSocket.awaitWelcome.count == 0L }
    val control = CameraOwnerRoleControl(f.client)
    val old = control.roles().first { it.original === original }
    val siblingRole = control.roles().first { it.original === sibling }
    val source = f.owner.snapshot().sources.single().sourceIdentity
    val replacement = f.offerSocket(original)
    firstSocket.remoteEof()
    f.lane.await("one child recovers without another use event") { control.roles().size == 3 && replacement.awaitWelcome.count == 0L }
    val fresh = control.roles().last()
    check(fresh.original === original && fresh !== old && fresh.source !== old.source)
    check(!siblingRole.snapshot().retirementRequested && sibling.live)
    check(f.owner.snapshot().sources.single().sourceIdentity === source)
    check(f.manager.opens.size == 1 && CodecProbe.codecs.size == 1 && f.output.inputs.size == 1 && device.closeCount == 0)
    check(f.client.receivedUses().size == 2)
    f.client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    awaitPriorUseDelivery(f, sibling)
    check(device.closeCount == 0 && !siblingRole.snapshot().retirementRequested)
    f.client.observe(CameraUseMessage.Close(sibling.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    f.lane.await("last sibling stops original native source") { device.closeCount == 1 }
    f.fence(call, device)
    f.lane.await("shared recovery original joins") { !f.owner.hasResources() }
}

private fun lateOriginalCallbacksPreserveAuthoritativeCloseFacts() {
    val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
    try { receiverFixture { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        CodecProbe.supply(MediaCodec("late-recovery-callbacks"))
        val original = f.prepare(73); val socket = f.start(original)
        val (call, device, _) = f.readyNative()
        val sibling = f.prepare(74); val siblingSocket = f.start(sibling)
        f.lane.await("late callback original roles active") { socket.awaitWelcome.count == 0L && siblingSocket.awaitWelcome.count == 0L }
        val control = CameraOwnerRoleControl(f.client)
        val old = control.roles().first { it.original === original }
        f.lane.await("actual original reader and writer started") { old.snapshot().let { it.writerStarted && it.readerStarted } }
        val early = old.snapshot()
        check(!early.writerJoined && !early.readerJoined)
        val position = f.owner.snapshot().sources.single().roles.indexOfFirst { it.original === original }
        val source = f.owner.snapshot().sources.single().sourceIdentity
        socket.onClose = { closing.countDown(); releaseClose.await() }
        val freshSocket = f.offerSocket(original)
        old.requestStop(CameraEndReason.TRANSPORT_EOF)
        control.useEnd(old, CameraEndReason.TRANSPORT_EOF) // Exact installed callback, inline.
        control.retireAttachment(old, CameraEndReason.TRANSPORT_EOF) // Exact original hub child route.
        f.lane.await("duplicate retirement retains one pending original") {
            closing.count == 0L && f.owner.snapshot().pendingOperations.any { it.startsWith("role-recovery-") }
        }
        val retiring = old.snapshot()
        check(retiring.retirementRequested && !retiring.readerJoined)
        control.changed(old, retiring); control.changed(old, retiring)
        f.lane.pump()
        check(control.roles().size == 2)
        releaseClose.countDown()
        f.lane.await("one exact successor after known original close") {
            control.roles().size == 3 && freshSocket.awaitWelcome.count == 0L &&
                !f.owner.snapshot().sources.single().roles[position].pending
        }
        val before = f.owner.snapshot().sources.single().roles[position]
        val receipt = before.observation!!
        check(receipt.writerJoined && receipt.readerJoined && receipt.sourceRetireReturned == true)
        check(receipt.socketCloseResults.isNotEmpty() && receipt.socketCloseResults.all { it == true })
        check(receipt.retirementWorkers.all { it.returned && it.joined && !it.failed })
        control.changed(old, early) // Genuinely observed older facts delivered after actual close.
        control.changed(old, retiring); control.useEnd(old, CameraEndReason.TRANSPORT_EOF)
        control.retireAttachment(old, CameraEndReason.TRANSPORT_EOF)
        f.lane.pump()
        val after = f.owner.snapshot().sources.single().roles[position]
        check(after.observation == receipt && !after.pending)
        check(f.owner.snapshot().sources.single().sourceIdentity === source)
        check(control.roles().size == 3 && control.roles().count { it.original === original && !it.snapshot().retirementRequested } == 1)
        check(f.manager.opens.size == 1 && device.closeCount == 0)
        f.projection.retire(CameraEndReason.PROJECTION_CLOSED)
        f.lane.await("late callback scenario original device stop") { device.closeCount == 1 }
        f.fence(call, device)
        f.lane.await("late callback scenario actual joins") { !f.owner.hasResources() }
    } } finally { releaseClose.countDown() }
}

private fun currentDemandWinsQueuedRecovery() {
    for (ending in listOf("close", "replace", "projection", "owner", "update")) {
        val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
        try { receiverFixture { f ->
            f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
            CodecProbe.supply(MediaCodec("queued-recovery-$ending"))
            val original = f.prepare(75); val socket = f.start(original)
            val (call, device, _) = f.readyNative()
            val sibling = f.prepare(76); val siblingSocket = f.start(sibling)
            f.lane.await("queued recovery original siblings welcomed") { socket.awaitWelcome.count == 0L && siblingSocket.awaitWelcome.count == 0L }
            val control = CameraOwnerRoleControl(f.client)
            val position = f.owner.snapshot().sources.single().roles.indexOfFirst { it.original === original }
            socket.onClose = { closing.countDown(); releaseClose.await() }
            socket.remoteEof()
            f.lane.await("queued recovery original closure held") {
                closing.count == 0L && f.owner.snapshot().pendingOperations.any { it.startsWith("role-recovery-") }
            }
            releaseClose.countDown()
            f.lane.awaitWithoutDelivery("actual close receipt before queued recovery evaluation") {
                !f.owner.snapshot().sources.single().roles[position].pending
            }
            check(f.owner.snapshot().pendingOperations.any { it.startsWith("role-recovery-") })
            var ownerCompleted = false
            when (ending) {
                "close" -> {
                    f.client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
                    awaitPriorUseDelivery(f, sibling)
                    check(!original.live && control.roles().size == 2)
                }
                "replace" -> {
                    val next = f.client.observe(CameraUseMessage.Replace(original.ref,
                        CameraUseRef("phone", "actual-session", 77), f.request, CameraEndReason.REPLACED)).replacement!!
                    awaitPriorUseDelivery(f, sibling)
                    check(!original.live && next.live && !next.started && control.roles().size == 2)
                    val replacement = f.start(next)
                    f.lane.await("only actual replacement Start creates role") { replacement.awaitWelcome.count == 0L }
                    check(control.roles().size == 3 && control.roles().last().original === next)
                }
                "projection" -> { f.projection.retire(CameraEndReason.PROJECTION_CLOSED); f.lane.pump(); check(control.roles().size == 2) }
                "owner" -> {
                    f.owner.closeAsync(Executor { it.run() }) { ownerCompleted = true }
                    f.lane.pump(); check(control.roles().size == 2)
                }
                "update" -> {
                    val updated = f.request.copy(requestedWidth = 1280, requestedHeight = 720, requestedPixelFormat = 256)
                    val fresh = f.offerSocket(original)
                    f.client.observe(CameraUseMessage.Update(original.ref, updated))
                    check(original.request == updated)
                    awaitPriorUseDelivery(f, sibling)
                    f.lane.await("queued recovery uses current actual request") { fresh.awaitWelcome.count == 0L }
                    check(control.roles().size == 3 && control.roles().last().original === original)
                    check((control.roles().last().source as EncoderCameraMediaSource.Attachment).request == updated)
                }
            }
            check(f.manager.opens.size == 1 && CodecProbe.codecs.size == 1)
            f.projection.retire(CameraEndReason.PROJECTION_CLOSED)
            f.lane.await("current demand scenario original native stop") { device.closeCount == 1 }
            f.fence(call, device)
            f.lane.await("current demand scenario actual original joins") { !f.owner.hasResources() }
            if (ending == "owner") check(ownerCompleted)
        } } finally { releaseClose.countDown() }
    }
}

private fun unsettledOriginalRolesCannotReplay() {
    val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
    try { receiverFixture { f ->
        f.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        CodecProbe.supply(MediaCodec("unknown-old-role-close"))
        val original = f.prepare(78); val socket = f.start(original)
        val (call, device, _) = f.readyNative()
        f.lane.await("unknown-close role welcome") { socket.awaitWelcome.count == 0L }
        val control = CameraOwnerRoleControl(f.client)
        socket.onClose = { closing.countDown(); releaseClose.await() }
        val successor = f.offerSocket(original); CodecProbe.supply(MediaCodec("after-known-role-close"))
        socket.remoteEof()
        f.lane.await("unknown close original native stop") { closing.count == 0L && device.closeCount == 1 }
        f.fence(call, device)
        f.lane.await("only actual unknown original role duty remains") {
            f.owner.snapshot().sources.first().pending.all { it.startsWith("role-") }
        }
        check(control.roles().size == 1 && f.manager.opens.size == 1 && f.owner.snapshot().sources.size == 1)
        check(f.owner.snapshot().pendingOperations.any { it.startsWith("role-recovery-") })
        releaseClose.countDown()
        val (nextCall, nextDevice, _) = f.readyNativeCount(2)
        f.lane.await("known close releases one fresh attempt") { successor.awaitWelcome.count == 0L }
        f.client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        f.lane.await("known successor direct stop") { nextDevice.closeCount == 1 }
        f.fence(nextCall, nextDevice)
        f.lane.await("unknown-close scenario actual joins") { !f.owner.hasResources() }
    } } finally { releaseClose.countDown() }

    // A failed close is deliberately never converted to a successful release during teardown.
    CodecProbe.reset()
    val failed = ReceiverUseFixture()
    failed.context.cameraPermission = PackageManager.PERMISSION_GRANTED
    CodecProbe.supply(MediaCodec("failed-old-role-close"))
    val original = failed.prepare(79); val socket = failed.start(original)
    val (call, device, _) = failed.readyNative()
    val control = CameraOwnerRoleControl(failed.client)
    var fenced = false
    try {
        failed.lane.await("failed-close role welcome") { socket.awaitWelcome.count == 0L }
        socket.onClose = { socket.remoteEof(); throw IOException("Explicit original socket close failure") }
        socket.remoteEof()
        failed.lane.await("actual failed original role close receipt") {
            failed.owner.snapshot().sources.single().roles.single().observation?.let {
                it.writerJoined && it.readerJoined && it.socketCloseResults.any { result -> result == false }
            } == true
        }
        failed.lane.await("failed close still directly stops original camera") { device.closeCount == 1 }
        failed.fence(call, device); fenced = true
        failed.lane.await("failed role only retains its actual closure debt") {
            failed.owner.snapshot().sources.single().pending.all { it.startsWith("role-") }
        }
        check(control.roles().size == 1 && failed.manager.opens.size == 1)
        check(failed.owner.snapshot().pendingOperations.any { it.startsWith("role-recovery-") })
    } finally {
        val completed = AtomicBoolean(false)
        failed.owner.closeAsync(Executor { it.run() }) { completed.set(true) }
        socket.emergencyWake() // Fixture waiters only; failed S3 close facts remain false.
        if (!fenced) { failed.lane.await("failed-close teardown original stop") { device.closeCount == 1 }; failed.fence(call, device) }
        failed.client.retireAll(CameraEndReason.PROJECTION_CLOSED)
        failed.lane.await("failed-close actual local threads joined with qualified debt") {
            failed.owner.snapshot().sources.single().pending.all { it.startsWith("role-") } &&
                control.roles().all { it.snapshot().let { value -> (!value.writerStarted || value.writerJoined) && (!value.readerStarted || value.readerJoined) } }
        }
        check(!completed.get() && failed.owner.hasResources())
        val receipt = failed.owner.snapshot().sources.single().roles.single()
        check(receipt.pending && receipt.observation!!.socketCloseResults.any { it == false })
        check(socket.closeCalls.get() == 1 && socket.emergencyWakeCalls.get() == 1)
    }

    // Inline actual callback while S3 connect has not returned/settled its writer launch.
    CodecProbe.reset()
    val preparing = MediaCodec("unreturned-connect-original")
    val configure = preparing.holdNext("configure")
    CodecProbe.supply(preparing); CodecProbe.supply(MediaCodec("after-unreturned-connect"))
    val firstLaunch = AtomicBoolean(true)
    val inlineObserved = AtomicBoolean(false)
    lateinit var unreturned: ReceiverUseFixture
    unreturned = ReceiverUseFixture(roleLaunch = { worker ->
        if (worker.name == "eyeprojector-camera-writer" && firstLaunch.compareAndSet(true, false)) {
            try {
                val actual = CameraOwnerRoleControl(unreturned.client)
                val role = actual.roles().single()
                role.requestStop(CameraEndReason.TRANSPORT_EOF)
                actual.useEnd(role, CameraEndReason.TRANSPORT_EOF)
                unreturned.lane.pump()
                check(!role.snapshot().writerStarted && worker.state == Thread.State.NEW)
                check(actual.roles().size == 1 && unreturned.owner.snapshot().sources.size == 1)
                check(unreturned.owner.snapshot().pendingOperations.any { it.startsWith("role-recovery-") })
                check(unreturned.owner.snapshot().sources.single().roles.single().pending)
                inlineObserved.set(true)
            } finally { configure.release.countDown(); worker.start() }
        } else worker.start()
    })
    try {
        unreturned.context.cameraPermission = PackageManager.PERMISSION_GRANTED
        val use = unreturned.prepare(80); val available = unreturned.start(use)
        unreturned.lane.await("actual unreturned connect callback observed inline") { inlineObserved.get() }
        val (nextCall, nextDevice, _) = unreturned.readyNativeCount(1)
        unreturned.lane.await("settled original connect admits one fresh role") {
            CameraOwnerRoleControl(unreturned.client).roles().size == 2 && available.awaitWelcome.count == 0L
        }
        check(unreturned.owner.snapshot().sources.first().roles.single().pending == false)
        unreturned.client.observe(CameraUseMessage.Close(use.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
        unreturned.lane.await("unreturned-connect successor original stop") { nextDevice.closeCount == 1 }
        unreturned.fence(nextCall, nextDevice)
        unreturned.lane.await("unreturned-connect actual original joins") { !unreturned.owner.hasResources() }
    } finally { configure.release.countDown(); unreturned.finish() }
}

object ReceiverCameraUse501Scenarios {
    @JvmStatic fun main(args: Array<String>) {
        val cases = listOf(
            "PREPARE permission only and synchronous never-camera close" to ::prepareIsPermissionOnlyAndLegacyCloseStaysSynchronous,
            "old Activity permission result cannot admit a replacement" to ::staleActivityResultCannotOpenReplacement,
            "permission request invocation fault is not denial" to ::requestInvocationFailureIsNotUserDenial,
            "compatible members share actual adapters and real encoded records" to ::realSourceAndEncodedRecordsAreSharedByCompatibleMembers,
            "overlapping differently sized selections retain first compatible native source" to ::overlappingSizeSelectionsRetainFirstCompatibleNativeSource,
            "ledger Update disqualifies old selection before queued owner Update" to ::ledgerUpdateDisqualifiesSelectionBeforeQueuedOwnerUpdate,
            "stop before original open arrival retains endpoints" to ::stopBeforeOpenArrivalRetainsOriginalEndpoints,
            "catalog wait remains off caller and retires before source installation" to ::nativeCatalogWaitCannotBlockRetirementOrInstallLateSource,
            "foreground callback reentry retains original source claim" to ::foregroundReentryCannotCreateASecondOrRetiredSource,
            "blocked media cannot delay direct original Camera2 stop" to ::blockedMediaCannotDelayDirectOriginalCameraStop,
            "rejected completion remains undelivered" to ::rejectedCompletionDoesNotInventDelivery,
            "unsupported local lease fails before any Camera2 borrow" to ::unsupportedLocalLeaseNeverReachesCameraBorrow,
            "known EOF and rejected attach recover same live use after original fences" to ::knownRoleFailuresRecoverLiveUseAfterOriginalFences,
            "shared native sibling survives exact child role recovery" to ::sharedSiblingSurvivesOneChildRecovery,
            "duplicate inline and late callbacks preserve authoritative original close facts" to ::lateOriginalCallbacksPreserveAuthoritativeCloseFacts,
            "actual close replace projection owner and current request win queued recovery" to ::currentDemandWinsQueuedRecovery,
            "failed unknown and unreturned original role closure never replays" to ::unsettledOriginalRolesCannotReplay,
        )
        for ((name, body) in cases) { println("START receiver-camera501: $name"); body(); println("PASS receiver-camera501: $name") }
        println("PASS receiver-camera501: ${cases.size} actual owner/ledger/role/Camera2/encoder host groups; explicit permission/catalog/output/codec/socket boundaries, no Android/TLS/native/capture acceptance")
    }
}
