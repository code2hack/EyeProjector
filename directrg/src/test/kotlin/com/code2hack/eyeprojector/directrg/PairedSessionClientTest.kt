package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.AttemptReceipt
import com.code2hack.eyeprojector.protocol.AttemptDisposition
import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

/** Real client executor/cancellation with modeled network admission; TLS is tested in protocol. */
class PairedSessionClientTest {
    @Test fun currentTransportExceptionKeepsUnavailableCallbackAndItsOwnClassification() = controlLogs { logs ->
        val unavailable = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), { fail("Unexpected state") }, { unavailable.countDown() }, { request, _ ->
            if (request.operation == SessionControl.Operation.STATUS) throw IOException("private-request-marker")
            SessionState("retired", SessionPhase.INACTIVE)
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch-marker")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val row = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(row.contains("outcome=EXCEPTION"))
            assertTrue(row.contains("error=IOException@"))
            assertTrue(row.contains("caughtClientError=NONE"))
            assertTrue(row.contains("eligibility=CURRENT"))
            assertFalse(row.contains("private-"))
        } finally { closeObserved(client, logs) }
    }

    @Test fun returnedRetryableAndUnavailableFlagsKeepBothFactsAndCurrentError() = controlLogs { logs ->
        val unavailable = CountDownLatch(1)
        val states = Collections.synchronizedList(mutableListOf<SessionState>())
        val client = PairedSessionClient(File("unused-synthetic-directory"), states::add, { unavailable.countDown() }, { request, _ ->
            SessionState("private-owner-marker", SessionPhase.AVAILABLE, "SESSION_CONTROL_STATE_UNAVAILABLE",
                AttemptReceipt(request.startEpoch, "private-owner-marker", AttemptDisposition.RETRYABLE))
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch-marker")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val row = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(row.contains("outcome=RETURNED_STATE"))
            assertTrue(row.contains("retryable=true stateUnavailable=true"))
            assertTrue(row.contains("phase=AVAILABLE receipt=RETRYABLE"))
            assertTrue(row.contains("replyAuthentication=UNOBSERVED")) // Injected transport is not mTLS evidence.
            assertFalse(row.contains("private-"))
            assertTrue(states.isEmpty())
        } finally { closeObserved(client, logs) }
    }

    @Test fun queuedDeliveryCanBecomeStaleAfterAReportedCurrentResult() = queuedDiagnosticDelivery(close = false)
    @Test fun queuedDeliveryCanBecomeClosedAfterAReportedCurrentResult() = queuedDiagnosticDelivery(close = true)

    @Test fun retryableReceiptAloneKeepsTheUnavailableBranch() = singleUnavailableFlag(retryable = true)
    @Test fun stateUnavailableDetailAloneKeepsTheUnavailableBranch() = singleUnavailableFlag(retryable = false)

    private fun singleUnavailableFlag(retryable: Boolean) = controlLogs { logs ->
        val unavailable = CountDownLatch(1)
        val states = java.util.concurrent.atomic.AtomicInteger()
        val client = PairedSessionClient(File("unused-synthetic-directory"), { states.incrementAndGet() }, { unavailable.countDown() }, { request, _ ->
            SessionState("private-owner", SessionPhase.INACTIVE, if (retryable) "private-detail" else "SESSION_CONTROL_STATE_UNAVAILABLE",
                AttemptReceipt(request.startEpoch, null, if (retryable) AttemptDisposition.RETRYABLE else AttemptDisposition.OBSERVED))
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("retryable=$retryable stateUnavailable=${!retryable}"))
            assertEquals(0, states.get())
        } finally { closeObserved(client, logs) }
    }

    private fun queuedDiagnosticDelivery(close: Boolean) = controlLogs { logs ->
        val delivery = LinkedBlockingQueue<() -> Unit>()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val client = PairedSessionClient(File("unused-synthetic-directory"), { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() },
            { _, _ -> SessionState("private-owner", SessionPhase.INACTIVE) }, { delivery.put(it) })
        var closed = false
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            val action = delivery.poll(2, TimeUnit.SECONDS) ?: error("Delivery was not queued")
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("eligibility=CURRENT"))
            assertTrue(logs.none { it.contains("event=DELIVERY_CHECK") })
            if (close) { closeObserved(client, logs); closed = true } else client.disconnect(null)
            action()
            val checked = awaitControlLog(logs) { it.contains("event=DELIVERY_CHECK") && it.contains("op=STATUS") }
            assertTrue(checked.contains("eligibility=${if (close) "CLOSED" else "STALE"}"))
            assertEquals(controlCall(result), controlCall(checked))
            assertEquals(0, callbacks.get())
        } finally { if (!closed) closeObserved(client, logs) }
    }

    @Test fun synchronousCallbackFailureIsNotMisreportedAsTransportFailure() = controlLogs { logs ->
        val unavailable = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), { throw IllegalStateException("private-callback-marker") },
            { unavailable.countDown() }, { _, _ -> SessionState("private-owner", SessionPhase.INACTIVE) })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("outcome=RETURNED_STATE"))
            assertTrue(result.contains("error=NONE caughtClientError=IllegalStateException@"))
            assertFalse(result.contains("private-"))
        } finally { closeObserved(client, logs) }
    }

    @Test fun synchronousDeliveryFailureKeepsTheOriginalUnavailableFallback() = controlLogs { logs ->
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val unavailable = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), { fail("State delivery should fail first") },
            { unavailable.countDown() }, { _, _ -> SessionState("private-owner", SessionPhase.INACTIVE) }, { action ->
                if (attempts.getAndIncrement() == 0) throw IllegalStateException("private-scheduler-marker")
                action()
            })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("error=NONE caughtClientError=IllegalStateException@"))
            assertTrue(result.contains("outcome=RETURNED_STATE"))
            assertEquals(2, attempts.get())
        } finally { closeObserved(client, logs) }
    }

    @Test fun attachmentSuppressionReportsFreshEligibilityAfterTheDeliveryCheck() = controlLogs { logs ->
        val capture = ControlOutcomeLog.sink
        val canceled = java.util.concurrent.atomic.AtomicBoolean()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        lateinit var client: PairedSessionClient
        client = PairedSessionClient(File("unused-synthetic-directory"), { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() }, { request, _ ->
            if (request.operation == SessionControl.Operation.STATUS)
                SessionState("private-owner", SessionPhase.AVAILABLE, receipt = AttemptReceipt(request.startEpoch, "private-owner", AttemptDisposition.OBSERVED))
            else SessionState("private-owner", SessionPhase.INACTIVE)
        })
        ControlOutcomeLog.sink = { row ->
            capture(row)
            if (row.contains("event=DELIVERY_CHECK") && row.contains("op=STATUS") && canceled.compareAndSet(false, true)) client.disconnect(null)
        }
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            val suppressed = awaitControlLog(logs) { it.contains("event=ATTACHMENT_SUPPRESSED") }
            assertTrue(suppressed.contains("eligibility=STALE"))
            assertTrue(logs.any { it.contains("event=DELIVERY_CHECK") && it.contains("eligibility=CURRENT") })
            assertEquals(0, callbacks.get())
        } finally { closeObserved(client, logs) }
    }

    @Test fun throwingTelemetryCannotReplaceTheOriginalTransportErrorCallback() = controlLogs { _ ->
        val original = ControlOutcomeLog.sink
        val unavailable = CountDownLatch(1); val stopped = CountDownLatch(1)
        val stopThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        ControlOutcomeLog.sink = { row -> original(row); throw AssertionError("injected telemetry failure") }
        val client = PairedSessionClient(File("unused-synthetic-directory"), { fail("Unexpected state") }, { unavailable.countDown() }, { request, _ ->
            if (request.operation == SessionControl.Operation.STATUS) throw IOException("private-transport-marker")
            stopThread.set(Thread.currentThread()); stopped.countDown()
            SessionState("private-owner", SessionPhase.INACTIVE)
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
        } finally {
            try {
                client.close()
                assertTrue(stopped.await(2, TimeUnit.SECONDS))
                stopThread.get().join(2000)
                assertFalse(stopThread.get().isAlive)
            } finally { ControlOutcomeLog.sink = original }
        }
    }

    @Test fun throwingTelemetryCannotChangeCallbackOrSkipIndependentFinalStops() = controlLogs { _ ->
        val original = ControlOutcomeLog.sink
        val state = CountDownLatch(1); val stopped = CountDownLatch(2)
        val targets = Collections.synchronizedList(mutableListOf<String>())
        val stopThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        ControlOutcomeLog.sink = { row -> original(row); throw AssertionError("injected telemetry failure") }
        val client = PairedSessionClient(File("unused-synthetic-directory"), { state.countDown() }, { fail("Unexpected error") }, { request, _ ->
            if (request.operation == SessionControl.Operation.STOP) {
                stopThread.set(Thread.currentThread())
                targets.add(request.epoch.ifEmpty { request.startEpoch }); stopped.countDown()
                if (targets.size == 1) throw IOException("first independent stop failed")
            }
            SessionState("private-owner", SessionPhase.INACTIVE)
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(state.await(2, TimeUnit.SECONDS))
            client.close("private-attached-owner")
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            stopThread.get().join(2000)
            assertFalse(stopThread.get().isAlive)
            assertEquals(2, targets.size)
            assertTrue(targets.contains("private-attached-owner"))
        } finally { try { client.close() } finally { ControlOutcomeLog.sink = original } }
    }

    @Test fun failedCancellationRetentionReportsCurrentErrorWithoutDeliveringStopState() = controlLogs { logs ->
        val error = CountDownLatch(1)
        val states = Collections.synchronizedList(mutableListOf<SessionState>())
        val client = PairedSessionClient(File("unused-synthetic-directory"), states::add, { error.countDown() }, { request, _ ->
            assertEquals(SessionControl.Operation.STOP, request.operation)
            SessionState("still-owned", SessionPhase.AVAILABLE, "SESSION_CONTROL_STATE_UNAVAILABLE",
                request.startEpoch.takeIf { it.isNotEmpty() }?.let { AttemptReceipt(it, "still-owned", AttemptDisposition.RETRYABLE) })
        })
        try {
            client.disconnect(null)
            assertTrue(error.await(2, TimeUnit.SECONDS))
            assertTrue(states.isEmpty())
        } finally { closeObserved(client, logs) }
    }

    @Test fun disconnectCancelsBothStaleAttachedEpochAndAnAcceptedStartWithoutAReply() = controlLogs { logs ->
        exercise(close = false, logs)
    }

    @Test fun pauseClosesTheInflightSocketAndCancelsBothRelevantEpochs() = controlLogs { logs ->
        exercise(close = true, logs)
    }

    @Test fun forgetCompletionRunsAfterTheBoundedStopAttemptBeforeTrustIsRemoved() = controlLogs { logs ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val complete = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), {}, {}, { request, _ ->
            check(request.operation == SessionControl.Operation.STOP)
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
            SessionState("retired", SessionPhase.INACTIVE)
        })
        try {
            client.disconnect("ending") { complete.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(complete.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(complete.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); closeObserved(client, logs) }
    }

    private fun exercise(close: Boolean, logs: List<String>) {
        val accepted = CountDownLatch(1); val stopped = CountDownLatch(2)
        val targets = Collections.synchronizedList(mutableListOf<String>())
        val reported = Collections.synchronizedList(mutableListOf<SessionState>())
        var proposed = ""
        val client = PairedSessionClient(File("unused-synthetic-directory"), reported::add, {}, { request, opened ->
            when (request.operation) {
                SessionControl.Operation.STATUS -> SessionState("observed-inactive", SessionPhase.INACTIVE, receipt = AttemptReceipt(request.startEpoch, null, AttemptDisposition.OBSERVED))
                SessionControl.Operation.START -> {
                    proposed = request.startEpoch
                    Socket().use { socket ->
                        opened(socket); accepted.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (!socket.isClosed && System.nanoTime() < deadline) Thread.sleep(1)
                        check(socket.isClosed)
                        throw IOException("Canceled reply after simulated Start admission")
                    }
                }
                SessionControl.Operation.STOP -> {
                    if (request.startEpoch.isNotEmpty()) assertEquals("observed-inactive", request.epoch)
                    targets.add(request.startEpoch.ifEmpty { request.epoch }); stopped.countDown()
                    SessionState("retired", SessionPhase.INACTIVE)
                }
                else -> error("Unexpected command")
            }
        })
        try {
            client.connect()
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            if (close) client.close("stale-attached") else {
                val suppressed = client.disconnect("stale-attached")
                assertEquals(setOf("stale-attached", proposed), suppressed.toSet())
            }
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(setOf("stale-attached", proposed), targets.toSet())
            assertTrue(reported.none { it.phase.active })
        } finally { if (close) client.close() else closeObserved(client, logs) }
    }
}
