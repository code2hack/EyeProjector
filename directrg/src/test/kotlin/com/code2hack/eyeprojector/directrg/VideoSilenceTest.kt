package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Production start/TLS/scheduler regression; codec doubles do not prove hardware capture health. */
class VideoSilenceTest {
    companion object {
        private lateinit var root: File
        @BeforeClass @JvmStatic fun identities() {
            root = File("build/tmp/video-silence-${System.nanoTime()}").apply { mkdirs() }
            val tool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            for (role in listOf("host", "receiver")) {
                val dir = File(root, role).apply { mkdir() }
                val password = "unit-test-only"
                File(dir, "password").writeText(password)
                fun keytool(vararg args: String) {
                    val process = ProcessBuilder(tool, *args).redirectErrorStream(true)
                        .redirectOutput(File(dir, "keytool.log")).start()
                    try {
                        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Test identity generation failed" }
                    } finally { if (process.isAlive) process.destroyForcibly() }
                }
                keytool("-genkeypair", "-alias", role, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", File(dir, "identity.p12").path,
                    "-storepass", password, "-keypass", password)
                keytool("-exportcert", "-rfc", "-alias", role, "-keystore", File(dir, "identity.p12").path,
                    "-storepass", password, "-file", File(dir, "public.crt").path)
            }
            File(root, "receiver/public.crt").copyTo(File(root, "host/peer.crt"))
            File(root, "host/public.crt").copyTo(File(root, "receiver/peer.crt"))
        }
    }

    @Test(timeout = 20_000) fun initialExplicitModeCannotPrecedeActualAuthenticatedRequestWelcome() {
        val listener=Tls.context(File(root,"host")).serverSocketFactory.createServerSocket(0,2,InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth=true;listener.soTimeout=5000
        File(root,"receiver/endpoint.properties").writeText("host=localhost\nport=${listener.localPort}\n")
        val executor=Executors.newSingleThreadExecutor()
        val server=executor.submit(Callable {
            (listener.accept() as SSLSocket).use { socket ->
                socket.soTimeout=5000;socket.startHandshake()
                val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
                assertEquals(Wire.STREAM,Wire.readRequest(input))
                socket.soTimeout=150
                assertThrows(java.net.SocketTimeoutException::class.java){input.readByte()}
                socket.soTimeout=5000;Wire.writeWelcome(output)
                val first=Wire.readFrame(input)
                assertEquals(Wire.RECEIVER_USABILITY,first.channel)
                assertEquals(ReceiverUsabilityMode.NONE,ReceiverUsability.from(first.payload).mode)
                Wire.writeFrame(output,Frame(Wire.END))
            }
        })
        val session=ProjectionSession(File(root,"receiver"),Surface(),{_,_->},{_,_->})
        try {
            session.usability(ReceiverUsability("startup-owner",ReceiverIncarnation("tls-startup",1),1,1,ReceiverUsabilityMode.NONE))
            session.start();server.get(8,TimeUnit.SECONDS)
        } finally {session.close();listener.close();executor.shutdownNow()}
    }

    private enum class Ending { QUIET, END, EOF, MALFORMED }
    @Test(timeout = 20_000) fun staticVideoRemainsUsableDuringControlOnlySilence() = exchange(Ending.QUIET)
    @Test(timeout = 20_000) fun explicitEndAfterFirstFrameReleasesSession() = exchange(Ending.END)
    @Test(timeout = 20_000) fun authenticatedStreamEofAfterFirstFrameReleasesSession() = exchange(Ending.EOF)
    @Test(timeout = 20_000) fun malformedFrameAfterFirstRenderReleasesSession() = exchange(Ending.MALFORMED)

    private fun exchange(ending: Ending) {
        val listener = Tls.context(File(root, "host")).serverSocketFactory
            .createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 5000
        File(root, "receiver/endpoint.properties").writeText("host=localhost\nport=${listener.localPort}\n")
        val pongs = AtomicInteger()
        val controls = CountDownLatch(1)
        val streaming = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()
        val available = AtomicBoolean()
        val codec = MediaCodec().also { MediaCodec.created = it }
        codec.queuedInput = { _, flags -> if (flags == 0) available.set(true) }
        codec.output = { info -> if (available.compareAndSet(true, false)) { info.presentationTimeUs = 1; 0 } else -1 }
        codec.releasedOutput = { render -> if (render) codec.frameListener!!.onFrameRendered(codec, 1, 0) }
        val executor = Executors.newSingleThreadExecutor()
        val server = executor.submit(Callable {
            try {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000; socket.startHandshake()
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    assertEquals(Wire.STREAM, Wire.readRequest(input))
                    Wire.writeWelcome(output)
                    Wire.writeFrame(output, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes()))
                    Wire.writeFrame(output, Frame(Wire.VIDEO, 1, byteArrayOf(1)))
                    while (true) {
                        val frame = Wire.readFrame(input)
                        when (frame.channel) {
                            Wire.PING -> {
                                Wire.writeFrame(output, Frame(Wire.PONG, frame.stamp)); pongs.incrementAndGet()
                                if (ending != Ending.QUIET && streaming.count == 0L) {
                                    when (ending) {
                                        Ending.END -> Wire.writeFrame(output, Frame(Wire.END))
                                        Ending.MALFORMED -> {
                                            output.writeByte(Wire.VIDEO); output.writeLong(0)
                                            output.writeInt(Wire.MAX_PAYLOAD + 1); output.flush()
                                        }
                                        else -> Unit
                                    }
                                    return@Callable
                                }
                            }
                            Wire.NAVIGATE -> { Navigate.from(frame.payload); controls.countDown() }
                            else -> error("Unexpected control")
                        }
                    }
                }
            } catch (_: EOFException) { /* Receiver owns shutdown. */ }
            catch (_: SocketException) { /* Receiver owns shutdown. */ }
        })
        val session = ProjectionSession(File(root, "receiver"), Surface(), { _, state ->
            states.add(state)
            if (state == "STREAMING") streaming.countDown()
            if (state in listOf("DISCONNECTED", "CONNECTION_OR_VIDEO_FAILED")) disconnected.countDown()
        }, { _, _ -> })
        try {
            session.start()
            assertTrue("First frame must establish video-only readiness: $states", streaming.await(4, TimeUnit.SECONDS))
            if (ending != Ending.QUIET) {
                assertTrue(disconnected.await(3, TimeUnit.SECONDS))
                assertEquals(if (ending == Ending.END) "DISCONNECTED" else "CONNECTION_OR_VIDEO_FAILED", states.last())
                assertEquals(1, codec.releases.get())
                assertFalse(session.navigate(Navigation.HOME))
            } else {
                // A static source can exhaust its finite codec repeats; PONGs are transport evidence only.
                Thread.sleep(6500)
                assertEquals("STREAMING", states.last())
                assertTrue(pongs.get() >= 6)
                assertEquals(0, codec.releases.get())
                assertTrue(session.navigate(Navigation.HOME))
                assertTrue(controls.await(1, TimeUnit.SECONDS))
            }
        } finally {
            session.close(); listener.close()
            try { server.get(3, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
        assertEquals(1, codec.releases.get())
    }
}
