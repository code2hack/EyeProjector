package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class FunctionWorkTest {
    private fun intent(gesture: Long, sequence: Long) = FunctionIntent(
        FunctionIntentKind.ACTIVATE, FunctionSource.PHONE_HID, gesture, sequence,
        FunctionIntentContext(
            ReceiverUsability("owner", ReceiverIncarnation("original", 1), 2, 3, ReceiverUsabilityMode.PROJECTED),
            HeadPointerState(3, 4, true, true, 50f, 50f, Fit(0, 0, 100, 100), Geometry(100, 100)),
        ),
    )

    @Test(timeout = 7000) fun blockedWorkerRetainsBothActivationsAndTheirCapturedOriginalContextInOrder() {
        val work = FunctionWork()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(2)
        val observed = mutableListOf<CapturedFunctionIntent<Any>>()
        val original = Any()
        var current = original
        try {
            work.submit { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val first = CapturedFunctionIntent(intent(10, 0), current)
            val second = CapturedFunctionIntent(intent(11, 1), current)
            for (captured in listOf(first, second)) work.submit {
                observed.add(captured); delivered.countDown()
            }
            current = Any() // A successor appears before either original callback can run.
            release.countDown()
            assertTrue("Both activations must survive pending work", delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(first, second), observed)
            assertEquals(listOf(10L, 11L), observed.map { it.intent.gestureId })
            assertEquals(listOf(0L, 1L), observed.map { it.intent.sequence })
            observed.forEach { assertSame(original, it.original); assertNotSame(current, it.original) }
        } finally { release.countDown(); work.close() }
    }

    @Test(timeout = 7000) fun externalCloseCancelsPendingWorkAndPreservesRunningCallbackCleanup() {
        val work = FunctionWork()
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
        val work = FunctionWork()
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
