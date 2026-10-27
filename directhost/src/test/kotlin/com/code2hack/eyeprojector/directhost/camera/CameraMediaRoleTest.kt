package com.code2hack.eyeprojector.directhost.camera

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Test identities and loopback TLS only; these never publish a production input. */
class PhoneCameraTestPeers : AutoCloseable {
    val root = Files.createTempDirectory("camera-role-test-").toFile()
    val phone = File(root, "phone")
    val receiver = File(root, "receiver")
    val listener: SSLServerSocket
    init {
        val phoneCertificate = PeerStore(phone).ensureIdentity()
        val receiverCertificate = PeerStore(receiver).ensureIdentity()
        File(phone, "peer.crt").writeBytes(receiverCertificate.encoded)
        File(receiver, "peer.crt").writeBytes(phoneCertificate.encoded)
        listener = Tls.context(phone).serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 3000
        PeerStore(receiver).writeTransportEndpoint("127.0.0.1", listener.localPort, PeerStore.tlsName(phoneCertificate))
        PeerStore(phone).writeTransportEndpoint("127.0.0.1", listener.localPort)
    }
    fun accept(): SSLSocket = (listener.accept() as SSLSocket).also {
        it.soTimeout = 3500; it.startHandshake(); Tls.requireTrustedPeer(it, phone)
    }
    override fun close() { listener.close(); root.deleteRecursively() }
}

