package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LiveScaleTest {
    private fun checkedThread(failure: AtomicReference<Throwable?>, action: () -> Unit) = thread(isDaemon = true) {
        try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
    }

    @Test fun requestAfterLandscapeGeometryReceiptWaitsForFeedbackAndUsesLandscapeTarget() {
        val publicationLock = Any()
        val controlLock = Any()
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        fun send(frame: Frame) = synchronized(publicationLock) { Wire.writeFrame(output, frame) }
        val writes = Collections.synchronizedList(mutableListOf<Geometry>())
        val inputGeometry = AtomicReference<Geometry>()
        val scale = LiveScale({ synchronized(controlLock) { writes.add(it) } }, {
            send(Frame(Wire.SCALE_STATE, it.requestId, it.bytes()))
        })
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        bytes.reset()
        val published = CountDownLatch(1)
        val finishObservation = CountDownLatch(1)
        val requestStarted = CountDownLatch(1)
        val requestDone = CountDownLatch(1)
        val pongStarted = CountDownLatch(1)
        val pongDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val observer = checkedThread(failure) {
            scale.observe(ScrcpyRecord.Session(Geometry(640, 480), false), publicationLock) {
                synchronized(controlLock) { inputGeometry.set(Geometry(640, 480)) }
                send(Frame(Wire.GEOMETRY, payload = inputGeometry.get().bytes()))
                published.countDown()
                check(finishObservation.await(2, TimeUnit.SECONDS))
            }
        }
        var requester: Thread? = null
        var pong: Thread? = null
        try {
            assertTrue(published.await(2, TimeUnit.SECONDS))
            // Decode the actual published frame before starting the request, as a receiver does.
            val received = Wire.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            assertEquals(Wire.GEOMETRY, received.channel)
            val dimensions = DataInputStream(ByteArrayInputStream(received.payload))
            assertEquals(Geometry(640, 480), Geometry(dimensions.readInt(), dimensions.readInt()))
            requester = checkedThread(failure) {
                requestStarted.countDown()
                scale.request(42, 1.25)
                scale.request(43, Double.NaN)
                requestDone.countDown()
            }
            pong = checkedThread(failure) {
                pongStarted.countDown()
                send(Frame(Wire.PONG, 7))
                pongDone.countDown()
            }
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
            assertTrue(pongStarted.await(2, TimeUnit.SECONDS))
            assertFalse(requestDone.await(100, TimeUnit.MILLISECONDS))
            assertFalse(pongDone.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishObservation.countDown()
            listOfNotNull(observer, requester, pong).forEach { it.join(2000); assertFalse(it.isAlive) }
        }
        failure.get()?.let { throw AssertionError("Publication worker failed", it) }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val frames = mutableListOf<Frame>()
        while (input.available() > 0) frames.add(Wire.readFrame(input))
        assertEquals(listOf(Wire.GEOMETRY, Wire.SCALE_STATE), frames.take(2).map { it.channel })
        val states = frames.filter { it.channel == Wire.SCALE_STATE }.map { ScaleState.from(it.payload) }
        assertEquals(listOf(ScaleStatus.READY, ScaleStatus.PENDING, ScaleStatus.REJECTED), states.map { it.status })
        assertTrue(states.all { it.observed == Geometry(640, 480) })
        assertEquals(42L, states[1].requestId)
        assertEquals(43L, states[2].requestId)
        assertEquals(listOf(Geometry(512, 384)), writes)
        assertEquals(42L, scale.state().requestId)
        assertEquals(1, frames.count { it.channel == Wire.PONG })
    }

    @Test fun observationCallbackSeesNewGeometryAndCannotOverwriteAReentrantRequest() {
        val writes = mutableListOf<Geometry>()
        val states = mutableListOf<ScaleState>()
        val scale = LiveScale(writes::add, states::add)
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        states.clear()
        scale.observe(ScrcpyRecord.Session(Geometry(640, 480), false)) {
            assertEquals(Geometry(640, 480), scale.state().observed)
            scale.request(42, 1.25)
        }
        assertEquals(listOf(Geometry(512, 384)), writes)
        assertEquals(listOf(42L, 42L), states.map { it.requestId })
        assertTrue(states.all { it.status == ScaleStatus.PENDING && it.observed == Geometry(640, 480) })
        assertEquals(states.last(), scale.state())
    }

    @Test fun publicationFailuresRetainActualGeometryAndUnwindBeforeCloseCanReadScale() {
        for (failFeedback in listOf(false, true)) {
            val publicationLock = Any()
            val controlLock = Any()
            val marker = IOException("modeled transport failure")
            val writes = mutableListOf<Geometry>()
            var feedbackAttempts = 0
            val scale = LiveScale({ synchronized(controlLock) { writes.add(it) } }, {
                synchronized(publicationLock) { feedbackAttempts++; if (failFeedback) throw marker }
            })
            try {
                scale.observe(ScrcpyRecord.Session(Geometry(640, 480), false), publicationLock) {
                    synchronized(controlLock) { if (!failFeedback) throw marker }
                }
                fail("Expected publication failure")
            } catch (error: IOException) {
                assertSame(marker, error)
                assertFalse(Thread.holdsLock(scale))
                assertFalse(Thread.holdsLock(publicationLock))
                assertFalse(Thread.holdsLock(controlLock))
            }
            val failure = AtomicReference<Throwable?>()
            val closeSnapshot = AtomicReference<ScaleState>()
            val closer = checkedThread(failure) { closeSnapshot.set(scale.state()) }
            closer.join(2000)
            assertFalse(closer.isAlive)
            failure.get()?.let { throw AssertionError("Close snapshot failed", it) }
            assertEquals(Geometry(640, 480), closeSnapshot.get().observed)
            assertEquals(if (failFeedback) 1 else 0, feedbackAttempts)
            assertTrue(writes.isEmpty())
        }
    }

    @Test fun localStartupDoesNotPublishBeforeWelcomeButEstablishedStreamGetsUnavailableReply() {
        val frames = mutableListOf<ScaleState>()
        val scale = LiveScale({}, frames::add)
        assertEquals(ScaleStatus.UNAVAILABLE, scale.request(1, 1.25).status)
        assertEquals(ScaleStatus.REJECTED, scale.request(2, Double.NaN).status)
        assertTrue(frames.isEmpty())
        val reply = scale.request(3, 1.25, publishUnavailable = true)
        assertEquals(listOf(reply), frames)
        assertEquals(ScaleStatus.UNAVAILABLE, reply.status)
    }

    @Test fun rejectedReplyCannotOvertakeANewerGeometryObservation() {
        val enteredReply = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val observerStarted = CountDownLatch(1)
        val frames = Collections.synchronizedList(mutableListOf<ScaleState>())
        val scale = LiveScale({}, { value ->
            if (value.status == ScaleStatus.REJECTED) {
                enteredReply.countDown()
                check(releaseReply.await(2, TimeUnit.SECONDS))
            }
            frames.add(value)
        })
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        val request = thread { scale.request(1, -1.0) }
        assertTrue(enteredReply.await(2, TimeUnit.SECONDS))
        val observation = thread {
            observerStarted.countDown()
            scale.observe(ScrcpyRecord.Session(Geometry(384, 512), true))
        }
        try { assertTrue(observerStarted.await(2, TimeUnit.SECONDS)) }
        finally { releaseReply.countDown() }
        request.join(2000); observation.join(2000)
        assertFalse(request.isAlive); assertFalse(observation.isAlive)
        assertEquals(listOf(Geometry(480, 640), Geometry(480, 640), Geometry(384, 512)), frames.map { it.observed })
        assertEquals(ScaleStatus.REJECTED, frames[1].status)
        assertEquals(Geometry(384, 512), scale.state().observed)
    }

    @Test fun pendingControlWriteDoesNotBecomeAppliedUntilBackendGeometryArrives() {
        val writes = mutableListOf<Geometry>()
        val states = mutableListOf<ScaleState>()
        val scale = LiveScale(writes::add, states::add)
        assertEquals(ScaleStatus.UNAVAILABLE, scale.request(1, 1.25).status)
        assertTrue(writes.isEmpty())
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        assertEquals(ScaleStatus.READY, scale.state().status)
        val pending = scale.request(2, 1.3)
        assertEquals(ScaleStatus.PENDING, pending.status)
        assertEquals(Geometry(480, 640), pending.observed)
        assertEquals(listOf(Geometry(369, 492)), writes)
        // Codec alignment can alter the dimensions independently; report what stock actually emits.
        scale.observe(ScrcpyRecord.Session(Geometry(368, 488), true))
        val result = scale.state()
        assertEquals(ScaleStatus.OBSERVED, result.status)
        assertEquals(1.3, result.requested, 0.0)
        assertEquals(Geometry(368, 488), result.observed)
        assertEquals(480.0 / 368, LayoutScale.applied(result.observed!!), 1e-12)
        assertEquals(2L, result.requestId)
    }

    @Test fun rapidRequestsUseStockDebouncingAndFeedbackAlwaysReportsActualIntermediateGeometry() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        scale.request(1, 1.25)
        scale.request(2, 1.5)
        scale.request(3, 2.0)
        assertEquals(listOf(Geometry(384, 512), Geometry(320, 427), Geometry(240, 320)), writes)
        scale.observe(ScrcpyRecord.Session(Geometry(384, 512), true))
        assertEquals(3L, scale.state().requestId)
        assertEquals(2.0, scale.state().requested, 0.0)
        assertEquals(1.25, LayoutScale.applied(scale.state().observed!!), 0.0)
        scale.observe(ScrcpyRecord.Session(Geometry(240, 320), true))
        assertEquals(ScaleStatus.OBSERVED, scale.state().status)
        assertEquals(2.0, LayoutScale.applied(scale.state().observed!!), 0.0)
    }

    @Test fun roundedNoOpCannotBlockLaterRequestsAndReturningToCurrentSizeCancelsPendingResize() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        scale.request(1, .999)
        assertEquals(Geometry(480, 641), writes.last())
        // The codec may align this back to480x640 and never emit another session record.
        scale.request(2, 1.25)
        assertEquals(Geometry(384, 512), writes.last())
        scale.request(3, 1.0)
        assertEquals(Geometry(480, 640), writes.last())
        assertEquals(3, writes.size)
        assertEquals(1.0, LayoutScale.applied(scale.state().observed!!), 0.0)
        assertEquals(ScaleStatus.PENDING, scale.state().status)
    }

    @Test fun invalidRequestRetainsPriorObservedAndPendingWork() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        scale.request(1, 1.25)
        val before = scale.state()
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, .01, 2000.0)) {
            val rejected = scale.request(9, invalid)
            assertEquals(ScaleStatus.REJECTED, rejected.status)
            assertEquals(before.observed, rejected.observed)
            assertEquals(before, scale.state())
        }
        assertEquals(1, writes.size)
        scale.observe(ScrcpyRecord.Session(Geometry(384, 512), true))
        assertEquals(1L, scale.state().requestId)
        assertEquals(ScaleStatus.OBSERVED, scale.state().status)
    }

    @Test fun rotationUpdatesObservedCoordinatesWithoutConfirmingAnOutstandingResize() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(640, 480), false))
        scale.request(1, 1.25)
        assertEquals(listOf(Geometry(512, 384)), writes)
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        assertEquals(ScaleStatus.INTERRUPTED, scale.state().status)
        assertEquals(Geometry(480, 640), scale.state().observed)
        assertFalse(scale.state().clientResize)
        scale.request(2, 1.25)
        assertEquals(listOf(Geometry(512, 384), Geometry(384, 512)), writes)
    }
}
