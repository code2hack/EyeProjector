package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeldTouchTransportTest {
    private fun touch(phase: Touch.Phase) = Touch(phase, 1000, Tap(10, 20, Geometry(480, 640)))
    @Test fun blockedDownAcceptsEveryMoveAndUpInOrder() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<Touch.Phase>())
        OrderedControlDispatcher { throw AssertionError(it) }.use { controls ->
            val held = HeldTouchTransport(controls, { true }) {
                if (it.phase == Touch.Phase.DOWN) { started.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                events.add(it.phase); if (it.phase == Touch.Phase.UP) done.countDown()
            }
            assertTrue(held.submit(touch(Touch.Phase.DOWN), 1000)); assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(10) { assertTrue(held.submit(touch(Touch.Phase.MOVE), 1001L + it)) }
            assertTrue(held.submit(touch(Touch.Phase.UP), 1011))
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(Touch.Phase.DOWN) + List(10) { Touch.Phase.MOVE } + Touch.Phase.UP, events)
        }
    }
    @Test fun cancellationFollowsInFlightDownAndDropsQueuedReleaseWithoutReplaying() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val canceled = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<Touch.Phase>())
        OrderedControlDispatcher { throw AssertionError(it) }.use { controls ->
            val held = HeldTouchTransport(controls, { true }) {
                if (it.phase == Touch.Phase.DOWN) { started.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                events.add(it.phase); if (it.phase == Touch.Phase.CANCEL) canceled.countDown()
            }
            held.submit(touch(Touch.Phase.DOWN), 1000); assertTrue(started.await(2, TimeUnit.SECONDS))
            held.submit(touch(Touch.Phase.MOVE), 1100); held.submit(touch(Touch.Phase.UP), 1200)
            controls.cancelPending(); held.cancel(0)
            controls.cancelPending("head-scroll")
            release.countDown(); assertTrue(canceled.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), events)
            assertFalse(held.submit(touch(Touch.Phase.UP), 51000))
            val fresh = CountDownLatch(1)
            controls.submit(51000, { true }) { fresh.countDown() }
            assertTrue("Unpaced cancellation must not add its timestamp to fresh input pacing", fresh.await(2, TimeUnit.SECONDS))
        }
    }
    @Test fun explicitCancelDropsQueuedMovesAndBypassesHistoricalHoldDelay() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val canceled = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<Touch.Phase>())
        OrderedControlDispatcher { throw AssertionError(it) }.use { controls ->
            val held = HeldTouchTransport(controls, { true }) {
                if (it.phase == Touch.Phase.DOWN) { started.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                events.add(it.phase); if (it.phase == Touch.Phase.CANCEL) canceled.countDown()
            }
            held.submit(touch(Touch.Phase.DOWN), 1000); assertTrue(started.await(2, TimeUnit.SECONDS))
            held.submit(touch(Touch.Phase.MOVE), 100000)
            assertTrue(held.submit(touch(Touch.Phase.CANCEL), 100001))
            release.countDown(); assertTrue(canceled.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), events)
            assertFalse(held.submit(touch(Touch.Phase.UP), 100002))
        }
    }

}
