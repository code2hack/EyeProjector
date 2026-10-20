package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OwnedReleaseTest {
    @Test fun concurrentCloseCannotReturnBeforeActualResourcesRelease() {
        val release = OwnedRelease()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val done = CountDownLatch(2)
        val called = CountDownLatch(1); val released = AtomicInteger()
        Thread {
            release.close { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); released.incrementAndGet() }
            done.countDown()
        }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread {
            called.countDown()
            release.close { fail("second caller must not repeat cleanup") }
            done.countDown()
        }.start()
        assertTrue(called.await(2, TimeUnit.SECONDS))
        assertFalse(done.await(50, TimeUnit.MILLISECONDS))
        finish.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, released.get())
    }

    @Test fun failedReleaseRemainsRetryable() {
        val release = OwnedRelease()
        assertThrows(IllegalStateException::class.java) { release.close { error("synthetic close failure") } }
        var completed = false
        release.close { completed = true }
        assertTrue(completed)
    }
}
