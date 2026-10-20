package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OrderedControlDispatcherTest {
    @Test fun blockedFirstWriteStillAcceptsMixedBurstInOrderAndPreservesIntervals() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(4)
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        OrderedControlDispatcher { throw AssertionError(it) }.use { q ->
            assertTrue(q.submit(1000, { true }) { started.countDown(); check(release.await(2, TimeUnit.SECONDS)); writes.add("tap1" to System.nanoTime()); done.countDown() })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            for ((at, name) in listOf(1100L to "tap2", 1210L to "scrollDown", 1350L to "scrollUp")) {
                assertTrue(q.submit(at, { true }) { writes.add(name to System.nanoTime()); done.countDown() })
            }
            assertEquals(4L, q.snapshot().accepted); assertEquals(0L, q.snapshot().written)
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("tap1", "tap2", "scrollDown", "scrollUp"), writes.map { it.first })
            assertTrue((writes[1].second - writes[0].second) / 1_000_000 >= 60)
            assertTrue((writes[2].second - writes[1].second) / 1_000_000 >= 70)
            assertTrue((writes[3].second - writes[2].second) / 1_000_000 >= 100)
            // Completion latch fires inside write, so enqueue a barrier before reading final counters.
            val barrier = CountDownLatch(1); q.submit(1350, { true }) { barrier.countDown() }
            assertTrue(barrier.await(2, TimeUnit.SECONDS))
            assertTrue(q.snapshot().written >= 4)
        }
    }
    @Test fun cancelDropsQueuedWorkAndFreshInputUsesFreshTiming() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        OrderedControlDispatcher {}.use { q ->
            q.submit(1000, { true }) { started.countDown(); release.await(2, TimeUnit.SECONDS); writes.add(1) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            q.submit(1100, { true }) { writes.add(2) }
            q.cancelPending()
            q.submit(9000, { true }) { writes.add(3); done.countDown() }
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), writes)
            assertEquals(1L, q.snapshot().canceled)
        }
    }
    @Test fun changedGeometryAndWriteFailuresHaveSeparateAccounting() {
        val failed = CountDownLatch(1)
        OrderedControlDispatcher { failed.countDown() }.use { q ->
            q.submit(1000, { false }) { fail("stale geometry write") }
            q.submit(1001, { true }) { throw IOException("closed socket") }
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertEquals(2L, q.snapshot().accepted)
            assertEquals(0L, q.snapshot().written)
            assertEquals(1L, q.snapshot().canceled)
            assertEquals(1L, q.snapshot().failed)
            q.close()
            assertFalse(q.submit(1002, { true }) {})
            assertEquals(1L, q.snapshot().rejected)
        }
    }
    @Test fun returningAimCancelsQueuedHeadScrollWhileRetainingTap() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<String>())
        OrderedControlDispatcher {}.use { q ->
            q.submit(1000, { true }) { started.countDown(); release.await(2, TimeUnit.SECONDS); writes.add("first") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            q.submit(1050, { true }, "head-scroll") { writes.add("old head scroll") }
            q.submit(1100, { true }) { writes.add("tap"); done.countDown() }
            q.cancelPending("head-scroll")
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("first", "tap"), writes)
            assertEquals(1L, q.snapshot().canceled)
        }
    }
}
