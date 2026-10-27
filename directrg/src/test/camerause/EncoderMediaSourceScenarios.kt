package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import android.media.CodecOutput
import android.media.CodecProbe
import android.media.MediaCodec
import android.media.MediaFormat
import com.code2hack.eyeprojector.directrg.localcamera.CameraSourceKey
import com.code2hack.eyeprojector.directrg.localcamera.CameraUseKey
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.ForwardCameraStream
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.RequestedCameraOutput
import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual hub/client/S1 bodies with explicit codec/Surface/TLS boundaries; no device/media proof. */
private const val WAIT_SECONDS = 5L
private val direct = Executor { it.run() }
private val clients = ArrayList<ReceiverCameraRoleClient>()
private fun await(latch: CountDownLatch) { check(latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Fixture latch did not arrive" } }
private fun eventLane(client: ReceiverCameraRoleClient): ExecutorService =
    ReceiverCameraRoleClient::class.java.getDeclaredField("events").let {
        it.isAccessible = true; it.get(client) as ExecutorService
    }
private fun barrier(client: ReceiverCameraRoleClient) { eventLane(client).submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS) }
private fun until(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
    while (!condition()) { check(System.nanoTime() < deadline) { message }; Thread.yield() }
}
private class Job<T>(name: String, action: () -> T) {
    private val task = FutureTask(Callable { action() })
    val thread = Thread(task, name).apply { isDaemon = true; start() }
    fun result(): T { val value = task.get(WAIT_SECONDS, TimeUnit.SECONDS); thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)); check(!thread.isAlive); return value }
    val done get() = task.isDone
}
private val request = CameraStreamRequest(640, 480, 37, "image/phone-request", 700_000, 24, 2)
private fun use(client: ReceiverCameraRoleClient, generation: Long = 1): CapturedPhoneCameraUse {
    val ref = CameraUseRef("owner501", "actual-session501", generation)
    val original = client.observe(CameraUseMessage.Prepare(ref, request)).original!!
    client.observe(CameraUseMessage.Start(ref, request))
    return original
}
private fun client(send: (Frame) -> Unit = {}): ReceiverCameraRoleClient =
    ReceiverCameraRoleClient(File("unused-focused-camera501"), "owner501", send).also { clients.add(it) }
