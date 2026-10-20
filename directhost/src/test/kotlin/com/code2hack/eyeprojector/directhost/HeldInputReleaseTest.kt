package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeldInputReleaseTest {
    @Test fun blockedDownCannotPreventTheFollowingTransportTeardown() {
        val started = CountDownLatch(1)
        val transportClosed = CountDownLatch(1)
        val downFinished = CountDownLatch(1)
        val host = PhoneTouchDispatcher {
            started.countDown()
            check(transportClosed.await(3, TimeUnit.SECONDS))
            throw IOException("Simulated local control socket closed during blocked write")
        }
        host.geometry(Geometry(480, 640))
        val down = Thread {
            try { host.touch(Touch(Touch.Phase.DOWN, 1000, Tap(10, 20, Geometry(480, 640)))) }
            catch (_: IOException) { }
            finally { downFinished.countDown() }
        }.apply { start() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        lateinit var release: Thread
        val before = System.nanoTime()
        try {
            release = HeldInputRelease.beforeTransportClose { host.close() }
            assertTrue("Cancellation should still be waiting behind the blocked writer", release.isAlive)
        } finally {
            // This is the Session.close ordering: socket close remains reachable without its lock.
            transportClosed.countDown()
        }
        assertTrue("Transport teardown was blocked by cancellation", (System.nanoTime() - before) / 1_000_000 < 1500)
        assertTrue(downFinished.await(2, TimeUnit.SECONDS))
        release.join(2000); down.join(2000)
        assertFalse(release.isAlive); assertFalse(down.isAlive)
    }
}
