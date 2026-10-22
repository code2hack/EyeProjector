package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real ProjectionSession submission/serialization with a controlled output stream, not physical injection. */
class ProjectionInputTest {
    @get:Rule val temporary = TemporaryFolder()
    private val geometry = Geometry(480, 640)
    private fun set(session: ProjectionSession, name: String, value: Any) {
        ProjectionSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }
    private fun controls(session: ProjectionSession): OrderedControlDispatcher =
        ProjectionSession::class.java.getDeclaredField("controls").apply { isAccessible = true }.get(session) as OrderedControlDispatcher
    private fun ready(output: OutputStream, completed: (ReceiverInput) -> Unit = {},
        state: (String) -> Unit = {}): ProjectionSession =
        ProjectionSession(temporary.root.resolve("session"), Surface(), { _, value -> state(value) }, { _, _ -> },
            onReceiverInput = { _, event -> completed(event) }).also {
            set(it, "geometry", geometry)
            val readiness = ProjectionSession::class.java.getDeclaredField("videoReadiness").apply { isAccessible = true }.get(it) as VideoReadiness
            val epoch = readiness.beginReplacement()!!
            check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
            set(it, "output", DataOutputStream(output))
        }

    @Test fun actualPointerWriterCoalescesAcrossNativeCancellationWithoutAStuckScheduledFlag() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(2)
        val first = AtomicBoolean(true); val buffer = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                buffer.write(value)
            }
            override fun flush() { done.countDown() }
        }
        ready(output).use { session ->
            fun point(sequence: Long, revision: Long) = HeadPointerState(revision, sequence, true, true,
                120f, 240f, Fit(0, 0, 480, 640), geometry)
            assertTrue(session.headPointer(point(1, 1), Long.MAX_VALUE)); assertTrue(entered.await(2, TimeUnit.SECONDS))
            for (n in 2L..1000L) session.headPointer(point(n, if (n < 900) 2 else 3), Long.MAX_VALUE)
            session.cancelPendingInput() // Cancels native timed input; pointer pump remains on existing separate writer lane.
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
            val input = DataInputStream(buffer.toByteArray().inputStream())
            val firstPoint = Wire.readFrame(input); val lastPoint = Wire.readFrame(input)
            assertEquals(Wire.HEAD_POINTER, firstPoint.channel); assertEquals(Wire.HEAD_POINTER, lastPoint.channel)
            assertEquals(1, HeadPointerState.from(firstPoint.payload).sequence)
            assertEquals(1000, HeadPointerState.from(lastPoint.payload).sequence)
            assertEquals(3, HeadPointerState.from(lastPoint.payload).revision)
            assertEquals(0, input.available())
        }
    }

    @Test fun actualReceiverConsumerAcceptsCompletedUiAndRejectsIncomingPointerDirection() {
        val events = mutableListOf<ReceiverInput>()
        ready(ByteArrayOutputStream(), completed = events::add).use { session ->
            val event = ReceiverInput(ReceiverInputKind.PANEL, 7)
            session.receive(Frame(Wire.RECEIVER_INPUT, payload = event.bytes()))
            assertEquals(listOf(event), events)
            assertThrows(IllegalStateException::class.java) { session.receive(Frame(Wire.HEAD_POINTER,
                payload = HeadPointerState(1, 1, false, false, 0f, 0f, null, null).bytes())) }
        }
    }

    @Test fun normalizedBurstSurvivesTheActualSessionSubmissionAndWirePath() {
        val blocked = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(4)
        val first = AtomicBoolean(true)
        val buffer = ByteArrayOutputStream()
        val at = Collections.synchronizedList(mutableListOf<Long>())
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (first.compareAndSet(true, false)) { blocked.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                buffer.write(value)
            }
            override fun flush() { at.add(System.nanoTime()); done.countDown() }
        }
        ready(output).use { session ->
            val point = PointerPosition(123f, 234f, true)
            val pad = PadGestureRecognizer(300, 500) { action -> when (action) {
                is PadGestureRecognizer.Action.Tap -> assertTrue(session.tap(Tap(123, 234, geometry),
                    action.downTimeMs + action.timeMs - action.upTimeMs))
                is PadGestureRecognizer.Action.Scroll -> assertTrue(session.scroll(Scroll(Tap(123, 234, geometry), action.pixels), action.timeMs))
                else -> fail("Unexpected action $action")
            } }
            fun key(key: PadGestureRecognizer.Key, t: Long) {
                pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, t, t, position = point), t)
                pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, t + 22, t), t + 22)
            }
            for (t in listOf(1000L, 1190L, 1343L, 1500L)) key(PadGestureRecognizer.Key.CONTACT, t)
            key(PadGestureRecognizer.Key.FORWARD, 1630)
            pad.confirm(3000)
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            assertEquals(4L, controls(session).snapshot().accepted)
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
            val stream = DataInputStream(buffer.toByteArray().inputStream())
            val frames = (1..4).map { Wire.readFrame(stream) }
            assertEquals(listOf(Wire.TAP, Wire.TAP, Wire.TAP, Wire.SCROLL), frames.map { it.channel })
            assertTrue(frames.take(3).all { Tap.from(it.payload) == Tap(123, 234, geometry) })
            assertEquals(Scroll(Tap(123, 234, geometry), 160), Scroll.from(frames.last().payload))
            assertEquals(0, stream.available())
            assertTrue((at[1] - at[0]) / 1_000_000 >= 150)
            assertTrue((at[2] - at[1]) / 1_000_000 >= 110)
        }
    }

    @Test fun focusCancellationAndGeometryReplacementDropAcceptedQueuedControls() {
        val blocked = CountDownLatch(1); val release = CountDownLatch(1); val firstDone = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (first.compareAndSet(true, false)) { blocked.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            }
            override fun flush() { firstDone.countDown() }
        }
        ready(output).use { session ->
            assertTrue(session.tap(Tap(10, 20, geometry), 1000))
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            assertTrue(session.scroll(Scroll(Tap(10, 20, geometry), 160), 1100))
            session.cancelPendingInput()
            assertEquals(1L, controls(session).snapshot().canceled)
            set(session, "geometry", Geometry(640, 480))
            assertFalse(session.tap(Tap(10, 20, geometry), 1200))
            release.countDown(); assertTrue(firstDone.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun acceptedWriteFailureIsReportedWithoutClaimingASuccessfulWrite() {
        val disconnected = CountDownLatch(1)
        ready(object : OutputStream() { override fun write(value: Int) { throw IOException("test socket write failure") } }) {
            if (it == "DISCONNECTED") disconnected.countDown()
        }.use { session ->
            assertTrue(session.tap(Tap(10, 20, geometry), 1000))
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            val counts = controls(session).snapshot()
            assertEquals(1L, counts.accepted); assertEquals(0L, counts.written); assertEquals(1L, counts.failed)
            assertFalse(session.scroll(Scroll(Tap(10, 20, geometry), -160), 1200))
        }
    }
    @Test fun actualSessionDeliversAllHeldTransitionsAndCancelsBehindInFlightDown() {
        for (cancel in listOf(false, true)) {
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            val done = CountDownLatch(if (cancel) 2 else 3)
            val first = AtomicBoolean(true)
            val buffer = ByteArrayOutputStream()
            val out = object : OutputStream() {
                override fun write(value: Int) {
                    if (first.compareAndSet(true, false)) { started.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                    buffer.write(value)
                }
                override fun flush() { done.countDown() }
            }
            ready(out).use { session ->
                fun event(phase: Touch.Phase, x: Int) = Touch(phase, 1000, Tap(x, 20, geometry))
                assertTrue(session.touch(event(Touch.Phase.DOWN, 10), 1000))
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertTrue(session.touch(event(Touch.Phase.MOVE, 100), 1010))
                assertTrue(session.touch(event(Touch.Phase.UP, 200), 1020))
                if (cancel) session.cancelPendingInput()
                release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
                val input = DataInputStream(buffer.toByteArray().inputStream())
                val frames = (1..if (cancel) 2 else 3).map { Wire.readFrame(input) }
                assertTrue(frames.all { it.channel == Wire.TOUCH })
                val events = frames.map { Touch.from(it.payload) }
                assertEquals(if (cancel) listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL)
                    else listOf(Touch.Phase.DOWN, Touch.Phase.MOVE, Touch.Phase.UP), events.map { it.phase })
                assertEquals(if (cancel) 10 else 200, events.last().target.x)
                assertTrue(events.all { it.contactId == 1000L }); assertEquals(0, input.available())
            }
        }
    }

}
