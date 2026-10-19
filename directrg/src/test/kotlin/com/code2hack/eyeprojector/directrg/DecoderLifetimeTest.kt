package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Navigation
import com.code2hack.eyeprojector.protocol.Scroll
import com.code2hack.eyeprojector.protocol.Tap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun nativeUiFrameWithObserved335msLeadWaitsForTheAudioClock() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val delivered = CountDownLatch(1)
        val once = AtomicBoolean()
        val failure = AtomicReference<Exception>()
        codec.output = { info ->
            if (once.compareAndSet(false, true)) { info.presentationTimeUs = 1_000_000; 0 } else -1
        }
        codec.releasedOutput = { if (it) delivered.countDown() }
        val decoder = SurfaceDecoder(Surface(), size, {}, {}, {}, { failure.set(it) },
            { System.nanoTime() + 335_000_000L })
        try {
            val rendered = delivered.await(1, TimeUnit.SECONDS)
            assertNull("A native UI update must fit the bounded future-frame wait", failure.get())
            assertTrue(rendered)
        } finally { decoder.close() }
    }

    @Test fun excessiveFutureFrameStillFailsAndStopCancelsAnAdmittedWait() {
        for (aheadMs in listOf(335L, 600L)) {
            val codec = MediaCodec().also { MediaCodec.created = it }
            val entered = CountDownLatch(1)
            val failed = CountDownLatch(1)
            val once = AtomicBoolean()
            val rendered = AtomicBoolean()
            codec.output = { info ->
                if (once.compareAndSet(false, true)) { info.presentationTimeUs = 1_000_000; 0 } else -1
            }
            codec.releasedOutput = { if (it) rendered.set(true) }
            val decoder = SurfaceDecoder(Surface(), size, {}, {}, {}, { failed.countDown() },
                { entered.countDown(); System.nanoTime() + aheadMs * 1_000_000 })
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                if (aheadMs > 500) assertTrue(failed.await(1, TimeUnit.SECONDS))
                decoder.close()
                assertFalse("Stop must not present a waiting frame", rendered.get())
                assertEquals(1, codec.releases.get())
            } finally { decoder.close() }
        }
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
        val worker = Executors.newSingleThreadExecutor()
        try {
            val construction = worker.submit { session.replaceDecoder(size) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            session.close()
            resume.countDown()
            construction.get(5, TimeUnit.SECONDS)
            assertEquals("Stop must close a decoder whose constructor finishes late", 1, codec.releases.get())
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
