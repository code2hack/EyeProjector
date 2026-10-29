package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real framing/session/decoder lifecycle with a codec double; no hardware decode claim. */
class VideoSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun codec() = MediaCodec().also { codec ->
        MediaCodec.created = codec
        val queued = LinkedBlockingQueue<Long>()
        var outputPts = 0L
        codec.queuedInput = { pts, flags -> if (flags == 0) queued.add(pts) }
        codec.output = { info ->
            queued.poll()?.let { pts -> info.presentationTimeUs = pts; outputPts = pts; 0 } ?: -1
        }
        codec.releasedOutput = { render -> if (render) codec.frameListener!!.onFrameRendered(codec, outputPts, 0) }
    }

    private fun deliver(session: ProjectionSession, vararg frames: Frame) {
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use { out -> Wire.writeWelcome(out); frames.forEach { Wire.writeFrame(out, it) } }
        val input = DataInputStream(data.toByteArray().inputStream())
        Wire.readWelcome(input)
        while (input.available() > 0) session.receive(Wire.readFrame(input))
    }

    /** Negotiated bytes for the real receiver thread; no network or TLS verification claim. */
    private class InputPeer(bytes: ByteArray) : SSLSocket() {
        private val input = bytes.inputStream()
        private val output = ByteArrayOutputStream()
        @Volatile private var ended = false
        private var readTimeout = 4000
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun setSoTimeout(value: Int) { readTimeout = value }
        override fun getSoTimeout(): Int = readTimeout
        override fun close() { ended = true }
        override fun isClosed(): Boolean = ended
        override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
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

    @Test fun stopDuringRepeatedInputUnavailabilityKeepsTheSessionTerminalState() {
        val first = codec()
        val polling = CountDownLatch(1)
        val returnIndex = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val polls = AtomicInteger()
        val submissions = AtomicInteger()
        first.queuedInput = { _, _ -> submissions.incrementAndGet() }
        first.input = { timeout ->
            assertEquals(50_000L, timeout)
            if (polls.incrementAndGet() <= 3) MediaCodec.INFO_TRY_AGAIN_LATER else {
                reader.set(Thread.currentThread())
                polling.countDown()
                check(returnIndex.await(5, TimeUnit.SECONDS))
                0 // Even a late successful dequeue must not admit after Stop.
            }
        }
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use { out ->
            Wire.writeWelcome(out)
            Wire.writeFrame(out, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes()))
            Wire.writeFrame(out, Frame(Wire.VIDEO, 123, byteArrayOf(1)))
        }
        val peer = InputPeer(data.toByteArray())
        val states = CopyOnWriteArrayList<String>()
        val directory = temporary.newFolder().resolve("session")
        val session = ProjectionSession(directory, Surface(), { _, state -> states.add(state) }, { _, _ -> },
            connectPeer = { peer }, tlsContextFactory = { error("Injected peer must not initialize TLS") })
        try {
            session.start()
            assertTrue("Receiver did not retry unavailable input: $states", polling.await(2, TimeUnit.SECONDS))
            assertEquals(4, polls.get())
            assertEquals(0, submissions.get())
            assertTrue(states.contains("WAITING_FOR_VIDEO"))
            assertFalse(states.contains("STREAMING"))
            // Isolate input cancellation from the independent heartbeat/write watchdog.
            val timer = ProjectionSession::class.java.getDeclaredField("timer").apply { isAccessible = true }
                .get(session) as java.util.concurrent.ScheduledExecutorService
            timer.shutdown()
            assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS))
            session.close()
            assertTrue(peer.isClosed)
            assertEquals(1, first.releases.get())
            assertEquals("STOPPED", states.last())
            returnIndex.countDown()
            reader.get().join(2000)
            assertFalse("Stop must end the pending receiver operation", reader.get().isAlive)
            assertEquals(0, submissions.get())
            assertEquals(listOf("STOPPED"), states.filter {
                it !in setOf("CONNECTING", "WAITING_FOR_VIDEO", "STREAMING")
            })
            assertFalse(session.navigate(Navigation.HOME))
            val statusFile = directory.parentFile!!.resolve("status.json")
            val stateField = Regex("(?:^\\{|, )state=([^,}]+)")
            fun stateOf(snapshot: String) = stateField.find(snapshot)?.groupValues?.get(1)
            val persisted = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < persisted &&
                runCatching { stateOf(statusFile.readText()) == "STOPPED" }.getOrDefault(false).not())
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            val terminal = statusFile.readText()
            assertEquals("Persisted terminal snapshot: $terminal", "STOPPED", stateOf(terminal))
            assertFalse("Cancellation was recorded as a codec error: $terminal", terminal.contains("errorType="))
            session.close()
            assertEquals(1, first.releases.get())
        } finally {
            session.close(); returnIndex.countDown(); reader.get()?.join(2000); peer.close()
        }
    }

    @Test fun videoOnlyHandshakeRendersResizesAndReconnectsWithFreshPts() {
        for (connection in 0..1) {
            val states = LinkedBlockingQueue<String>()
            val first = codec()
            val session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, state -> states.add(state) }, { _, _ -> })
            try {
                val size = Geometry(480, 640)
                deliver(session, Frame(Wire.GEOMETRY, payload = size.bytes()),
                    Frame(Wire.VIDEO, Scrcpy.CONFIG, byteArrayOf(0, 0, 1)),
                    Frame(Wire.VIDEO, Scrcpy.KEY_FRAME or 9_000_000_000, byteArrayOf(1)))
                assertEquals("WAITING_FOR_VIDEO", states.poll(2, TimeUnit.SECONDS))
                assertEquals("STREAMING", states.poll(2, TimeUnit.SECONDS))
                val second = codec()
                val rotated = Geometry(640, 480)
                deliver(session, Frame(Wire.GEOMETRY, payload = rotated.bytes()))
                assertEquals("WAITING_FOR_VIDEO", states.poll(2, TimeUnit.SECONDS))
                assertEquals(1, first.releases.get())
                assertEquals(rotated, session.geometry)
                assertFalse(session.tap(Tap(1, 1, size)))
                // The new encoder can restart PTS; it must not inherit the previous stream's origin.
                deliver(session, Frame(Wire.VIDEO, Scrcpy.KEY_FRAME or 20_000, byteArrayOf(2)))
                assertEquals("STREAMING", states.poll(2, TimeUnit.SECONDS))
                deliver(session, Frame(Wire.END))
                assertEquals("DISCONNECTED", states.poll(2, TimeUnit.SECONDS))
                assertEquals(1, second.releases.get())
                assertFalse(session.tap(Tap(1, 1, rotated)))
            } finally { session.close() }
        }
    }

    @Test fun unobservedCodecTimingFailureEndsTheVideoOnlySessionAndReleasesCodec() {
        val states = LinkedBlockingQueue<String>()
        val codec = codec()
        val session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, state -> states.add(state) }, { _, _ -> })
        try {
            deliver(session, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes()),
                Frame(Wire.VIDEO, 0, byteArrayOf(1)))
            assertEquals("WAITING_FOR_VIDEO", states.poll(2, TimeUnit.SECONDS))
            assertEquals("STREAMING", states.poll(2, TimeUnit.SECONDS))
            // An admitted frame can arrive after a long source gap. Preserve the
            // failure path with a codec output PTS that no packet actually supplied.
            val originalOutput = codec.output
            codec.output = { info -> originalOutput(info).also { if (it >= 0) info.presentationTimeUs += 5_000_000 } }
            deliver(session, Frame(Wire.VIDEO, 33_333, byteArrayOf(2)))
            assertEquals("DECODE_FAILED", states.poll(2, TimeUnit.SECONDS))
            assertEquals(1, codec.releases.get())
            assertFalse(session.navigate(Navigation.HOME))
        } finally { session.close() }
    }

    @Test fun codecAndSurfaceReleaseFailuresEndSessionAndAllowFreshReconnect() {
        for (stage in listOf("output", "surface-release")) {
            val states = LinkedBlockingQueue<String>()
            val broken = AtomicBoolean()
            val first = codec()
            val originalRelease = first.releasedOutput
            first.at = { if (it == "dequeueOutput" && stage == "output" && broken.get()) error("codec failure") }
            first.releasedOutput = {
                if (stage == "surface-release" && broken.get()) error("Surface release failed")
                originalRelease(it)
            }
            val directory = temporary.newFolder().resolve("session")
            val session = ProjectionSession(directory, Surface(), { _, state -> states.add(state) }, { _, _ -> })
            try {
                deliver(session, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes()),
                    Frame(Wire.VIDEO, 0, byteArrayOf(1)))
                assertEquals("WAITING_FOR_VIDEO", states.poll(2, TimeUnit.SECONDS))
                assertEquals("STREAMING", states.poll(2, TimeUnit.SECONDS))
                broken.set(true)
                if (stage == "surface-release") deliver(session, Frame(Wire.VIDEO, 33_333, byteArrayOf(2)))
                assertEquals("DECODE_FAILED", states.poll(2, TimeUnit.SECONDS))
                assertEquals(1, first.releases.get())
                assertFalse(session.navigate(Navigation.HOME))
                val statusFile = directory.parentFile!!.resolve("status.json")
                // This suite's JSONObject telemetry double serializes a map, not JSON.
                // Check the exact state value in that representation, including its boundary.
                val stateField = Regex("(?:^\\{|, )state=([^,}]+)")
                fun stateOf(snapshot: String) = stateField.find(snapshot)?.groupValues?.get(1)
                val persisted = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < persisted &&
                    runCatching { stateOf(statusFile.readText()) == "DECODE_FAILED" }.getOrDefault(false).not())
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
                val terminal = statusFile.readText()
                assertEquals("Persisted terminal snapshot: $terminal", "DECODE_FAILED", stateOf(terminal))
                assertTrue("Persisted terminal snapshot: $terminal", terminal.contains("decoderProgress"))
            } finally { session.close() }

            val second = codec()
            val fresh = ProjectionSession(directory, Surface(), { _, state -> states.add(state) }, { _, _ -> })
            try {
                deliver(fresh, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes()),
                    Frame(Wire.VIDEO, 9_000_000, byteArrayOf(3)))
                assertEquals("WAITING_FOR_VIDEO", states.poll(2, TimeUnit.SECONDS))
                assertEquals("STREAMING", states.poll(2, TimeUnit.SECONDS))
            } finally { fresh.close() }
            assertEquals(1, second.releases.get())
            assertEquals("STOPPED", states.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test fun scaleFeedbackKeepsRequestedAndObservedNumbersSeparateWithoutReplacingTheDecoder() {
        val states = mutableListOf<ScaleState>()
        val codec = codec()
        val session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, _ -> }, { _, _ -> },
            onScale = { _, state -> states.add(state) })
        try {
            val old = Geometry(480, 640)
            deliver(session, Frame(Wire.GEOMETRY, payload = old.bytes()), Frame(Wire.VIDEO, 0, byteArrayOf(1)))
            val pending = ScaleState(7, 1.25, old, Geometry(384, 512), ScaleStatus.PENDING, false)
            deliver(session, Frame(Wire.SCALE_STATE, 7, pending.bytes()))
            assertEquals(pending, session.scaleState)
            assertEquals(1.0, LayoutScale.applied(session.scaleState!!.observed!!), 0.0)
            assertEquals(old, session.geometry)
            assertEquals(0, codec.releases.get())
            val rejected = pending.copy(requestId = 8, requested = -1.0, target = null, status = ScaleStatus.REJECTED)
            deliver(session, Frame(Wire.SCALE_STATE, 8, rejected.bytes()))
            assertEquals(listOf(pending, rejected), states)
            assertEquals(old, session.geometry)
            assertEquals(0, codec.releases.get())
        } finally { session.close() }
        assertEquals(1, codec.releases.get())
        assertFalse(session.requestScale(1.0))
    }
}
