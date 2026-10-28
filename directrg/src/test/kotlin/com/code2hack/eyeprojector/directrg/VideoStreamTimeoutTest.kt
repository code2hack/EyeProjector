package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Frame
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Wire
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual Session start/framing with modeled socket timeouts and codec APIs; no TLS/device proof. */
class VideoStreamTimeoutTest {
    @get:Rule val temporary = TemporaryFolder()

    private companion object {
        // Scale only the fake setup timeout; production policy is not changed by this fixture.
        const val SETUP_TIMEOUT_MS = 50
    }

    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also {
        write(DataOutputStream(it))
    }.toByteArray()

    private class Peer : SSLSocket() {
        private val incoming = LinkedBlockingQueue<Int>()
        private val written = ByteArrayOutputStream()
        @Volatile private var timeout = SETUP_TIMEOUT_MS
        @Volatile private var ended = false
        val timeoutChanges = CopyOnWriteArrayList<Int>()
        val readTimeouts = CopyOnWriteArrayList<Int>()
        val blockedReads = LinkedBlockingQueue<Int>()
        val timeoutFailures = AtomicInteger()
        val readReleased = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        private val input = object : InputStream() {
            override fun read(): Int {
                reader.set(Thread.currentThread())
                if (ended) { readReleased.countDown(); throw SocketException("Modeled socket closed") }
                val bound = timeout
                val available = incoming.poll()
                val value = if (available != null) available else {
                    blockedReads.add(bound)
                    if (bound == 0) incoming.take() else incoming.poll(bound.toLong(), TimeUnit.MILLISECONDS)
                        ?: run { timeoutFailures.incrementAndGet(); throw SocketTimeoutException("Modeled setup timeout") }
                }
                if (ended || value == -1) {
                    readReleased.countDown(); throw SocketException("Modeled socket closed")
                }
                readTimeouts.add(bound)
                return value
            }
        }
        fun offer(data: ByteArray) { data.forEach { incoming.add(it.toInt() and 0xff) } }
        fun request(): Int = Wire.readRequest(DataInputStream(written.toByteArray().inputStream()))
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = written
        override fun setSoTimeout(value: Int) { timeout = value; timeoutChanges.add(value) }
        override fun getSoTimeout(): Int = timeout
        override fun close() { ended = true; incoming.offer(-1) }
        override fun isClosed(): Boolean = ended
        override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getPort(): Int = 27183
        override fun getLocalPort(): Int = 12345
        override fun getSession(): SSLSession = SSLContext.getDefault().createSSLEngine().session
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(value: Array<String>) = Unit
        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3")
        override fun getEnabledProtocols(): Array<String> = supportedProtocols
        override fun setEnabledProtocols(value: Array<String>) = Unit
        override fun addHandshakeCompletedListener(value: HandshakeCompletedListener) = Unit
        override fun removeHandshakeCompletedListener(value: HandshakeCompletedListener) = Unit
        override fun startHandshake() = Unit
        override fun setUseClientMode(value: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(value: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(value: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(value: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
    }

    @Test fun validWelcomeEndsSetupTimeoutAndIdleStreamResumesBeforeStopUnblocksItsRead() {
        val peer = Peer()
        val welcome = bytes(Wire::writeWelcome)
        peer.offer(welcome)
        peer.offer(bytes { Wire.writeFrame(it, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes())) })
        peer.offer(bytes { Wire.writeFrame(it, Frame(Wire.VIDEO, 1, byteArrayOf(1))) })
        val outputs = LinkedBlockingQueue<Long>()
        val outputPts = AtomicLong()
        val rendered = AtomicInteger()
        val resumed = CountDownLatch(1)
        val codec = MediaCodec().also { MediaCodec.created = it }
        codec.queuedInput = { pts, flags -> if (flags == 0) outputs.add(pts) }
        codec.output = { info -> outputs.poll()?.let { pts ->
            outputPts.set(pts); info.presentationTimeUs = pts; 0
        } ?: -1 }
        codec.releasedOutput = { render -> if (render) {
            codec.frameListener!!.onFrameRendered(codec, outputPts.get(), System.nanoTime())
            if (rendered.incrementAndGet() == 2) resumed.countDown()
        } }
        val streaming = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()
        val session = ProjectionSession(temporary.root.resolve("session"), Surface(), { _, state ->
            states.add(state)
            if (state == "STREAMING") streaming.countDown()
            if (state !in setOf("CONNECTING", "WAITING_FOR_VIDEO", "STREAMING")) terminal.countDown()
        }, { _, _ -> }, connectPeer = { peer })
        try {
            session.start()
            // Establish rendered readiness before idle; this does not bypass the initial-frame limit.
            assertTrue("First frame did not render: $states", streaming.await(2, TimeUnit.SECONDS))
            assertEquals(Wire.STREAM, peer.request())
            assertEquals(SETUP_TIMEOUT_MS, peer.readTimeouts.first().toInt())
            assertTrue(peer.readTimeouts.take(welcome.size).all { it == SETUP_TIMEOUT_MS })
            assertEquals(listOf(0), peer.timeoutChanges.toList())
            assertEquals(0, peer.blockedReads.poll(2, TimeUnit.SECONDS)?.toInt() ?: -1)
            assertFalse("Active stream inherited the modeled setup timeout: $states",
                terminal.await((SETUP_TIMEOUT_MS * 3).toLong(), TimeUnit.MILLISECONDS))
            assertEquals(0, peer.timeoutFailures.get())
            peer.offer(bytes { Wire.writeFrame(it, Frame(Wire.VIDEO, 2, byteArrayOf(2))) })
            assertTrue("Valid frame did not resume after idle: $states", resumed.await(2, TimeUnit.SECONDS))
            assertEquals(0, peer.blockedReads.poll(2, TimeUnit.SECONDS)?.toInt() ?: -1)
            assertEquals("STREAMING", states.last())
            session.close()
            assertTrue(peer.isClosed)
            assertTrue("Stop did not unblock the active read", peer.readReleased.await(2, TimeUnit.SECONDS))
            peer.reader.get().join(2000)
            assertFalse(peer.reader.get().isAlive)
            assertEquals(1, codec.releases.get())
        } finally { session.close(); peer.close() }
    }

    @Test fun missingWelcomeRetainsTheFiniteSetupTimeout() = rejectedWelcome(null, timedOut = true)

    @Test fun invalidWelcomeDoesNotClearTheFiniteSetupTimeout() = rejectedWelcome(
        bytes { it.writeInt(0); it.writeInt(0); it.flush() }, timedOut = false)

    private fun rejectedWelcome(payload: ByteArray?, timedOut: Boolean) {
        val peer = Peer()
        payload?.let(peer::offer)
        val failed = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()
        val session = ProjectionSession(temporary.root.resolve("rejected-session"), Surface(), { _, state ->
            states.add(state)
            if (state == "CONNECTION_OR_VIDEO_FAILED") failed.countDown()
        }, { _, _ -> }, connectPeer = { peer })
        try {
            session.start()
            assertTrue("Setup failure was not reported: $states", failed.await(2, TimeUnit.SECONDS))
            assertEquals(Wire.STREAM, peer.request())
            assertEquals(SETUP_TIMEOUT_MS, peer.soTimeout)
            assertTrue("A rejected welcome cleared setup timeout", peer.timeoutChanges.isEmpty())
            assertEquals(if (timedOut) 1 else 0, peer.timeoutFailures.get())
            assertFalse(states.contains("STREAMING"))
            assertTrue(peer.isClosed)
            peer.reader.get().join(2000)
            assertFalse(peer.reader.get().isAlive)
        } finally { session.close(); peer.close() }
    }
}