private fun dispose(client: ReceiverCameraRoleClient) {
    client.retireAll(CameraEndReason.PROJECTION_CLOSED)
    check(eventLane(client).awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
}
private fun newEncoder(hub: EncoderCameraMediaSource): CameraSurfaceEncoder = CameraSurfaceEncoder(
    SurfaceEncoderRequest(ForwardCameraStream(CameraSourceKey(CameraUseKey("actual-projection501", 31), 47), 9),
        RequestedCameraOutput(640, 480, 0x7F000789), 700_000, 24, 2), hub).also(hub::bindEncoder)
private fun format(encoder: CameraSurfaceEncoder, generation: Long = 4,
    width: Int? = 611, height: Int? = 457): EncoderFormatObservation =
    EncoderFormatObservation(encoder.identity, generation, "actual-double-avc501", width, height,
        mapOf("csd-0" to EncodedByteSnapshot(byteArrayOf(1, 7, 13))), 823L)
private fun packet(encoder: CameraSurfaceEncoder, generation: Long? = 4, sequence: Long = 19): EncoderAccessUnit =
    EncoderAccessUnit(encoder.identity, encoder.stream, generation, sequence, -7123, 5,
        EncodedByteSnapshot(byteArrayOf(9, 3, 8)), 991L)
private fun attachment(hub: EncoderCameraMediaSource, original: CapturedPhoneCameraUse,
    source: Long = 12, encoder: Long = 16): EncoderCameraMediaSource.Attachment =
    hub.attach(original, CameraStreamRef(original.ref, source, encoder), original.request)
private fun publishFormat(hub: EncoderCameraMediaSource, encoder: CameraSurfaceEncoder,
    fact: EncoderFormatObservation = format(encoder)): Job<Unit> =
    Job("fixture501-format") { hub.format(encoder, fact) }
private fun pending(hub: EncoderCameraMediaSource, count: Int) =
    until("Expected actual pending handoff for $count attachments") { hub.snapshot().waitingAttachments == count }

private fun actualEncoderToTwoRoleViews() {
    CodecProbe.reset()
    val client = client()
    val failures = Collections.synchronizedList(ArrayList<Throwable>())
    val hub = EncoderCameraMediaSource({ _, _ -> }, { failures.add(it) })
    val encoder = newEncoder(hub)
    val first = attachment(hub, use(client, 1))
    val codec = MediaCodec("actual-codec-boundary501")
    CodecProbe.supply(codec)
    val sourceCsd = byteArrayOf(4, 5, 6)
    val observed = MediaFormat.createVideoFormat("video/avc", 619, 461).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(sourceCsd))
    }
    codec.output(CodecOutput.Format(observed))
    val ready = CountDownLatch(1)
    encoder.prepareAsync(direct) { check(it.ready); ready.countDown() }
    await(ready)
    val description = first.next() as CameraOutboundRecord.Description
    sourceCsd[0] = 88
    check(description.value.mime == "video/avc" && description.value.request.requestedMime == "image/phone-request")
    check(description.value.observedWidth == 619 && description.value.observedHeight == 461)
    check(description.value.csd.single().value.contentEquals(byteArrayOf(4, 5, 6)))
    val second = attachment(hub, use(client, 2), 33, 42)
    val cached = second.next() as CameraOutboundRecord.Description
    check(cached.ref == second.ref && cached.value.formatGeneration == description.value.formatGeneration)
    val sourceBytes = byteArrayOf(99, 10, 11, 12, 98)
    codec.output(CodecOutput.Buffer(6, ByteBuffer.wrap(sourceBytes), 1, 3, -7001, 5) { sourceBytes.fill(0) })
    val a = first.next() as CameraOutboundRecord.AccessUnit
    val b = second.next() as CameraOutboundRecord.AccessUnit
    check(a.ref == first.ref && b.ref == second.ref && a.ref != b.ref)
    check(a.value.sequence == b.value.sequence && a.value.formatGeneration == description.value.formatGeneration)
    check(a.value.ptsUs == -7001L && a.value.codecFlags == 5 && a.value.data.contentEquals(byteArrayOf(10, 11, 12)))
    check(b.value.data.contentEquals(a.value.data) && CodecProbe.bufferReleases.single().index == 6)
    val exposed = a.value.data; exposed.fill(44); check(a.value.data.contentEquals(byteArrayOf(10, 11, 12)))
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED)
    val closed = CountDownLatch(1)
    encoder.closeAsync(direct) { check(it.ownership.workerThreadClosedObserved); check(it.ownership.eosObserved); closed.countDown() }
    await(closed)
    check(failures.isEmpty())
    dispose(client)
}

private fun rendezvousNeedsBothCapturedMembers() {
    val client = client(); val hub = EncoderCameraMediaSource({ _, _ -> }, { throw AssertionError(it) })
    val encoder = newEncoder(hub); val a = attachment(hub, use(client, 1)); val b = attachment(hub, use(client, 2))
    val format = publishFormat(hub, encoder); pending(hub, 2)
    check(a.next() is CameraOutboundRecord.Description); pending(hub, 1); check(!format.done)
    check(b.next() is CameraOutboundRecord.Description); format.result()
    val output = Job("fixture501-packet") { hub.packet(encoder, packet(encoder)) }; pending(hub, 2)
    val actualA = a.next() as CameraOutboundRecord.AccessUnit; pending(hub, 1); check(!output.done)
    val actualB = b.next() as CameraOutboundRecord.AccessUnit; output.result()
    check(actualA.value.sequence == 19L && actualB.value.ptsUs == -7123L)
    check(hub.snapshot().publications == 2L)
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); dispose(client)
}

