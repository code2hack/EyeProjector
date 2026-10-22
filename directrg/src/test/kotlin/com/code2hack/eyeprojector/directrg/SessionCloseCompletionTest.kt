package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual session and decoder close with a blocked platform codec release boundary. */
class SessionCloseCompletionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun secondCloseWaitsForCodecReleaseBeforeFreshSessionCanOwnResources() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(2)
        val secondCalled = CountDownLatch(1); val secondDone = CountDownLatch(1)
        val codec = MediaCodec().also { MediaCodec.created = it }
        val failOutput = AtomicBoolean()
        codec.at = {
            if (it == "dequeueOutput" && failOutput.get()) error("Synthetic output failure")
            if (it == "release") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        }
        val session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, state ->
            if (state == "DECODE_FAILED") done.countDown()
        }, { _, _ -> })
        session.replaceDecoder(Geometry(480, 640))
        failOutput.set(true)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread { secondCalled.countDown(); session.close(); secondDone.countDown(); done.countDown() }.start()
            assertTrue(secondCalled.await(2, TimeUnit.SECONDS))
            assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS))
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, codec.releases.get())
        } finally { release.countDown(); session.close() }
    }

    @Test fun sameThreadReleaseReentryAndTerminalCallbackCloseCannotDeadlock() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        lateinit var session: ProjectionSession
        val terminal = AtomicBoolean()
        session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, value ->
            if (value == "STOPPED") {
                val other = Thread { session.close() }.apply { start() }
                other.join(1000)
                check(!other.isAlive)
                terminal.set(true)
            }
        }, { _, _ -> })
        codec.at = { if (it == "release") session.close() }
        session.replaceDecoder(Geometry(480, 640))
        session.close()
        assertTrue(terminal.get())
        assertEquals(1, codec.releases.get())
        session.replaceDecoder(Geometry(640, 480))
        assertEquals(1, codec.releases.get())
    }

    @Test fun reentrantCloseDuringGeometryCallbackAllocatesNoLateDecoder() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        lateinit var session: ProjectionSession
        session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(), { _, _ -> }, { _, _ -> session.close() })
        session.replaceDecoder(Geometry(480, 640))
        assertEquals(0, codec.releases.get())
        session.close()
    }
}