class CameraMediaRoleTest {
    private val request = CameraStreamRequest(640, 480, 35, "video/avc")
    private class Consumer : PhoneCameraMediaConsumer {
        val descriptions = CopyOnWriteArrayList<CameraStreamDescription>()
        val units = CopyOnWriteArrayList<CameraAccessUnit>()
        val ends = CopyOnWriteArrayList<CameraMediaEnd>()
        val received = CountDownLatch(1)
        val retired = AtomicInteger()
        override fun description(original: PublishedCameraUse, stream: CameraStreamRef, value: CameraStreamDescription) { descriptions.add(value) }
        override fun accessUnit(original: PublishedCameraUse, stream: CameraStreamRef, value: CameraAccessUnit) { units.add(value); received.countDown() }
        override fun remoteEnd(original: PublishedCameraUse, value: CameraMediaEnd) { ends.add(value) }
        override fun requestRetire(original: PublishedCameraUse, reason: CameraEndReason) { retired.incrementAndGet() }
    }
    private data class Opened(val socket: SSLSocket, val role: PendingCameraRole, val disposition: CameraAttachDisposition) : AutoCloseable {
        override fun close() { socket.close() }
    }
    private fun open(peers: PhoneCameraTestPeers, owner: PhoneCameraMediaRole, ref: CameraUseRef): Opened {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            val socket = peers.accept(); val input = DataInputStream(socket.inputStream)
            assertEquals(Wire.CAMERA, Wire.readRequest(input))
            val transfer = owner.takePendingSocket(socket, input, CapturedTrustedPeer.authenticated(socket))
            assertTrue(transfer.claimed); transfer.pending!!.also(owner::startPending)
        })
        try {
            val client = Tls.connect(peers.receiver)
            val output = DataOutputStream(client.outputStream)
            Wire.writeRequest(output, Wire.CAMERA); CameraFeed.writeHello(output, ref)
            return Opened(client, future.get(3, TimeUnit.SECONDS), CameraFeed.readWelcome(DataInputStream(client.inputStream), ref))
        } finally { executor.shutdownNow() }
    }
    private fun joined(role: PendingCameraRole): PhoneCameraRoleObservation {
        val done = CountDownLatch(1); var observation: PhoneCameraRoleObservation? = null
        role.closeAsync(Executor { it.run() }) { observation = it; done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS)); return observation!!
    }
    @Test fun emptyRegistryAbsentConsumerAndCloseBeforeAttachAreTruthful() {
        PhoneCameraTestPeers().use { peers ->
            val controls = CopyOnWriteArrayList<Frame>(); val owner = PhoneCameraMediaRole("owner", controls::add)
            try {
                open(peers, owner, CameraUseRef("owner", "unknown", 0)).use {
                    assertEquals(CameraAttachDisposition.UNKNOWN_ORIGINAL_USE, it.disposition); joined(it.role)
                }
                val group = object : OriginalPhoneDemandGroup {}
                val original = owner.publishConfigured(group, request, null)
                assertEquals(CameraFact.UNAVAILABLE, owner.start(original, request).fact)
                open(peers, owner, original.ref).use {
                    assertEquals(CameraAttachDisposition.PHONE_CONSUMER_UNAVAILABLE, it.disposition); joined(it.role)
                }
                val consumer = Consumer(); assertEquals(CameraFact.OBSERVED_RETURN, owner.bindConsumer(original, consumer).fact)
                val forged = PublishedCameraUse(owner, group, original.ref, request, consumer)
                assertEquals(CameraFact.STALE_OR_DUPLICATE, owner.start(forged, request).fact)
                assertEquals(CameraFact.REJECTED, owner.bindConsumer(original, Consumer()).fact)
                owner.retire(original, CameraEndReason.ORIGINAL_INPUT_CLOSED)
                open(peers, owner, original.ref).use {
                    assertEquals(CameraAttachDisposition.ORIGINAL_USE_RETIRED, it.disposition); joined(it.role)
                }
            } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED) }
        }
    }
    @Test fun actualRoleDeliversLargeCopiedMediaAndStreamEndLeavesOtherStreamLive() {
        PhoneCameraTestPeers().use { peers ->
            val consumer = Consumer(); val owner = PhoneCameraMediaRole("owner", {})
            val original = owner.publishConfigured(object : OriginalPhoneDemandGroup {}, request, consumer)
            owner.start(original, request)
            try {
                open(peers, owner, original.ref).use { opened ->
                    assertEquals(CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE, opened.disposition)
                    val output = DataOutputStream(opened.socket.outputStream)
                    val first = CameraStreamRef(original.ref, 0, 0); val second = first.copy(encoderGeneration = 1)
                    val description = CameraStreamDescription(request, "video/avc", null, null, null, 3, CameraFraming.SOURCE_BYTES_UNCLASSIFIED,
                        listOf(CameraCodecData("csd-0", ByteArray(Wire.MAX_PAYLOAD + 3) { 4 })))
                    for (ref in listOf(first, second)) CameraFeed.descriptionFrames(ref, description).forEach { Wire.writeFrame(output, it) }
                    Wire.writeFrame(output, Frame(Wire.CAMERA_END, payload = CameraFeed.endBytes(CameraMediaEnd(original.ref, 0L to 0L, CameraEndReason.ORIGINAL_RETIRED))))
                    val au = CameraAccessUnit(3, 7, -100, Int.MIN_VALUE, ByteArray(Wire.MAX_PAYLOAD + 31) { it.toByte() })
                    CameraFeed.mediaFrames(second, au).forEach { Wire.writeFrame(output, it) }
                    assertTrue(consumer.received.await(3, TimeUnit.SECONDS))
                    assertEquals(2, consumer.descriptions.size); assertArrayEquals(au.data, consumer.units.single().data)
                    assertEquals(-100L, consumer.units.single().ptsUs); assertEquals(Int.MIN_VALUE, consumer.units.single().codecFlags)
                    val snapshot = joined(opened.role)
                    assertTrue(snapshot.readerReturned); assertTrue(snapshot.readerJoined)
                    assertTrue(snapshot.writerReturned); assertTrue(snapshot.writerJoined); assertEquals(true, snapshot.socketCloseReturned)
                    assertEquals(0, consumer.retired.get()) // Role end alone does not retire the live original demand.
                }
            } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED) }
        }
    }
    @Test fun mediaBeforeDescriptionFailsOnlyOriginalRoleAndFreshAttemptIsAllowed() {
        PhoneCameraTestPeers().use { peers ->
            val owner = PhoneCameraMediaRole("owner", {}); val consumer = Consumer()
            val original = owner.publishConfigured(object : OriginalPhoneDemandGroup {}, request, consumer)
            try {
                open(peers, owner, original.ref).use { opened ->
                    val ref = CameraStreamRef(original.ref, 0, 0)
                    Wire.writeFrame(DataOutputStream(opened.socket.outputStream), CameraFeed.mediaFrames(ref, CameraAccessUnit(0, 0, 0, 0, byteArrayOf(1))).single())
                    val reader = opened.role.reader!!; reader.join(3000); assertFalse(reader.isAlive)
                    assertEquals(CameraEndReason.PROTOCOL_FAILURE, opened.role.snapshot().failure)
                    joined(opened.role)
                }
                open(peers, owner, original.ref).use { assertEquals(CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE, it.disposition); joined(it.role) }
                val replacement = owner.replace(original, object : OriginalPhoneDemandGroup {}, request, Consumer())
                assertEquals(CameraFact.STALE_OR_DUPLICATE, owner.start(original, request).fact)
                open(peers, owner, replacement.ref).use { assertEquals(CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE, it.disposition); joined(it.role) }
            } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED) }
        }
    }
    @Test fun projectionWriteFailureDoesNotAppendOrReplayControls() {
        val failed = CountDownLatch(1); val writes = AtomicInteger()
        val owner = PhoneCameraMediaRole("owner", { writes.incrementAndGet(); throw IOException("test partial write") }, projectionFailure = { failed.countDown() })
        try {
            val original = owner.publishConfigured(object : OriginalPhoneDemandGroup {}, request, Consumer())
            owner.start(original, request); owner.update(original, request.copy(frameRate = 1))
            assertTrue(failed.await(3, TimeUnit.SECONDS)); assertEquals(1, writes.get()); assertTrue(owner.controlFailure)
        } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED) }
    }
}
