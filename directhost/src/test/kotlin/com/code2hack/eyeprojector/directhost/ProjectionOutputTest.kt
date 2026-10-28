package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.Wire
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ProjectionOutputTest {
    private fun awaitTermination(lane: SessionPongLane) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!lane.terminated && System.nanoTime() < until) Thread.yield()
        assertTrue("Session PONG worker did not retire", lane.terminated)
    }

    @Test fun blockedSendDoesNotBlockAdmissionAndEveryStampUsesTheSameWorker() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val sent = CountDownLatch(3)
        val stamps = CopyOnWriteArrayList<Long>(); val workers = CopyOnWriteArrayList<Long>()
        val failed = AtomicReference<Throwable?>()
        val lane = SessionPongLane({ stamp ->
            if (stamp == 11L) { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
            stamps.add(stamp); workers.add(Thread.currentThread().id); sent.countDown()
        }, failed::set)
        val admission = Executors.newSingleThreadExecutor()
        try {
            lane.submit(11L)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            admission.submit { lane.submit(-9L); lane.submit(Long.MAX_VALUE) }.get(1, TimeUnit.SECONDS)
            assertEquals(3L, sent.count)
            finish.countDown()
            assertTrue(sent.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(11L, -9L, Long.MAX_VALUE), stamps)
            assertEquals(1, workers.toSet().size)
            assertNull(failed.get())
        } finally {
            finish.countDown(); lane.retire(); admission.shutdownNow(); awaitTermination(lane)
        }
    }

    @Test fun retirementDropsPendingPongsWithoutInterruptingAnInFlightSend() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val stamps = CopyOnWriteArrayList<Long>(); val interrupted = AtomicBoolean()
        val lane = SessionPongLane({ stamp ->
            entered.countDown(); check(finish.await(3, TimeUnit.SECONDS))
            interrupted.set(Thread.currentThread().isInterrupted); stamps.add(stamp)
        }, {})
        try {
            lane.submit(1L)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            lane.submit(2L); lane.retire(); lane.submit(3L)
            finish.countDown(); awaitTermination(lane)
            assertEquals(listOf(1L), stamps)
            assertFalse(interrupted.get())
            assertNull(lane.failure)
        } finally { finish.countDown(); lane.retire(); awaitTermination(lane) }
    }

    @Test fun failedPongRetiresItsOwnWorkerAndRetainsCleanupFailureSeparately() {
        val primary = IOException("PONG write")
        val cleanup = IllegalStateException("incomplete release")
        val received = AtomicReference<Throwable?>(); val interrupted = AtomicBoolean()
        lateinit var lane: SessionPongLane
        lane = SessionPongLane({ throw primary }, { error ->
            received.set(error); lane.retire()
            interrupted.set(Thread.currentThread().isInterrupted)
            throw cleanup
        })
        try {
            lane.submit(55L); awaitTermination(lane)
            assertSame(primary, received.get())
            assertSame(primary, lane.failure)
            assertSame(cleanup, lane.cleanupFailure)
            assertFalse(interrupted.get())
        } finally { lane.retire(); awaitTermination(lane) }
    }

    @Test fun capturedWriteRetainsItsIdentityAndOutcomeAcrossLaterWrites() {
        val writes = ProjectionWrites()
        val first = writes.begin(Wire.VIDEO, 4096)
        val captured = writes.active.get()!!
        val started = captured.startedMonoNs
        val writerId = captured.writerId; val writerName = captured.writerName
        val next = writes.begin(Wire.PONG, 0)
        val failure = IOException("original VIDEO write")
        writes.complete(first, failure)
        assertSame(next, writes.active.get())
        assertSame(first, captured)
        assertEquals(Wire.VIDEO, captured.channel)
        assertEquals(4096, captured.bytes)
        assertEquals(started, captured.startedMonoNs)
        assertEquals(writerId, captured.writerId); assertEquals(writerName, captured.writerName)
        val outcome = captured.completion.get()!!
        assertSame(failure, outcome.failure)
        assertTrue(outcome.endedMonoNs >= started)
        assertTrue(next.id > captured.id)
        writes.complete(next, null)
        assertNull(writes.active.get())
        assertSame(outcome, captured.completion.get())
        val laterFailure = writes.begin(Wire.PONG, 0)
        writes.complete(laterFailure, IOException("later teardown write"))
        assertSame("Later failures must not replace the initiating attempt", captured, writes.failed.get())
    }
}
