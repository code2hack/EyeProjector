package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Scrcpy
import com.code2hack.eyeprojector.protocol.ScrcpyRecord
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual decoder/clock with platform API doubles; no Android or run181 replay claim. */
class DecoderReceiptClockTest {
    private class Fixture(outputOffsetUs: Long = 0) : AutoCloseable {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val outputs = LinkedBlockingQueue<Long>()
        val rendered = LinkedBlockingQueue<Long>()
        val failures = LinkedBlockingQueue<Exception>()
        private var outputPts = 0L
        val decoder: SurfaceDecoder

        init {
            codec.queuedInput = { pts, flags ->
                if (flags == 0) outputs.add(pts + outputOffsetUs)
            }
            codec.output = { info ->
                outputs.poll()?.let { pts -> info.presentationTimeUs = pts; outputPts = pts; 0 } ?: -1
            }
            codec.releasedOutput = { render ->
                if (render) codec.frameListener!!.onFrameRendered(codec, outputPts, 0)
            }
            decoder = SurfaceDecoder(Surface(), Geometry(480, 640), {}, {}, {}, { failures.add(it) },
                sample = { pts, _ -> rendered.add(pts) })
        }

        override fun close() {
            decoder.close()
            assertEquals(1, codec.releases.get())
        }
    }

    @Test fun alreadyReceivedFrameDoesNotInheritTheFirstReceiptsDelay() {
        Fixture().use { fixture ->
            val source = 1_968_658_357_745L
            fixture.decoder.enqueue(ScrcpyRecord.Packet(Scrcpy.CONFIG, byteArrayOf(0)), System.nanoTime())
            fixture.decoder.enqueue(ScrcpyRecord.Packet(source, byteArrayOf(1)), System.nanoTime())
            assertEquals(source, fixture.rendered.poll(2, TimeUnit.SECONDS))
            // An admitted next frame with a larger source interval than local arrival interval.
            // This is a controlled variable-delay model, not a measured initial run181 sample.
            fixture.decoder.enqueue(ScrcpyRecord.Packet(source + 5_000_000, byteArrayOf(2)), System.nanoTime())
            val next = fixture.rendered.poll(2, TimeUnit.SECONDS)
            assertNotNull("Second frame missing; failure=${fixture.failures.peek()}", next)
            assertEquals(source + 5_000_000, next!!.toLong())
            assertNull(fixture.failures.peek())
            val snapshot = fixture.decoder.snapshot()
            assertEquals(3L, snapshot.optLong("queuedInputs", -1))
            assertEquals(1L, snapshot.optLong("configInputs", -1))
            assertEquals(2L, snapshot.optLong("outputReleases", -1))
            assertEquals(0L, snapshot.optLong("pendingDecodeFrames", -1))
            assertEquals(0L, snapshot.optLong("pendingRenderFrames", -1))
        }
    }

    @Test fun configDoesNotEstablishThePresentationOrigin() {
        Fixture().use { fixture ->
            fixture.decoder.enqueue(ScrcpyRecord.Packet(Scrcpy.CONFIG, byteArrayOf(0)), 0)
            fixture.decoder.enqueue(ScrcpyRecord.Packet(8_000_000_000, byteArrayOf(1)), System.nanoTime())
            assertEquals(8_000_000_000L, fixture.rendered.poll(2, TimeUnit.SECONDS))
            assertNull(fixture.failures.peek())
        }
    }

    @Test fun unobservedFutureCodecPtsStillFailsTheExistingBound() {
        Fixture(outputOffsetUs = 5_000_000).use { fixture ->
            fixture.decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), System.nanoTime())
            val failure = fixture.failures.poll(2, TimeUnit.SECONDS)
            assertTrue("failure=$failure", failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("Video clock diverged: videoPtsUs=5000000"))
            assertNull(fixture.rendered.peek())
            assertEquals(0L, fixture.decoder.snapshot().optLong("outputReleases", -1))
        }
    }

    @Test fun overflowingReceiptSampleFailsBeforeQueueingInsteadOfWrapping() {
        Fixture().use { fixture ->
            fixture.decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), System.nanoTime())
            assertEquals(0L, fixture.rendered.poll(2, TimeUnit.SECONDS))
            assertThrows(ArithmeticException::class.java) {
                fixture.decoder.enqueue(ScrcpyRecord.Packet(Scrcpy.KEY_FRAME - 1, byteArrayOf(2)), System.nanoTime())
            }
            assertEquals(1L, fixture.decoder.snapshot().optLong("queuedInputs", -1))
        }
    }
}