private fun childWithdrawalKeepsSiblingLive() {
    val client = client(); val retired = Collections.synchronizedList(ArrayList<EncoderCameraMediaSource.Attachment>())
    val hub = EncoderCameraMediaSource({ a, _ -> retired.add(a) }, { throw AssertionError(it) })
    val encoder = newEncoder(hub); val a = attachment(hub, use(client, 1)); val b = attachment(hub, use(client, 2))
    val job = publishFormat(hub, encoder); pending(hub, 2)
    a.requestRetire(CameraEndReason.TRANSPORT_EOF); pending(hub, 1)
    check(!job.done && a.next() == null && b.snapshot().retiredReason == null)
    check(b.next() is CameraOutboundRecord.Description); job.result()
    a.requestRetire(CameraEndReason.ORIGINAL_RETIRED); check(retired.count { it === a } == 1)
    check(hub.snapshot().retiredReason == null)
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); check(retired.count { it === b } == 1); dispose(client)
}

private fun oldAttachmentCannotIdentifySameUseSuccessor() {
    val client = client(); val retired = ArrayList<EncoderCameraMediaSource.Attachment>()
    val hub = EncoderCameraMediaSource({ a, _ -> retired.add(a) }, { throw AssertionError(it) }); newEncoder(hub)
    val original = use(client); val old = attachment(hub, original); val successor = attachment(hub, original)
    check(old !== successor && old.original === successor.original && old.ref == successor.ref)
    old.requestRetire(CameraEndReason.TRANSPORT_EOF)
    check(retired.single() === old && successor.snapshot().retiredReason == null)
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); dispose(client)
}

private fun sourceRetirementWakesWriterAndPublisher() {
    val client = client(); val hub = EncoderCameraMediaSource({ _, _ -> }, { throw AssertionError(it) })
    val encoder = newEncoder(hub); val first = attachment(hub, use(client, 1)); val waiting = Job("fixture501-next") { first.next() }
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); check(waiting.result() == null)
    check(first.next() == null && hub.snapshot().pendingPublication == null)
    val secondHub = EncoderCameraMediaSource({ _, _ -> }, { throw AssertionError(it) }); val secondEncoder = newEncoder(secondHub)
    attachment(secondHub, use(client, 2)); val publisher = publishFormat(secondHub, secondEncoder); pending(secondHub, 1)
    secondHub.retireSource(CameraEndReason.ORIGINAL_RETIRED); publisher.result()
    check(secondHub.snapshot().pendingPublication == null && secondHub.snapshot().waitingAttachments == 0)
    check(encoder.identity !== secondEncoder.identity); dispose(client)
}

private fun unknownFormatNeverAdoptsLaterGeneration() {
    val client = client(); val failures = ArrayList<Throwable>()
    val hub = EncoderCameraMediaSource({ _, _ -> }, { failures.add(it) }); val encoder = newEncoder(hub)
    val a = attachment(hub, use(client)); hub.packet(encoder, packet(encoder, null))
    check(failures.size == 1 && hub.snapshot().actualFormatGeneration == null)
    check(hub.snapshot().publications == 0L && a.next() == null)
    hub.format(encoder, format(encoder, 91)); check(hub.snapshot().actualFormatGeneration == null)
    dispose(client)
}

private fun foreignEncoderCallbacksCannotReplaceBoundIdentity() {
    val client = client(); val failures = ArrayList<Throwable>()
    val hub = EncoderCameraMediaSource({ _, _ -> }, { failures.add(it) }); val original = newEncoder(hub)
    val otherHub = EncoderCameraMediaSource({ _, _ -> }, { failures.add(it) }); val other = newEncoder(otherHub)
    val a = attachment(hub, use(client))
    hub.format(other, format(other)); hub.packet(original, packet(other)); hub.observed(other, other.snapshot)
    check(hub.snapshot().ignoredCallbacks == 3L && hub.snapshot().encoder === original.identity)
    check(hub.snapshot().actualFormatGeneration == null && failures.isEmpty())
    val job = publishFormat(hub, original); check(a.next() is CameraOutboundRecord.Description); job.result()
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); otherHub.retireSource(CameraEndReason.PROJECTION_CLOSED); dispose(client)
}

private fun observedDimensionsAndGenerationStayActual() {
    val client = client(); val failures = ArrayList<Throwable>()
    val hub = EncoderCameraMediaSource({ _, _ -> }, { failures.add(it) }); val encoder = newEncoder(hub)
    val original = use(client); val a = attachment(hub, original)
    val format = publishFormat(hub, encoder, format(encoder, 24, null, null))
    val description = a.next() as CameraOutboundRecord.Description; format.result()
    check(description.value.observedWidth == null && description.value.observedHeight == null)
    check(description.value.request === original.request && description.value.framing == CameraFraming.SOURCE_BYTES_UNCLASSIFIED)
    hub.packet(encoder, packet(encoder, 25)); check(failures.size == 1 && a.next() == null)
    check(hub.snapshot().actualFormatGeneration == 24L); dispose(client)
}

