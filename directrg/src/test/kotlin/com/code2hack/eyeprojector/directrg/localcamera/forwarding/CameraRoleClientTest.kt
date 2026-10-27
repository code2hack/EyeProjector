package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.net.ssl.*

private class ReceiverCameraTestPeers : AutoCloseable {
    val root = Files.createTempDirectory("camera-client-test-").toFile()
    val phone = File(root, "phone"); val receiver = File(root, "receiver")
    val listener: SSLServerSocket
    init {
        val p = PeerStore(phone).ensureIdentity(); val r = PeerStore(receiver).ensureIdentity()
        File(phone, "peer.crt").writeBytes(r.encoded); File(receiver, "peer.crt").writeBytes(p.encoded)
        listener = Tls.context(phone).serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 3000
        PeerStore(receiver).writeTransportEndpoint("127.0.0.1", listener.localPort, PeerStore.tlsName(p))
    }
    fun accept(ref: CameraUseRef): SSLSocket = (listener.accept() as SSLSocket).also {
        it.soTimeout = 3500; it.startHandshake(); Tls.requireTrustedPeer(it, phone)
        val input = DataInputStream(it.inputStream); assertEquals(Wire.CAMERA, Wire.readRequest(input)); assertEquals(ref, CameraFeed.readHello(input))
        CameraFeed.writeWelcome(DataOutputStream(it.outputStream), ref, CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE)
        it.soTimeout = 0
    }
    override fun close() { listener.close(); root.deleteRecursively() }
}

