package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual session cancellation and framing with controlled I/O; no device/hardware claim. */
class ScaleSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun set(session: ProjectionSession, name: String, value: Any) {
        ProjectionSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }
    private fun dispatcher(session: ProjectionSession, name: String): OrderedControlDispatcher =
        ProjectionSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session) as OrderedControlDispatcher

    @Test fun queuedScaleSurvivesGeometryAndPointerCancellationWhileOldPointerInputIsDiscarded() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushed = CountDownLatch(3)
        val first = AtomicBoolean(true)
        val buffer = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (first.compareAndSet(true, false)) {
                    blocked.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
                buffer.write(value)
            }
            override fun flush() { flushed.countDown() }
        }
        val session = ProjectionSession(temporary.root.resolve("session"), Surface(), { _, _ -> }, { _, _ -> })
        val initial = Geometry(480, 640)
        set(session, "geometry", initial)
        val readiness = ProjectionSession::class.java.getDeclaredField("videoReadiness").apply { isAccessible = true }.get(session) as VideoReadiness
        val epoch = readiness.beginReplacement()!!
        check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
        set(session, "output", DataOutputStream(output))
        val codec = MediaCodec().also { MediaCodec.created = it }
        try {
            assertTrue(session.tap(Tap(30, 40, initial), 1000))
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            assertTrue(session.scroll(Scroll(Tap(30, 40, initial), 160), 1100))
            assertTrue(session.requestScale(1.25, 1))
            assertTrue(session.requestScale(2.0, 2))
            session.replaceDecoder(Geometry(384, 512))
            session.cancelPendingInput()
            assertEquals(1L, dispatcher(session, "controls").snapshot().canceled)
            assertEquals(0L, dispatcher(session, "scaleControls").snapshot().canceled)
            release.countDown()
            assertTrue(flushed.await(2, TimeUnit.SECONDS))
            val input = DataInputStream(buffer.toByteArray().inputStream())
            val frames = (1..3).map { Wire.readFrame(input) }
            assertEquals(listOf(Wire.TAP, Wire.SCALE_REQUEST, Wire.SCALE_REQUEST), frames.map { it.channel })
            assertEquals(listOf(1.25, 2.0), frames.drop(1).map { ScaleRequest.from(it.payload).scale })
            assertEquals(listOf(1L, 2L), frames.drop(1).map { it.stamp })
            assertEquals(0, input.available())
        } finally { release.countDown(); session.close() }
        assertEquals(1, codec.releases.get())
        assertFalse(session.requestScale(1.0))
        assertEquals(0L, dispatcher(session, "scaleControls").snapshot().pending)
    }

    @Test fun closingSessionCancelsQueuedScaleAndRejectsLaterAdjustments() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = ProjectionSession(temporary.root.resolve("session"), Surface(), { _, _ -> }, { _, _ -> })
        set(session, "geometry", Geometry(480, 640))
        val queue = dispatcher(session, "scaleControls")
        try {
            queue.submit(1000, { true }) { started.countDown(); release.await(2, TimeUnit.SECONDS) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(session.requestScale(1.25, 1))
            session.close()
            assertEquals(1L, queue.snapshot().canceled)
            assertEquals(0L, queue.snapshot().pending)
            assertFalse(session.requestScale(2.0))
        } finally { release.countDown(); session.close() }
    }
}
