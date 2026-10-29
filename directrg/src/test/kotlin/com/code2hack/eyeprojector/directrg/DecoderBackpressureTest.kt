package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Scrcpy
import com.code2hack.eyeprojector.protocol.ScrcpyRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Finite codec capacity makes output presentation exert real backpressure on input. */
class DecoderBackpressureTest {
    @Test fun scheduledOutputDoesNotTurnTemporaryInputExhaustionIntoFailure() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val slot = Semaphore(1)
        val queued = LinkedBlockingQueue<Long>()
        val firstRendered = CountDownLatch(1)
        val futureOutput = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        var outputPts = 0L
        var queuedCount = 0
        codec.input = { timeout -> if (slot.tryAcquire(timeout, TimeUnit.MICROSECONDS)) 0 else -1 }
        // An unmatched future codec PTS isolates the retained output wait from the
        // receipt-offset estimate. This is an API fault model, not healthy capture.
        codec.queuedInput = { pts, _ -> queued.add(if (++queuedCount == 1) pts else pts + 335_000) }
        codec.output = { info ->
            queued.poll()?.let { pts ->
                info.presentationTimeUs = pts; outputPts = pts
                if (pts == 335_000L) futureOutput.countDown()
                0
            } ?: -1
        }
        codec.releasedOutput = { render ->
            slot.release()
            if (render) {
                codec.frameListener!!.onFrameRendered(codec, outputPts, 0)
                if (outputPts == 0L) firstRendered.countDown()
            }
        }
        val decoder = SurfaceDecoder(Surface(), Geometry(480, 640), {}, {}, {}, { failure.set(it) })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val origin = System.nanoTime()
            decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), origin)
            assertTrue(firstRendered.await(1, TimeUnit.SECONDS))
            decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(2)), origin)
            assertTrue(futureOutput.await(1, TimeUnit.SECONDS))
            val admission = worker.submit { decoder.enqueue(ScrcpyRecord.Packet(368_333, byteArrayOf(3)), System.nanoTime()) }
            admission.get(2, TimeUnit.SECONDS)
            assertNull(failure.get())
            assertEquals(3L, decoder.snapshot().optLong("queuedInputs", -1))
            assertTrue(decoder.snapshot().optLong("inputRetries", 0) > 0)
        } finally { decoder.close(); worker.shutdownNow() }
        assertEquals(1, codec.releases.get())
    }

    @Test fun repeatedUnavailableInputWithoutOutputProgressAdmitsTheSamePacketOnce() {
        for (stamp in listOf(Scrcpy.CONFIG or 123L, Scrcpy.KEY_FRAME or 456L)) {
            val codec = MediaCodec().also { MediaCodec.created = it }
            val polls = mutableListOf<Long>()
            val queued = mutableListOf<Pair<Long, Int>>()
            val failure = AtomicReference<Exception>()
            codec.input = { timeout ->
                polls.add(timeout)
                if (polls.size <= 4) MediaCodec.INFO_TRY_AGAIN_LATER else 0
            }
            codec.queuedInput = { pts, flags -> queued.add(pts to flags) }
            val data = byteArrayOf(0, 0, 1, 42)
            val packet = ScrcpyRecord.Packet(stamp, data)
            val decoder = SurfaceDecoder(Surface(), Geometry(480, 640), {}, {}, {}, { failure.set(it) })
            try {
                decoder.enqueue(packet, System.nanoTime())
                assertEquals(List(5) { 50_000L }, polls)
                assertEquals(listOf(packet.ptsUs to if (packet.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0), queued)
                assertArrayEquals(byteArrayOf(0, 0, 1, 42), data)
                val progress = decoder.snapshot()
                assertEquals(4L, progress.optLong("inputRetries", -1))
                assertEquals(1L, progress.optLong("queuedInputs", -1))
                assertEquals(if (packet.config) 1L else 0L, progress.optLong("configInputs", -1))
                assertEquals(packet.ptsUs, progress.optLong("lastInputPtsUs", -1))
                assertEquals(0L, progress.optLong("outputBuffers", -1))
                assertEquals(0L, progress.optLong("outputReleases", -1))
                assertEquals(0L, progress.optLong("renderCallbacks", -1))
                assertEquals(0L, progress.optLong("presentationTargetMonoNs", -1))
                assertNull(failure.get())
            } finally { decoder.close() }
            assertEquals(1, codec.releases.get())
        }
    }

    @Test fun stopDuringInputBackpressureDoesNotQueueAnotherPacket() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val inputWaiting = CountDownLatch(1)
        val admit = CountDownLatch(1)
        codec.input = { timeout ->
            inputWaiting.countDown()
            if (admit.await(timeout, TimeUnit.MICROSECONDS)) 0 else -1
        }
        val decoder = SurfaceDecoder(Surface(), Geometry(480, 640), {}, {}, {}, {})
        val worker = Executors.newSingleThreadExecutor()
        try {
            val admission = worker.submit<Exception?> {
                try { decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), System.nanoTime()); null }
                catch (error: Exception) { error }
            }
            assertTrue(inputWaiting.await(1, TimeUnit.SECONDS))
            decoder.close()
            admit.countDown()
            assertTrue(admission.get(1, TimeUnit.SECONDS) is IllegalStateException)
            assertEquals(0L, decoder.snapshot().optLong("queuedInputs", -1))
        } finally { admit.countDown(); decoder.close(); worker.shutdownNow() }
        assertEquals(1, codec.releases.get())
    }
}