private fun callbacksReenterOutsideMonitorAndKeepExactChild() {
    val client = client(); val original = use(client); val failures = ArrayList<Throwable>()
    lateinit var hub: EncoderCameraMediaSource
    var replacement: EncoderCameraMediaSource.Attachment? = null
    val marker = AssertionError("member observer501")
    hub = EncoderCameraMediaSource({ child, _ ->
        if (replacement == null && child.original === original) {
            replacement = Job("fixture501-reentrant-attach") { attachment(hub, original) }.result()
            throw marker
        }
    }, { failures.add(it) })
    newEncoder(hub); val old = attachment(hub, original)
    old.requestRetire(CameraEndReason.TRANSPORT_EOF)
    check(replacement !== old && replacement!!.snapshot().retiredReason == null)
    check(failures.single() === marker && hub.snapshot().failures.single() === marker)
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); dispose(client)
}

private fun joinObservationNeverWaitsForPacketConsumer() {
    val client = client(); val hub = EncoderCameraMediaSource({ _, _ -> }, { throw AssertionError(it) })
    val encoder = newEncoder(hub); attachment(hub, use(client))
    val publisher = publishFormat(hub, encoder); pending(hub, 1)
    val observed = Job("fixture501-actual-observation-lane") { hub.observed(encoder, encoder.snapshot) }; observed.result()
    check(hub.snapshot().lifetime!!.encoder === encoder.identity && !publisher.done)
    hub.retireSource(CameraEndReason.PROJECTION_CLOSED); publisher.result(); dispose(client)
}

private fun progressBeforeRoleKeepsActualPayloadAndSubmission() {
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val written = ArrayList<Frame>()
    val client = client { check(Thread.currentThread().name == "eyeprojector-camera-events"); entered.countDown(); await(release); written.add(it) }
    val original = use(client)
    val value = CameraProgress(original.ref, null, CameraStage.PERMISSION, CameraFact.REJECTED,
        CameraClock.ANDROID_ELAPSED_REALTIME_NANOS, 501L)
    check(client.reportProgress(original, value) == CameraFact.PENDING); await(entered)
    val pending = client.progressSubmissions().single()
    check(pending.original === original && pending.value === value && pending.actionStarted && pending.actionReturned == null)
    check(client.snapshot().isEmpty())
    release.countDown(); barrier(client)
    val returned = client.progressSubmissions().single()
    check(returned.fact == CameraFact.OBSERVED_RETURN && returned.actionReturned == true)
    check(CameraFeed.readProgress(written.single().payload) == value)
    dispose(client)
}

