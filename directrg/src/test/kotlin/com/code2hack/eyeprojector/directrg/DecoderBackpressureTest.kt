package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
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
        codec.input = { timeout -> if (slot.tryAcquire(timeout, TimeUnit.MICROSECONDS)) 0 else -1 }
        codec.queuedInput = { pts, _ -> queued.add(pts) }
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
            decoder.enqueue(ScrcpyRecord.Packet(335_000, byteArrayOf(2)), origin)
            assertTrue(futureOutput.await(1, TimeUnit.SECONDS))
            val admission = worker.submit { decoder.enqueue(ScrcpyRecord.Packet(368_333, byteArrayOf(3)), System.nanoTime()) }
            admission.get(2, TimeUnit.SECONDS)
            assertNull(failure.get())
            assertEquals(3L, decoder.snapshot().optLong("queuedInputs", -1))
            assertTrue(decoder.snapshot().optLong("inputRetries", 0) > 0)
        } finally { decoder.close(); worker.shutdownNow() }
        assertEquals(1, codec.releases.get())
    }

    @Test fun unavailableInputWithoutOutputProgressStillFails() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        codec.input = { timeout -> TimeUnit.MICROSECONDS.sleep(timeout); -1 }
        val decoder = SurfaceDecoder(Surface(), Geometry(480, 640), {}, {}, {}, {})
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), System.nanoTime())
            }
            assertTrue(error.message!!.contains("Decoder input stalled"))
            assertEquals(0L, decoder.snapshot().optLong("queuedInputs", -1))
            assertEquals(0L, decoder.snapshot().optLong("outputBuffers", -1))
            assertEquals(1L, decoder.snapshot().optLong("inputRetries", -1))
        } finally { decoder.close() }
        assertEquals(1, codec.releases.get())
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
