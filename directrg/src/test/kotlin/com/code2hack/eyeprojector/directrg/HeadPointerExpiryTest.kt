package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeadPointerExpiryTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun resetClock() { SystemClock.elapsedNs = null }

    private fun field(session: ProjectionSession, name: String) =
        ProjectionSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun model() = HeadPointerModel().apply {
        resize(PointerBounds(0f, 0f, 100f, 100f), 0); start()
        check(sample(RotationSample(1_000_000_000, 0f, 0f, 0f, 1f), 1_010_000_000))
    }
    private fun point(model: HeadPointerModel, sequence: Long, revision: Long) =
        HeadPointerState(revision, sequence, model.position.available, false,
            model.position.x, model.position.y, null, null)

    /** Matched old/new source test: the old producer had no deadline argument and must fail expiry. */
    private fun publish(session: ProjectionSession, state: HeadPointerState, deadline: Long): Boolean {
        val method = ProjectionSession::class.java.declaredMethods.single { it.name == "headPointer" }
        return if (method.parameterCount == 2) method.invoke(session, state, deadline) as Boolean
        else method.invoke(session, state) as Boolean
    }

    private fun queued(coalesceFresh: Boolean) {
        val source = model()
        SystemClock.elapsedNs = 1_010_000_000
        val written = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val output = object : DataOutputStream(bytes) {
            override fun flush() { super.flush(); written.countDown() }
        }
        ProjectionSession(temporary.root, Surface(), { _, _ -> }, { _, _ -> }).use { session ->
            field(session, "geometry").set(session, Geometry(100, 100))
            field(session, "output").set(session, output)
            val lane = field(session, "scaleControls").get(session) as OrderedControlDispatcher
            synchronized(field(session, "outputLock").get(session)) {
                assertTrue(publish(session, point(source, 1, 1), source.expiresAtNs))
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                fun waitingForWriter() = Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.state == Thread.State.BLOCKED && stack.any { it.className.endsWith(".LatestHeadPointer") } &&
                        stack.any { it.className.endsWith(".ProjectionSession") }
                }
                while (!waitingForWriter() && System.nanoTime() < until) Thread.yield()
                assertTrue(waitingForWriter())
                assertEquals(1L, lane.snapshot().inFlight)
                // Deliberately leave advance()/the UI expiry callback undelivered.
                SystemClock.elapsedNs = 1_300_000_000
                assertTrue(source.position.available)
                assertTrue(SystemClock.elapsedRealtimeNanos() >= source.expiresAtNs)
                if (coalesceFresh) {
                    assertTrue(publish(session, point(source, 2, 2).copy(available = false), source.expiresAtNs))
                    assertTrue(source.sample(RotationSample(1_300_000_000, 0f, 0f, 0f, 1f), 1_310_000_000))
                    SystemClock.elapsedNs = 1_310_000_000
                    assertTrue(publish(session, point(source, 3, 3), source.expiresAtNs))
                }
                session.cancelPendingInput() // Native queue cancellation must not strand the pointer pump.
            }
            assertTrue(written.await(2, TimeUnit.SECONDS))
            val input = DataInputStream(bytes.toByteArray().inputStream())
            val frame = Wire.readFrame(input)
            assertEquals(Wire.HEAD_POINTER, frame.channel)
            val actual = HeadPointerState.from(frame.payload)
            assertEquals(coalesceFresh, actual.available)
            assertEquals(if (coalesceFresh) 3L else 1L, actual.sequence)
            assertEquals(if (coalesceFresh) 3L else 1L, actual.revision)
            assertEquals(0, input.available())
        }
    }

    @Test fun actualQueuedWriterMarksSourceUnavailableAfterExistingDeadlineWithUiExpiryDelayed() = queued(false)
    @Test fun actualWriterSelectsFreshLatestContextAfterItsLockWaitAndNativeCancellation() = queued(true)
}
