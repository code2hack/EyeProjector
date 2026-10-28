package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.protocol.Navigation
import com.code2hack.eyeprojector.protocol.Scroll
import com.code2hack.eyeprojector.protocol.Tap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecoderLifetimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val size = Geometry(480, 640)
    private fun session() = ProjectionSession(temporary.root.resolve("session"), Surface(), { _, _ -> }, { _, _ -> })
    private fun decoder() = SurfaceDecoder(Surface(), size, {}, {}, {}, {})

    @Test fun delayedRenderCallbacksDoNotKillOutputOrSuppressEvictedFrameReceipts() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val outputs = LinkedBlockingQueue<Long>()
        val releases = LinkedBlockingQueue<Boolean>()
        val failures = AtomicReference<Exception>()
        val callbacks = AtomicInteger()
        val drained = CountDownLatch(1)
        val latency = java.util.Collections.synchronizedList(mutableListOf<Double?>())
        val sampled = mutableListOf<Long>()
        codec.queuedInput = { pts, flags -> if (flags == 0) outputs.add(pts) }
        var lastOutput = 0L
        codec.output = { info -> outputs.poll()?.let {
            lastOutput = it; info.presentationTimeUs = it; 0
        } ?: run { if (lastOutput == 40L) drained.countDown(); -1 } }
        // Model a healthy Surface while MAIN callback delivery is delayed independently.
        codec.releasedOutput = { releases.add(it) }
        val decoder = SurfaceDecoder(Surface(), size, { latency.add(it); callbacks.incrementAndGet() }, {}, {},
            { failures.set(it) }, sample = { pts, _ -> sampled.add(pts) })
        try {
            for (pts in 1L..40L) {
                decoder.enqueue(ScrcpyRecord.Packet(pts, byteArrayOf(1)), System.nanoTime())
                assertEquals("Every admitted late frame still reaches the Surface", true, releases.poll(2, TimeUnit.SECONDS))
            }
            assertTrue("observe the last release return before reading counters", drained.await(2, TimeUnit.SECONDS))
            assertNull(failures.get())
            val pending = decoder.snapshot()
            assertEquals(40L, pending.optLong("outputReleases", -1))
            assertEquals(40L, pending.optLong("pendingRenderFrames", -1))
            assertEquals(16L, pending.optLong("renderHistoryEntries", -1))
            assertEquals(24L, pending.optLong("renderHistoryEvictions", -1))
            for (pts in 1L..40L) codec.frameListener!!.onFrameRendered(codec, pts, 0)
            assertEquals(40, callbacks.get())
            assertEquals((1L..40L).toList(), sampled)
            assertTrue("Evicted metadata supplies no invented latency", latency.take(24).all { it == null })
            assertTrue(latency.drop(24).all { it != null })
            assertEquals(0L, decoder.snapshot().optLong("pendingRenderFrames", -1))
            assertNull(failures.get())
        } finally { decoder.close() }
        assertEquals(1, codec.releases.get())
    }

    @Test fun boundedFutureCodecOutputSchedulesWithoutAnAudioClock() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val delivered = CountDownLatch(1)
        val available = AtomicBoolean()
        val queued = AtomicInteger()
        val failure = AtomicReference<Exception>()
        // Keep the output schedule explicit through an unmatched codec PTS, since
        // an already-received matching frame need not inherit the first offset.
        codec.queuedInput = { _, _ -> if (queued.incrementAndGet() == 2) available.set(true) }
        codec.output = { info ->
            if (available.compareAndSet(true, false)) { info.presentationTimeUs = 335_000; 0 } else -1
        }
        codec.releasedOutput = { if (it) delivered.countDown() }
        val decoder = SurfaceDecoder(Surface(), size, {}, {}, {}, { failure.set(it) })
        try {
            val origin = System.nanoTime()
            decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), origin)
            decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(2)), origin)
            assertFalse("Presentation must follow video PTS", delivered.await(100, TimeUnit.MILLISECONDS))
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally { decoder.close() }
    }

    @Test fun excessiveFutureFrameStillFailsAndStopCancelsAnAdmittedWait() {
        for (aheadMs in listOf(335L, 600L)) {
            val codec = MediaCodec().also { MediaCodec.created = it }
            val entered = CountDownLatch(1)
            val failed = CountDownLatch(1)
            val available = AtomicBoolean()
            val queued = AtomicInteger()
            val rendered = AtomicBoolean()
            codec.queuedInput = { _, _ -> if (queued.incrementAndGet() == 2) available.set(true) }
            codec.output = { info ->
                if (available.compareAndSet(true, false)) {
                    info.presentationTimeUs = aheadMs * 1000; entered.countDown(); 0
                } else -1
            }
            codec.releasedOutput = { if (it) rendered.set(true) }
            val decoder = SurfaceDecoder(Surface(), size, {}, {}, {}, { failed.countDown() })
            try {
                val origin = System.nanoTime()
                decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(1)), origin)
                decoder.enqueue(ScrcpyRecord.Packet(0, byteArrayOf(2)), origin)
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                if (aheadMs > 500) assertTrue(failed.await(1, TimeUnit.SECONDS))
                decoder.close()
                assertFalse("Stop must not present a waiting frame", rendered.get())
                assertEquals(1, codec.releases.get())
            } finally { decoder.close() }
        }
    }

    @Test fun lateCompletedUiFrameRendersAndDecoderBacklogRemainsBounded() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val available = AtomicBoolean()
        val rendered = CountDownLatch(1)
        val dropped = AtomicInteger()
        val failure = AtomicReference<Exception>()
        codec.queuedInput = { _, _ -> available.set(true) }
        codec.output = { info -> if (available.compareAndSet(true, false)) { info.presentationTimeUs = 1; 0 } else -1 }
        codec.releasedOutput = { render -> if (render) codec.frameListener!!.onFrameRendered(codec, 1, 0) }
        val decoder = SurfaceDecoder(Surface(), size, { rendered.countDown() }, {}, {}, { failure.set(it) },
            dropped = { dropped.incrementAndGet() })
        try {
            decoder.enqueue(ScrcpyRecord.Packet(1, byteArrayOf(1)), System.nanoTime() - 200_000_000)
            assertTrue("A late final UI redraw must reach the Surface callback", rendered.await(1, TimeUnit.SECONDS))
            assertEquals(0, dropped.get())
            assertNull(failure.get())
        } finally { decoder.close() }
        assertEquals(1, codec.releases.get())
        val blocked = MediaCodec().also { MediaCodec.created = it }
        val next = decoder()
        try {
            for (pts in 1L..16L) next.enqueue(ScrcpyRecord.Packet(pts, byteArrayOf(1)), System.nanoTime())
            assertThrows(IllegalStateException::class.java) { next.enqueue(ScrcpyRecord.Packet(17, byteArrayOf(1)), System.nanoTime()) }
        } finally { next.close() }
        assertEquals(1, blocked.releases.get())
    }

    @Test fun everyInputKindIsRejectedBeforeRenderedVideoAndAfterStop() {
        val session = session()
        val target = Tap(240, 320, size)
        try {
            assertFalse(session.tap(target))
            assertFalse(session.scroll(Scroll(target, 160)))
            assertFalse(session.navigate(Navigation.BACK))
            session.close()
            assertFalse(session.tap(target))
            assertFalse(session.scroll(Scroll(target, -160)))
            assertFalse(session.navigate(Navigation.HOME))
        } finally { session.close() }
    }

    @Test fun stopDuringConstructionReleasesUnpublishedDecoder() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        codec.at = { if (it == "configure") { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) } }
        val session = session()
        val worker = Executors.newFixedThreadPool(2)
        try {
            val construction = worker.submit { session.replaceDecoder(size) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopCalled = CountDownLatch(1)
            val stopping = worker.submit { stopCalled.countDown(); session.close() }
            assertTrue(stopCalled.await(2, TimeUnit.SECONDS))
            assertFalse("Close waits for the in-progress allocation to release", stopping.isDone)
            resume.countDown()
            construction.get(5, TimeUnit.SECONDS)
            stopping.get(5, TimeUnit.SECONDS)
            assertEquals("Stop must close a decoder whose constructor finishes late", 1, codec.releases.get())
            assertNull(ProjectionSession::class.java.getDeclaredField("decoder").apply { isAccessible = true }.get(session))
            assertFalse(Thread.getAllStackTraces().keys.any { it.name == "eyeprojector-decoder" && it.isAlive })
        } finally { resume.countDown(); session.close(); worker.shutdownNow() }
    }

    @Test fun replacementAndRepeatedStopReleaseEachCodecOnce() {
        val session = session()
        val first = MediaCodec().also { MediaCodec.created = it }
        session.replaceDecoder(size)
        val second = MediaCodec().also { MediaCodec.created = it }
        session.replaceDecoder(size)
        session.close(); session.close()
        assertEquals(1, first.releases.get())
        assertEquals(1, second.releases.get())
    }

    @Test fun initializationFailuresReleaseCodecAndPreserveOriginalError() {
        for (stage in listOf("name", "configure", "listener", "start")) {
            val original = IllegalStateException("public test failure at $stage")
            val codec = MediaCodec().also { MediaCodec.created = it }
            codec.at = { if (it == stage) throw original }
            codec.releaseFailure = IllegalStateException("cleanup failure")
            val caught = assertThrows(IllegalStateException::class.java) { decoder() }
            assertSame(original, caught)
            assertEquals("Failed $stage must release its allocation", 1, codec.releases.get())
        }
    }

    @Test fun interruptedCloseReleasesCodecAndPreservesInterrupt() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val output = AtomicReference<Thread>()
        val codec = MediaCodec().also { MediaCodec.created = it }
        codec.at = { if (it == "dequeueOutput") {
            output.set(Thread.currentThread()); entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        } }
        val decoder = decoder()
        val interrupted = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val closer = Thread {
            Thread.currentThread().interrupt()
            try { decoder.close() } catch (error: Throwable) { failure.set(error) }
            finally { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer.start(); closer.join(2000)
            assertFalse("Interrupted cleanup must finish", closer.isAlive)
            assertEquals("Interrupted join must not skip native release", 1, codec.releases.get())
            assertTrue("Cleanup must restore the caller interrupt", interrupted.get())
            assertNull(failure.get())
            decoder.close()
            assertEquals(1, codec.releases.get())
        } finally { resume.countDown(); decoder.close(); closer.join(2000); output.get()?.join(2000) }
    }
}
