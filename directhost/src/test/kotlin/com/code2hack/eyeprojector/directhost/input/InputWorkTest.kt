package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class InputWorkTest {
    private data class Operation(val gestureId: Long,val sequence: Long,val original: Any)

    @Test(timeout = 7000) fun blockedWorkerRetainsBothActivationsAndTheirCapturedOriginalContextInOrder() {
        val work = InputWork()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(2)
        val observed = mutableListOf<Operation>()
        val original = Any()
        var current = original
        try {
            work.submit { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val first = Operation(10,0,current)
            val second = Operation(11,1,current)
            for (captured in listOf(first, second)) work.submit {
                observed.add(captured); delivered.countDown()
            }
            current = Any() // A successor appears before either original callback can run.
            release.countDown()
            assertTrue("Both activations must survive pending work", delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(first, second), observed)
            assertEquals(listOf(10L, 11L), observed.map { it.gestureId })
            assertEquals(listOf(0L, 1L), observed.map { it.sequence })
            observed.forEach { assertSame(original, it.original); assertNotSame(current, it.original) }
        } finally { release.countDown(); work.close() }
    }

    @Test(timeout = 7000) fun externalCloseCancelsPendingWorkAndPreservesRunningCallbackCleanup() {
        val work = InputWork()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cleaned = CountDownLatch(1)
        val pendingRan = AtomicBoolean()
        try {
            work.submit {
                entered.countDown()
                try { release.await(4, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { /* External close retains existing interruption semantics. */ }
                finally { cleaned.countDown() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            work.submit { pendingRan.set(true) }
            work.close()
            work.submit { pendingRan.set(true) }
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertFalse(pendingRan.get())
        } finally { release.countDown(); work.close() }
    }

    @Test(timeout = 7000) fun callbackFailureDropsPendingWorkWithoutInterruptingItsOwnCleanupThread() {
        val work = InputWork()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val failed = CountDownLatch(1)
        val observedFailure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val pendingRan = AtomicBoolean()
        val failure = IllegalStateException("original callback failed")
        try {
            work.submit {
                Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
                    observedFailure.set(error); interrupted.set(thread.isInterrupted); failed.countDown()
                }
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                throw failure
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            work.submit { pendingRan.set(true) }
            release.countDown()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertSame(failure, observedFailure.get())
            assertFalse(interrupted.get())
            assertFalse(pendingRan.get())
        } finally { release.countDown(); work.close() }
    }
}