private fun progressRejectsForeignRefAndRetiredOriginal() {
    val sent = AtomicInteger(); val client = client { sent.incrementAndGet() }; val other = client()
    val original = use(client); val foreign = use(other)
    val value = CameraProgress(original.ref, null, CameraStage.SOURCE_PREPARATION, CameraFact.PENDING)
    check(client.reportProgress(foreign, value) == CameraFact.STALE_OR_DUPLICATE)
    check(client.reportProgress(original, value.copy(use = original.ref.copy(useGeneration = 72))) == CameraFact.STALE_OR_DUPLICATE)
    client.observe(CameraUseMessage.Close(original.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    check(client.reportProgress(original, value) == CameraFact.STALE_OR_DUPLICATE)
    check(sent.get() == 0 && client.progressSubmissions().all { !it.actionStarted && it.submissionReturned == null })
    dispose(client); dispose(other)
}

private fun progressFailureDoesNotContaminateLaterSubmission() {
    val marker = AssertionError("first actual projection-write501"); val count = AtomicInteger()
    val client = client { if (count.incrementAndGet() == 1) throw marker }
    val original = use(client); val value = CameraProgress(original.ref, null, CameraStage.FOREGROUND_CAMERA, CameraFact.OBSERVED_RETURN)
    client.reportProgress(original, value); barrier(client)
    check(client.eventDeliveryFailed && client.progressSubmissions().single().failure === marker)
    client.reportProgress(original, value.copy(stage = CameraStage.SOURCE_START)); barrier(client)
    val attempts = client.progressSubmissions()
    check(attempts[0].fact == CameraFact.FAILED && attempts[1].fact == CameraFact.OBSERVED_RETURN)
    eventLane(client).shutdown(); check(eventLane(client).awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
    check(original.live && client.reportProgress(original, value) == CameraFact.REJECTED)
    check(client.progressSubmissions().last().submissionReturned == false)
    dispose(client)
}

private fun queuedProgressRetirementNeverAdoptsSuccessor() {
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val written = ArrayList<CameraProgress>()
    val client = client { val value = CameraFeed.readProgress(it.payload); written.add(value)
        if (written.size == 1) { entered.countDown(); await(release) } }
    val old = use(client, 1); val next = use(client, 2)
    val oldFact = CameraProgress(old.ref, null, CameraStage.PERMISSION, CameraFact.PENDING)
    client.reportProgress(old, oldFact); await(entered)
    client.reportProgress(old, oldFact.copy(stage = CameraStage.SOURCE_PREPARATION))
    client.observe(CameraUseMessage.Close(old.ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))
    client.reportProgress(next, CameraProgress(next.ref, null, CameraStage.PERMISSION, CameraFact.OBSERVED_RETURN))
    release.countDown(); barrier(client)
    val attempts = client.progressSubmissions()
    check(attempts[1].original === old && attempts[1].fact == CameraFact.STALE_OR_DUPLICATE && attempts[1].actionReturned == null)
    check(attempts[2].original === next && attempts[2].fact == CameraFact.OBSERVED_RETURN)
    check(written.size == 2 && written.last().use == next.ref && next.live)
    dispose(client)
}

object EncoderMediaSource501Scenarios {
    @JvmStatic fun main(args: Array<String>) {
        val cases = listOf(
            "actual encoder bytes and cached format reach two original role views" to ::actualEncoderToTwoRoleViews,
            "rendezvous waits each captured member" to ::rendezvousNeedsBothCapturedMembers,
            "child withdrawal preserves compatible sibling" to ::childWithdrawalKeepsSiblingLive,
            "same-use successor retains exact attachment identity" to ::oldAttachmentCannotIdentifySameUseSuccessor,
            "source retirement wakes writer and publisher" to ::sourceRetirementWakesWriterAndPublisher,
            "unknown format never adopts later generation" to ::unknownFormatNeverAdoptsLaterGeneration,
            "foreign encoder callbacks cannot replace bound identity" to ::foreignEncoderCallbacksCannotReplaceBoundIdentity,
            "observed dimensions framing and generation stay actual" to ::observedDimensionsAndGenerationStayActual,
            "member callback reenters outside monitor with exact child" to ::callbacksReenterOutsideMonitorAndKeepExactChild,
            "join observation cannot wait on packet consumer" to ::joinObservationNeverWaitsForPacketConsumer,
            "pre-role progress keeps payload and submission distinct" to ::progressBeforeRoleKeepsActualPayloadAndSubmission,
            "progress rejects foreign mismatched and retired originals" to ::progressRejectsForeignRefAndRetiredOriginal,
            "earlier failure does not contaminate later progress submission" to ::progressFailureDoesNotContaminateLaterSubmission,
            "queued old progress cannot adopt successor" to ::queuedProgressRetirementNeverAdoptsSuccessor,
        )
        try {
            for ((name, body) in cases) { println("START encoder-media501: $name"); body(); println("PASS encoder-media501: $name") }
        } finally {
            // Failure-only harness cleanup does not assert product source/resource closure.
            clients.forEach {
                it.retireAll(CameraEndReason.PROJECTION_CLOSED)
                if (!eventLane(it).isTerminated) eventLane(it).shutdownNow()
                eventLane(it).awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }
        println("PASS encoder-media501: ${cases.size} actual hub/client/S1 host groups with explicit codec, Surface and TLS boundaries; no native AVC, Camera2, TLS/network, Phone capture or whole issue proof")
    }
}
