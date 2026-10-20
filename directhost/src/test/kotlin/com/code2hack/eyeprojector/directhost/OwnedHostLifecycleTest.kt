package com.code2hack.eyeprojector.directhost

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OwnedHostLifecycleTest {
    private class FakeBackend : OwnedHostLifecycle.Backend {
        val running = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun run() { running.countDown(); released.await(3, TimeUnit.SECONDS) }
        override fun close() { released.countDown() }
    }
    private fun waitFor(host: OwnedHostLifecycle, state: OwnedHostLifecycle.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (host.status().state != state && System.nanoTime() < deadline) Thread.yield()
        assertEquals(state, host.status().state)
    }

    @Test fun repeatedStartsOwnOneBackendAndReadinessComesFromBackend() {
        val host = OwnedHostLifecycle()
        val creates = AtomicInteger()
        val prepare = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val backend = FakeBackend()
        val factory: (() -> Unit) -> OwnedHostLifecycle.Backend = { ready ->
            creates.incrementAndGet(); entered.countDown()
            assertTrue(prepare.await(3, TimeUnit.SECONDS)); ready(); backend
        }
        host.start(factory); assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(20) { host.start(factory) }
        assertEquals(1, creates.get())
        assertEquals(OwnedHostLifecycle.State.STARTING, host.status().state)
        prepare.countDown(); assertTrue(backend.running.await(3, TimeUnit.SECONDS))
        waitFor(host, OwnedHostLifecycle.State.READY)
        assertEquals(OwnedHostLifecycle.State.STOPPED, host.stop().state)
    }

    @Test fun stopDuringConstructionNeverRunsLateBackendAndCanRestart() {
        val host = OwnedHostLifecycle()
        val entered = CountDownLatch(1)
        val prepare = CountDownLatch(1)
        val backend = FakeBackend()
        host.start { ready -> entered.countDown(); prepare.await(3, TimeUnit.SECONDS); ready(); backend }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertEquals(OwnedHostLifecycle.State.STOP_UNVERIFIED, host.stop(1).state)
        prepare.countDown(); waitFor(host, OwnedHostLifecycle.State.STOPPED)
        assertEquals(1L, backend.running.count)
        assertEquals(0L, backend.released.count)
        val fresh = FakeBackend()
        host.start { ready -> ready(); fresh }
        assertTrue(fresh.running.await(3, TimeUnit.SECONDS))
        assertEquals(OwnedHostLifecycle.State.STOPPED, host.stop().state)
    }

    @Test fun startupFailureIsActionableAndStopIsVerifiedAfterWorkerExits() {
        val host = OwnedHostLifecycle()
        host.start { throw IllegalStateException("fixture failure") }
        waitFor(host, OwnedHostLifecycle.State.FAILED)
        assertEquals("BACKEND_START_OR_RUN_FAILED", host.status().failure)
        assertEquals(OwnedHostLifecycle.State.STOPPED, host.stop().state)
    }

    @Test fun closeFailureDoesNotAllowASecondOwnedBackend() {
        val host = OwnedHostLifecycle()
        val allowClose = CountDownLatch(1)
        host.start { object : OwnedHostLifecycle.Backend {
            override fun run() = Unit
            override fun close() { check(allowClose.count == 0L) }
        } }
        waitFor(host, OwnedHostLifecycle.State.STOP_UNVERIFIED)
        host.start { fail("a second backend must not replace unverified teardown"); FakeBackend() }
        assertEquals(OwnedHostLifecycle.State.STOP_UNVERIFIED, host.stop().state)
        allowClose.countDown()
        assertEquals(OwnedHostLifecycle.State.STOPPED, host.stop().state)
    }

    @Test fun backendInitiatedStopClearsOwnershipAndRepeatedStopIsIdempotent() {
        val host = OwnedHostLifecycle()
        val backend = FakeBackend()
        host.start { ready -> ready(); backend }
        assertTrue(backend.running.await(3, TimeUnit.SECONDS))
        backend.close(); waitFor(host, OwnedHostLifecycle.State.STOPPED)
        repeat(20) { assertEquals(OwnedHostLifecycle.State.STOPPED, host.stop().state) }
    }
}