class CameraRoleClientTest {
    private val ref = CameraUseRef("phone", "original-session", 0)
    private val request = CameraStreamRequest(640, 480, 35, "video/avc")
    private class Source : CameraOutboundMediaSource {
        val items = LinkedBlockingQueue<Any>(); private val end = Any()
        val retires = AtomicInteger(); val retired = CountDownLatch(1)
        val reasons = CopyOnWriteArrayList<CameraEndReason>()
        var blockRetire: CountDownLatch? = null
        override fun next(): CameraOutboundRecord? = items.take().let { if (it === end) null else it as CameraOutboundRecord }
        override fun requestRetire(reason: CameraEndReason) {
            retires.incrementAndGet(); reasons.add(reason); items.add(end); retired.countDown(); blockRetire?.await()
        }
    }
    private class Events : CameraRoleEvents {
        val observations = CopyOnWriteArrayList<RoleCloseObservation>()
        val ends = CopyOnWriteArrayList<CameraMediaEnd>()
        override fun changed(role: OwnedCameraRole, value: RoleCloseObservation) { observations.add(value) }
        override fun remoteEnd(role: OwnedCameraRole, value: CameraMediaEnd) { ends.add(value) }
    }
    private fun original(owner: ReceiverCameraRoleClient) = owner.observe(CameraUseMessage.Prepare(ref, request)).original!!
    private fun joined(role: OwnedCameraRole): RoleCloseObservation {
        val done = CountDownLatch(1); var result: RoleCloseObservation? = null
        role.closeAsync(Executor { it.run() }) { result = it; done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS)); return result!!
    }
    @Test fun actualPinnedClientWritesLargeFragmentsAndPreservesSourceFacts() {
        ReceiverCameraTestPeers().use { peers ->
            val progress = CopyOnWriteArrayList<Frame>(); val owner = ReceiverCameraRoleClient(peers.receiver, "phone", { progress.add(it) })
            val source = Source(); val events = Events(); val use = original(owner)
            val stream = CameraStreamRef(ref, 0, 7)
            val description = CameraStreamDescription(request, "video/avc", "actual-test-source", 640, 480, 9,
                CameraFraming.SOURCE_BYTES_UNCLASSIFIED, listOf(CameraCodecData("csd-0", ByteArray(Wire.MAX_PAYLOAD + 1) { 42 })))
            val au = CameraAccessUnit(9, 0, -1000, 0x12345678, ByteArray(Wire.MAX_PAYLOAD + 27) { it.toByte() })
            source.items.add(CameraOutboundRecord.Description(stream, description)); source.items.add(CameraOutboundRecord.AccessUnit(stream, au))
            source.items.add(CameraOutboundRecord.End(CameraMediaEnd(ref, null, CameraEndReason.ORIGINAL_RETIRED, 1, 0)))
            val server = Executors.newSingleThreadExecutor()
            val received = server.submit(Callable {
                peers.accept(ref).use { socket ->
                    val input = DataInputStream(socket.inputStream); val assembler = CameraFragmentAssembler()
                    var seenDescription: CameraStreamDescription? = null; var seenAu: CameraAccessUnit? = null
                    while (true) {
                        val frame = Wire.readFrame(input)
                        when (frame.channel) {
                            Wire.CAMERA_STREAM -> assembler.description(CameraFeed.readStream(frame.payload))?.let { seenDescription = it }
                            Wire.CAMERA_MEDIA -> assembler.media(CameraFeed.readMedia(frame.payload))?.let { seenAu = it }
                            Wire.CAMERA_END -> { assertEquals(false, CameraFeed.readEnd(frame.payload).fact(0)); break }
                            else -> error("Wrong client channel")
                        }
                    }
                    seenDescription!! to seenAu!!
                }
            })
            try {
                val role = owner.connect(use, source, events)
                val delivered = received.get(5, TimeUnit.SECONDS)
                assertArrayEquals(description.csd.single().value, delivered.first.csd.single().value)
                assertArrayEquals(au.data, delivered.second.data); assertEquals(au.ptsUs, delivered.second.ptsUs); assertEquals(au.codecFlags, delivered.second.codecFlags)
                val complete = joined(role)
                assertTrue(complete.writerReturned); assertTrue(complete.writerJoined); assertTrue(complete.readerReturned); assertTrue(complete.readerJoined)
                assertEquals(true, complete.sourceRetireReturned); assertEquals(true, complete.socketCloseReturned)
                assertTrue(use.live); assertEquals(1, source.retires.get())
            } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED); server.shutdownNow() }
        }
    }
    @Test fun eofKeepsUseLiveButSourceIdentityCannotBeAdoptedByFreshAttempt() {
        ReceiverCameraTestPeers().use { peers ->
            val owner = ReceiverCameraRoleClient(peers.receiver, "phone", {})
            val use = original(owner); val first = Source(); val releaseRetire = CountDownLatch(1); first.blockRetire = releaseRetire
            val server = Executors.newSingleThreadExecutor()
            val closeFirst = server.submit { peers.accept(ref).close() }
            try {
                val role = owner.connect(use, first, Events())
                closeFirst.get(3, TimeUnit.SECONDS); assertTrue(first.retired.await(3, TimeUnit.SECONDS))
                assertTrue(use.live)
                assertThrows(IllegalStateException::class.java) { owner.connect(use, first, Events()) }
                val second = Source(); val attached = CountDownLatch(1); val releasePeer = CountDownLatch(1)
                val secondServer = server.submit { peers.accept(ref).use { attached.countDown(); releasePeer.await() } }
                val successor = owner.connect(use, second, Events())
                assertTrue(attached.await(3, TimeUnit.SECONDS))
                val oldDone = CountDownLatch(1)
                role.closeAsync(Executor { it.run() }) { assertTrue(it.writerJoined); oldDone.countDown() }
                assertFalse(oldDone.await(100, TimeUnit.MILLISECONDS)) // Actual retirement hook still owns an unfinished turn.
                releaseRetire.countDown(); assertTrue(oldDone.await(3, TimeUnit.SECONDS))
                assertFalse(successor.snapshot().retirementRequested); assertEquals(0, second.retires.get())
                releasePeer.countDown(); secondServer.get(3, TimeUnit.SECONDS); joined(successor)
                assertEquals(1, first.retires.get()); assertEquals(1, second.retires.get())
            } finally { releaseRetire.countDown(); owner.retireAll(CameraEndReason.PROJECTION_CLOSED); server.shutdownNow() }
        }
    }
    @Test fun originalCloseAndProjectionEndRetirePendingHelloWithoutBlockingCaller() {
        ReceiverCameraTestPeers().use { peers ->
            val owner = ReceiverCameraRoleClient(peers.receiver, "phone", {})
            val use = original(owner); val source = Source(); val releaseHook = CountDownLatch(1); source.blockRetire = releaseHook
            val server = Executors.newSingleThreadExecutor(); val hello = CountDownLatch(1); val releasePeer = CountDownLatch(1)
            val accepted = server.submit {
                (peers.listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 3500; socket.startHandshake(); Tls.requireTrustedPeer(socket, peers.phone)
                    val input = DataInputStream(socket.inputStream); assertEquals(Wire.CAMERA, Wire.readRequest(input)); CameraFeed.readHello(input)
                    hello.countDown(); releasePeer.await() // No welcome.
                }
            }
            try {
                val role = owner.connect(use, source, Events()); assertTrue(hello.await(3, TimeUnit.SECONDS))
                val caller = Executors.newSingleThreadExecutor()
                try { caller.submit { owner.observe(CameraUseMessage.Close(ref, CameraEndReason.PERMISSION_DENIED)) }.get(1, TimeUnit.SECONDS) }
                finally { caller.shutdownNow() }
                assertTrue(source.retired.await(3, TimeUnit.SECONDS)); assertFalse(use.live)
                assertEquals(listOf(CameraEndReason.PERMISSION_DENIED), source.reasons)
                assertNull(role.snapshot().sourceRetireReturned)
                val forged = CapturedPhoneCameraUse(owner, use.handle)
                assertThrows(IllegalStateException::class.java) { owner.connect(forged, Source(), Events()) }
                owner.retireAll(CameraEndReason.PROJECTION_CLOSED)
                releaseHook.countDown(); releasePeer.countDown(); accepted.get(3, TimeUnit.SECONDS)
                val complete = joined(role); assertTrue(complete.writerReturned); assertTrue(complete.writerJoined)
                assertFalse(complete.readerStarted); assertNull(complete.welcome)
            } finally { releaseHook.countDown(); releasePeer.countDown(); owner.retireAll(CameraEndReason.PROJECTION_CLOSED); server.shutdownNow() }
        }
    }
    @Test fun inlineAndRejectedCompletionNeverInventDeliveryOrReleaseTwice() {
        ReceiverCameraTestPeers().use { peers ->
            val owner = ReceiverCameraRoleClient(peers.receiver, "phone", {})
            val source = Source(); val use = original(owner); val server = Executors.newSingleThreadExecutor()
            val accepted = server.submit { peers.accept(ref).close() }
            try {
                val role = owner.connect(use, source, Events()); accepted.get(3, TimeUnit.SECONDS)
                val rejected = CountDownLatch(1); val callbackCount = AtomicInteger()
                role.closeAsync(Executor { rejected.countDown(); throw RejectedExecutionException("test") }) { callbackCount.incrementAndGet() }
                assertTrue(rejected.await(3, TimeUnit.SECONDS))
                val observation = role.snapshot()
                assertTrue(observation.writerJoined)
                if (observation.readerStarted) assertTrue(observation.readerJoined) else assertFalse(observation.readerJoined)
                assertEquals(0, callbackCount.get()); assertEquals(1, source.retires.get())
                role.completions.first().task.await()
                joined(role)
                val requests = role.snapshot().completions
                assertEquals(2, requests.size); assertTrue(requests.first().submissionFailed)
                assertFalse(requests.first().callbackEntered)
            } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED); server.shutdownNow() }
        }
    }
    @Test fun blockedCloseCannotSuppressSourceRetirementAndLateSameSocketIsNotReclosed() {
        val directory = Files.createTempDirectory("camera-blocked-close-").toFile()
        val captured = CountDownLatch(1); val continueConnector = CountDownLatch(1)
        val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1); val closeCount = AtomicInteger()
        val socket = object : Socket() {
            override fun close() { closeCount.incrementAndGet(); closing.countDown(); releaseClose.await(); throw IOException("actual test close failure") }
        }
        val owner = ReceiverCameraRoleClient(directory, "phone", {}, connector = { _, opened ->
            opened(socket); captured.countDown(); continueConnector.await()
            opened(socket) // Same captured object returns after retirement; its close duty is already claimed.
            throw IOException("original attempt retired")
        })
        val source = Source(); val use = original(owner)
        try {
            val role = owner.connect(use, source, Events()); assertTrue(captured.await(2, TimeUnit.SECONDS))
            role.requestStop(CameraEndReason.ORIGINAL_RETIRED)
            assertTrue(closing.await(2, TimeUnit.SECONDS)); assertTrue(source.retired.await(2, TimeUnit.SECONDS))
            assertNull(role.snapshot().socketCloseReturned)
            continueConnector.countDown()
            val complete = CountDownLatch(1)
            role.closeAsync(Executor { it.run() }) { assertEquals(false, it.socketCloseReturned); assertTrue(it.writerJoined); complete.countDown() }
            assertFalse(complete.await(100, TimeUnit.MILLISECONDS))
            releaseClose.countDown(); assertTrue(complete.await(3, TimeUnit.SECONDS))
            assertEquals(1, closeCount.get()); assertEquals(1, source.retires.get())
            assertEquals(listOf(false), role.snapshot().socketCloseResults)
        } finally { continueConnector.countDown(); releaseClose.countDown(); owner.retireAll(CameraEndReason.PROJECTION_CLOSED); directory.deleteRecursively() }
    }
    @Test fun failedThreadStartKeepsOriginalRetirementAndUnstartedJoinFacts() {
        val directory = Files.createTempDirectory("camera-start-failure-").toFile()
        val connected = AtomicInteger()
        val owner = ReceiverCameraRoleClient(directory, "phone", {}, connector = { _, _ -> connected.incrementAndGet(); error("must not connect") },
            launch = { throw RejectedExecutionException("test start failure") })
        val source = Source()
        try {
            val role = owner.connect(original(owner), source, Events())
            assertTrue(source.retired.await(2, TimeUnit.SECONDS)); assertEquals(0, connected.get())
            val observed = joined(role)
            assertFalse(observed.writerStarted); assertFalse(observed.writerReturned); assertFalse(observed.writerJoined)
            assertEquals(CameraEndReason.LOCAL_RELEASE_FAILURE, observed.failure); assertEquals(1, source.retires.get())
        } finally { owner.retireAll(CameraEndReason.PROJECTION_CLOSED); directory.deleteRecursively() }
    }
}
