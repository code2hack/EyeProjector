package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.camera.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.net.ssl.SSLSocket

/** Constructs the actual Session without running its Android stock-capture setup. */
private class CameraBridgeOriginal(peers: PhoneCameraTestPeers, ownerEpoch: String) : AutoCloseable {
    private val type = Class.forName("com.code2hack.eyeprojector.directhost.Session")
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val client: SSLSocket
    val session: Any
    init {
        val accepting = Executors.newSingleThreadExecutor()
        try {
            val server = accepting.submit(Callable { peers.accept() })
            client = Tls.connect(peers.receiver)
            val peer = server.get(3, TimeUnit.SECONDS)
            session = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
                peer, DataInputStream(peer.inputStream), peers.phone, File(peers.phone, "unused.jar"), timer, ownerEpoch, {} as () -> Unit)
        } finally { accepting.shutdownNow() }
    }
    fun field(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(session)
    override fun close() {
        type.getDeclaredMethod("close", SessionStopReason::class.java).apply { isAccessible = true }.invoke(session, SessionStopReason.OWNER_CLOSE)
        client.close(); timer.shutdownNow()
        assertTrue((field("nativeSetup") as ExecutorService).awaitTermination(3, TimeUnit.SECONDS))
    }
}

/** Actual Bridge.serve/Session ownership; only stock Android display/process resources are modeled. */
class CameraBridgeDispatchTest {
    @Test fun noOriginalSessionRejectsCameraWithoutWaitingForHello() {
        PhoneCameraTestPeers().use { peers ->
            val timer = Executors.newSingleThreadScheduledExecutor()
            val bridge = Bridge(peers.phone, File(peers.phone, "unused.jar"), timer, peers.listener, {})
            val serve = bridge.javaClass.getDeclaredMethod("serve", SSLSocket::class.java).apply { isAccessible = true }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val accepted = executor.submit { serve.invoke(bridge, peers.listener.accept() as SSLSocket) }
                Tls.connect(peers.receiver).use { socket ->
                    Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.CAMERA)
                    assertEquals(-1, socket.inputStream.read()) // No hello, scrcpy welcome or projection dispatch.
                }
                accepted.get(2, TimeUnit.SECONDS)
            } finally { bridge.close(); executor.shutdownNow(); timer.shutdownNow() }
        }
    }
    @Test fun stalledCameraHellosLeaveActualSecondGenericWorkerAvailableForStop() {
        PhoneCameraTestPeers().use { peers -> CameraBridgeOriginal(peers, "bridge-owner").use { fixture ->
            val statuses = CopyOnWriteArrayList<String>()
            val timer = Executors.newSingleThreadScheduledExecutor()
            val bridge = Bridge(peers.phone, File(peers.phone, "unused.jar"), timer, peers.listener, statuses::add, "bridge-owner")
            val active = bridge.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(bridge) as AtomicReference<Any?>
            active.set(fixture.session)
            val workers = bridge.javaClass.getDeclaredField("workers").apply { isAccessible = true }.get(bridge) as ThreadPoolExecutor
            assertEquals(2, workers.maximumPoolSize)
            val serve = bridge.javaClass.getDeclaredMethod("serve", SSLSocket::class.java).apply { isAccessible = true }
            val heldProjection = CountDownLatch(1); val projectionEntered = CountDownLatch(1)
            workers.execute { projectionEntered.countDown(); try { heldProjection.await() } catch (_: InterruptedException) {} }
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS))
            val acceptor = Executors.newSingleThreadExecutor()
            val clients = ArrayList<SSLSocket>()
            fun accepted(): Future<*> = acceptor.submit {
                val socket = peers.listener.accept() as SSLSocket
                workers.execute { serve.invoke(bridge, socket) }
            }
            try {
                repeat(3) {
                    val acceptance = accepted()
                    val client = Tls.connect(peers.receiver); clients.add(client)
                    Wire.writeRequest(DataOutputStream(client.outputStream), Wire.CAMERA) // Deliberately no hello.
                    acceptance.get(2, TimeUnit.SECONDS)
                    workers.submit {}.get(2, TimeUnit.SECONDS) // Actual serve has relinquished generic lane.
                }
                val camera = fixture.field("cameraRoles") as PhoneCameraMediaRole
                assertEquals(3, camera.snapshot().size)
                // A real bound test consumer and one attached role now stall in the actual media read.
                val delivered = CopyOnWriteArrayList<Any>()
                val consumer = object : PhoneCameraMediaConsumer {
                    override fun description(original: PublishedCameraUse, stream: CameraStreamRef, value: CameraStreamDescription) { delivered.add(value) }
                    override fun accessUnit(original: PublishedCameraUse, stream: CameraStreamRef, value: CameraAccessUnit) { delivered.add(value) }
                    override fun remoteEnd(original: PublishedCameraUse, value: CameraMediaEnd) { delivered.add(value) }
                    override fun requestRetire(original: PublishedCameraUse, reason: CameraEndReason) { delivered.add(reason) }
                }
                val use = camera.publishConfigured(object : OriginalPhoneDemandGroup {}, CameraStreamRequest(640, 480, 35, "video/avc"), consumer)
                val cameraAcceptance = accepted()
                val attached = Tls.connect(peers.receiver); clients.add(attached)
                Wire.writeRequest(DataOutputStream(attached.outputStream), Wire.CAMERA)
                CameraFeed.writeHello(DataOutputStream(attached.outputStream), use.ref)
                assertEquals(CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE, CameraFeed.readWelcome(DataInputStream(attached.inputStream), use.ref))
                cameraAcceptance.get(2, TimeUnit.SECONDS)
                workers.submit {}.get(2, TimeUnit.SECONDS)
                assertEquals(4, camera.snapshot().size)
                assertTrue((fixture.field("running") as AtomicBoolean).get())
                val acceptance = accepted()
                Tls.connect(peers.receiver).use { Wire.writeRequest(DataOutputStream(it.outputStream), Wire.STOP_HOST) }
                acceptance.get(2, TimeUnit.SECONDS)
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
                assertTrue(statuses.contains("HOST_STOPPING")); assertTrue(statuses.contains("HOST_STOPPED"))
                assertFalse((fixture.field("running") as AtomicBoolean).get())
                val complete = CountDownLatch(4)
                val rolesField = camera.javaClass.getDeclaredField("roles").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val roles = (rolesField.get(camera) as List<PendingCameraRole>).toList()
                roles.forEach { it.closeAsync(Executor { action -> action.run() }) { snapshot ->
                    assertTrue(snapshot.readerReturned); assertTrue(snapshot.readerJoined); complete.countDown()
                } }
                assertTrue(complete.await(3, TimeUnit.SECONDS))
            } finally {
                clients.forEach { runCatching { it.close() } }; heldProjection.countDown()
                bridge.close(); acceptor.shutdownNow(); timer.shutdownNow()
            }
        } }
    }
    @Test fun cameraEofAndUnknownUseNeverCloseActualProjectionSession() {
        PhoneCameraTestPeers().use { peers -> CameraBridgeOriginal(peers, "bridge-owner").use { fixture ->
            val timer = Executors.newSingleThreadScheduledExecutor()
            val bridge = Bridge(peers.phone, File(peers.phone, "unused.jar"), timer, peers.listener, {}, "bridge-owner")
            @Suppress("UNCHECKED_CAST") val active = bridge.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(bridge) as AtomicReference<Any?>
            active.set(fixture.session)
            val serve = bridge.javaClass.getDeclaredMethod("serve", SSLSocket::class.java).apply { isAccessible = true }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit { serve.invoke(bridge, peers.listener.accept() as SSLSocket) }
                Tls.connect(peers.receiver).use { socket ->
                    val use = CameraUseRef("bridge-owner", "unpublished", 0)
                    Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.CAMERA); CameraFeed.writeHello(DataOutputStream(socket.outputStream), use)
                    assertEquals(CameraAttachDisposition.UNKNOWN_ORIGINAL_USE, CameraFeed.readWelcome(DataInputStream(socket.inputStream), use))
                }
                future.get(2, TimeUnit.SECONDS)
                assertTrue((fixture.field("running") as AtomicBoolean).get()); assertSame(fixture.session, active.get())
            } finally { bridge.close(); executor.shutdownNow(); timer.shutdownNow() }
        } }
    }
}
