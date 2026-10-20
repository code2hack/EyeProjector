package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

    @Test fun decoderTimingFailureEndsTheVideoOnlySessionAndReleasesCodec() {
        val states = LinkedBlockingQueue<String>()
        val codec = codec()
        val session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, state -> states.add(state) }, { _, _ -> })
        try {
            deliver(session, Frame(Wire.GEOMETRY, payload = Geometry(480, 640).bytes()),
                Frame(Wire.VIDEO, 0, byteArrayOf(1)))
            assertEquals("WAITING_FOR_VIDEO", states.poll(2, TimeUnit.SECONDS))
            assertEquals("STREAMING", states.poll(2, TimeUnit.SECONDS))
            deliver(session, Frame(Wire.VIDEO, 5_000_000, byteArrayOf(2)))
            assertEquals("DECODE_FAILED", states.poll(2, TimeUnit.SECONDS))
            assertEquals(1, codec.releases.get())
            assertFalse(session.navigate(Navigation.HOME))
        } finally { session.close() }
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
