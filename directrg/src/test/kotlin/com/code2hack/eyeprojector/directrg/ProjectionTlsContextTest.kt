package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.*
import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real pinned mainstream + camera handshakes; no device latency or UI-thread scheduling claim. */
class ProjectionTlsContextTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun projectionInitializesOffCallerThenSharesContextWithCameraAndFreshProjectionOwnsAnother() {
        val phone = temporary.newFolder("phone")
        val receiver = temporary.newFolder("receiver")
        val phoneIdentity = PeerStore(phone).ensureIdentity()
        val receiverIdentity = PeerStore(receiver).ensureIdentity()
        File(phone, "peer.crt").writeBytes(receiverIdentity.encoded)
        File(receiver, "peer.crt").writeBytes(phoneIdentity.encoded)
        val password = File(receiver, "password").readBytes()
        val identity = File(receiver, "identity.p12").readBytes()
        val contexts = CopyOnWriteArrayList<SSLContext>()
        val initializationThreads = CopyOnWriteArrayList<Long>()
        val caller = Thread.currentThread().id
        val listener = Tls.context(phone).serverSocketFactory
            .createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true
        listener.soTimeout = 5000
        PeerStore(receiver).writeTransportEndpoint("127.0.0.1", listener.localPort, PeerStore.tlsName(phoneIdentity))
        val server = Executors.newSingleThreadExecutor()
        try {
            repeat(2) { projection ->
                File(receiver, "password").writeBytes(password)
                File(receiver, "identity.p12").writeBytes(identity)
                val ref = CameraUseRef("phone-owner", "projection-$projection", 0)
                val welcome = CountDownLatch(1)
                val cameraDelivered = CountDownLatch(1)
                val releaseMain = CountDownLatch(1)
                val finished = server.submit(Callable {
                    (listener.accept() as SSLSocket).use { main ->
                        main.soTimeout = 3500; main.startHandshake(); Tls.requireTrustedPeer(main, phone)
                        assertEquals(Wire.STREAM, Wire.readRequest(DataInputStream(main.inputStream)))
                        Wire.writeWelcome(DataOutputStream(main.outputStream))
                        (listener.accept() as SSLSocket).use { camera ->
                            camera.soTimeout = 3500; camera.startHandshake(); Tls.requireTrustedPeer(camera, phone)
                            val input = DataInputStream(camera.inputStream)
                            assertEquals(Wire.CAMERA, Wire.readRequest(input))
                            assertEquals(ref, CameraFeed.readHello(input))
                            CameraFeed.writeWelcome(DataOutputStream(camera.outputStream), ref,
                                CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE)
                            assertEquals(Wire.CAMERA_END, Wire.readFrame(input).channel)
                            cameraDelivered.countDown()
                        }
                        releaseMain.await()
                    }
                })
                val session = ProjectionSession(receiver, Surface(), { _, state ->
                    if (state == "WAITING_FOR_VIDEO") welcome.countDown()
                }, { _, _ -> }, expectedPhoneOwnerEpoch = "phone-owner", tlsContextFactory = { directory ->
                    initializationThreads.add(Thread.currentThread().id)
                    Tls.context(directory).also { contexts.add(it) }
                })
                try {
                    assertEquals("Construction initialized TLS on its caller", projection, contexts.size)
                    session.start()
                    assertTrue("Mainstream welcome not observed", welcome.await(5, TimeUnit.SECONDS))
                    assertEquals(projection + 1, contexts.size)
                    assertNotEquals(caller, initializationThreads.last().toLong())
                    // A fresh camera context cannot initialize without these files. Its successful
                    // actual handshake therefore proves the production owner passed the main context.
                    assertTrue(File(receiver, "password").delete())
                    assertTrue(File(receiver, "identity.p12").delete())
                    val use = session.cameraUses.observe(CameraUseMessage.Prepare(ref,
                        CameraStreamRequest(640, 480, 35, "video/avc"))).original!!
                    val source = object : CameraOutboundMediaSource {
                        override fun next(): CameraOutboundRecord = CameraOutboundRecord.End(
                            CameraMediaEnd(ref, null, CameraEndReason.ORIGINAL_RETIRED, 1, 0))
                        override fun requestRetire(reason: CameraEndReason) = Unit
                    }
                    val events = object : CameraRoleEvents {
                        override fun changed(role: OwnedCameraRole, value: RoleCloseObservation) = Unit
                        override fun remoteEnd(role: OwnedCameraRole, value: CameraMediaEnd) = Unit
                    }
                    val role = session.cameraUses.connect(use, source, events)
                    val joined = CountDownLatch(1)
                    val observed = CopyOnWriteArrayList<RoleCloseObservation>()
                    // Await actual camera end delivery before requesting its close.
                    // The server remains alive until the captured main is explicitly released.
                    assertTrue("Camera end did not arrive", cameraDelivered.await(5, TimeUnit.SECONDS))
                    role.closeAsync(Executor { it.run() }) { observed.add(it); joined.countDown() }
                    assertTrue(joined.await(5, TimeUnit.SECONDS))
                    assertEquals(CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE, observed.single().welcome)
                    assertTrue(observed.single().writerJoined)
                    assertTrue(observed.single().readerJoined)
                    assertEquals(projection + 1, contexts.size)
                } finally {
                    session.close()
                    releaseMain.countDown()
                    finished.get(5, TimeUnit.SECONDS)
                }
            }
            assertEquals(2, contexts.size)
            assertNotSame("A fresh projection must own fresh TLS state", contexts[0], contexts[1])
        } finally {
            listener.close()
            server.shutdownNow()
        }
    }
}
