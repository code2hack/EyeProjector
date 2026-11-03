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
    @Test fun periodicPollBeforeQueuedDirectStopDoesNotRetireItsOriginalSource() = controlLogs { logs ->
        val directory = java.nio.file.Files.createTempDirectory("direct-periodic-stop-").toFile()
        val feature = ReceiverActivationStore(directory.resolve("feature")); feature.startIncarnation()
        val old = ReceiverLaunchScope("old-scope", "old-T", 7)
        feature.launch(old, "old-admission"); feature.cancel(old, null)
        feature.accept(com.code2hack.eyeprojector.protocol.ActivationReply(trigger =
            com.code2hack.eyeprojector.protocol.TriggerReceipt(old.trigger, null,
                com.code2hack.eyeprojector.protocol.TriggerDisposition.CANCELED)))
        val initial = CountDownLatch(1); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<SessionControl.Request>())
        val client = PairedSessionClient(directory, { initial.countDown() }, {}, { request, opened ->
            requests.add(request)
            if (request.operation == SessionControl.Operation.RENDERED) {
                opened(Socket()); entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }
            SessionState("S1", SessionPhase.AVAILABLE,
                receipt = AttemptReceipt(request.startEpoch, "S1", AttemptDisposition.OBSERVED))
        }, feature = feature)
        try {
            client.start(); assertTrue(initial.await(2, TimeUnit.SECONDS))
            client.command(SessionControl.Operation.RENDERED, "S1")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The actual one-second periodic task is due before Disconnect queues its Stop.
            Thread.sleep(1100)
            client.disconnect("S1", scope = old, activationId = null)
            release.countDown()
            val executor = client.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(client) as java.util.concurrent.ScheduledExecutorService
            executor.submit {}.get(2, TimeUnit.SECONDS)
            val sent = requests.toList()
            val stops = sent.filter { it.operation == SessionControl.Operation.STOP }
            assertEquals("passive polling must not discard the captured S1 Stop", listOf("S1"), stops.map { it.epoch })
            val polls = sent.filter { it.operation == SessionControl.Operation.STATUS }
            assertTrue(polls.size >= 2)
            assertNotEquals(polls[0].startEpoch, polls[1].startEpoch)
            assertTrue(sent.indexOf(polls[1]) < sent.indexOf(stops.single()))
            assertTrue(feature.state.triggers.getValue(old.trigger).acknowledged)
            assertNull(feature.currentScope())
        } finally { release.countDown(); closeObserved(client, logs); directory.deleteRecursively() }
    }

    @Test fun periodicPollStillRetiresOldStopForSuccessorEpochNewLaunchOrClose() = controlLogs { logs ->
        for (replacement in listOf("epoch", "launch", "close")) {
            val directory = java.nio.file.Files.createTempDirectory("direct-periodic-replacement-").toFile()
            val feature = ReceiverActivationStore(directory.resolve("feature")); feature.startIncarnation()
            val old = ReceiverLaunchScope("old-scope", "old-T", 7)
            val next = ReceiverLaunchScope("new-scope", "new-T", 8)
            feature.launch(old, "old-admission"); feature.cancel(old, null)
            feature.accept(com.code2hack.eyeprojector.protocol.ActivationReply(trigger =
                com.code2hack.eyeprojector.protocol.TriggerReceipt(old.trigger, null,
                    com.code2hack.eyeprojector.protocol.TriggerDisposition.CANCELED)))
            val initial = CountDownLatch(1); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val changed = CountDownLatch(1); val fresh = CountDownLatch(1)
            val polls = java.util.concurrent.atomic.AtomicInteger()
            val queuedStops = Collections.synchronizedList(mutableListOf<SessionControl.Request>())
            lateinit var client: PairedSessionClient
            client = PairedSessionClient(directory, { state ->
                initial.countDown(); if (state.epoch == "S2") fresh.countDown()
            }, {}, { request, opened ->
                if (request.operation == SessionControl.Operation.STOP && Thread.currentThread().name != "eyeprojector-session-stop")
                    queuedStops.add(request)
                if (request.operation == SessionControl.Operation.RENDERED) {
                    opened(Socket()); entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
                val poll = if (request.operation == SessionControl.Operation.STATUS) polls.incrementAndGet() else 0
                if (poll == 2) {
                    if (replacement == "launch") client.launch(next)
                    if (replacement == "close") client.close("S1")
                    changed.countDown()
                }
                val epoch = if (replacement == "epoch" && poll >= 2 || request.intent?.triggerId == next.trigger) "S2" else "S1"
                SessionState(epoch, SessionPhase.AVAILABLE,
                    receipt = AttemptReceipt(request.startEpoch, epoch, AttemptDisposition.OBSERVED))
            }, feature = feature)
            try {
                client.start(); assertTrue(initial.await(2, TimeUnit.SECONDS))
                client.command(SessionControl.Operation.RENDERED, "S1")
                assertTrue(entered.await(2, TimeUnit.SECONDS)); Thread.sleep(1100)
                client.disconnect("S1", scope = old, activationId = null); release.countDown()
                assertTrue(changed.await(2, TimeUnit.SECONDS))
                if (replacement == "close") {
                    awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("eligibility=FINAL_STOP_NO_UI") }
                } else {
                    assertTrue(fresh.await(2, TimeUnit.SECONDS))
                    val executor = client.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(client) as java.util.concurrent.ScheduledExecutorService
                    executor.submit {}.get(2, TimeUnit.SECONDS)
                }
                assertTrue("$replacement must retire queued original Stop", queuedStops.isEmpty())
            } finally {
                release.countDown()
                if (replacement != "close") closeObserved(client, logs) else client.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun queuedDirectStopAndOldReplyCannotBorrowReplacementAdmission() = controlLogs { logs ->
        val directory = java.nio.file.Files.createTempDirectory("direct-disconnect-client-").toFile()
        val feature = ReceiverActivationStore(directory.resolve("feature"))
        feature.startIncarnation()
        val first = ReceiverLaunchScope("S1-scope", "T1", 7)
        val second = ReceiverLaunchScope("S2-scope", "T2", 8)
        val delivery = LinkedBlockingQueue<() -> Unit>()
        val states = mutableListOf<SessionState>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        val stops = Collections.synchronizedList(mutableListOf<SessionControl.Request>())
        val client = PairedSessionClient(directory, states::add, {}, { request, opened ->
            val cancelTrigger = request.cancelTrigger
            when {
                request.operation == SessionControl.Operation.STOP -> {
                    stops.add(request); SessionState("retired", SessionPhase.INACTIVE)
                }
                cancelTrigger != null -> SessionState("retired", SessionPhase.INACTIVE,
                    activation = com.code2hack.eyeprojector.protocol.ActivationReply(trigger =
                        com.code2hack.eyeprojector.protocol.TriggerReceipt(cancelTrigger.triggerId, null,
                            com.code2hack.eyeprojector.protocol.TriggerDisposition.CANCELED)))
                request.operation == SessionControl.Operation.RENDERED -> {
                    opened(Socket()); entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                    SessionState("S1", SessionPhase.AVAILABLE,
                        receipt = AttemptReceipt(request.startEpoch, "S1", AttemptDisposition.OBSERVED))
                }
                else -> {
                    val epoch = if (request.intent?.triggerId == second.trigger) "S2" else "S1"
                    if (epoch == "S2") fresh.countDown()
                    SessionState(epoch, SessionPhase.AVAILABLE,
                        receipt = AttemptReceipt(request.startEpoch, epoch, AttemptDisposition.OBSERVED))
                }
            }
        }, { delivery.put(it) }, feature)
        try {
            client.launch(first)
            val oldDelivery = delivery.poll(2, TimeUnit.SECONDS) ?: error("Original reply not queued")
            client.command(SessionControl.Operation.RENDERED, "S1")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.disconnect("S1", scope = first, activationId = null)
            client.launch(second)
            release.countDown(); assertTrue(fresh.await(2, TimeUnit.SECONDS))
            oldDelivery()
            val nextDelivery = delivery.poll(2, TimeUnit.SECONDS) ?: error("Replacement reply not queued")
            nextDelivery()
            assertEquals(listOf("S2"), states.map { it.epoch })
            // The queued old Stop became obsolete before opening, and never resolves S2.
            assertTrue(stops.isEmpty())
            assertEquals(second, feature.currentScope())
        } finally { release.countDown(); closeObserved(client, logs); directory.deleteRecursively() }
    }

    @Test fun oldTriggerCancellationKeepsObservedDirectSuccessorAndItsQueuedDelivery() = controlLogs { logs ->
        val directory = java.nio.file.Files.createTempDirectory("direct-trigger-client-").toFile()
        val feature = ReceiverActivationStore(directory.resolve("feature")); feature.startIncarnation()
        val old = ReceiverLaunchScope("old-scope", "old-T", 7)
        feature.launch(old, "old-admission")
        val delivery = LinkedBlockingQueue<() -> Unit>()
        val states = mutableListOf<SessionState>()
        val requests = Collections.synchronizedList(mutableListOf<SessionControl.Request>())
        val settled = CountDownLatch(1)
        val client = PairedSessionClient(directory, states::add, {}, { request, _ ->
            requests.add(request)
            if (request.cancelTrigger != null) SessionState("S2", SessionPhase.AVAILABLE,
                activation = com.code2hack.eyeprojector.protocol.ActivationReply(trigger =
                    com.code2hack.eyeprojector.protocol.TriggerReceipt(old.trigger, null,
                        com.code2hack.eyeprojector.protocol.TriggerDisposition.CANCELED)))
            else SessionState("S2", SessionPhase.AVAILABLE,
                receipt = AttemptReceipt(request.startEpoch, "S2", AttemptDisposition.OBSERVED))
        }, { delivery.put(it) }, feature)
        try {
            client.command(SessionControl.Operation.STATUS, "")
            val successor = delivery.poll(2, TimeUnit.SECONDS) ?: error("Successor delivery missing")
            client.disconnect(null, scope = old, activationId = null, complete = { settled.countDown() })
            assertTrue(settled.await(2, TimeUnit.SECONDS))
            successor()
            assertEquals(listOf("S2"), states.map { it.epoch })
            assertTrue(requests.none { it.operation == SessionControl.Operation.STOP })
        } finally { closeObserved(client, logs); directory.deleteRecursively() }
    }

    @Test fun renderedAcknowledgmentsCoalesceWhileOriginalTransportIsInflight() = controlLogs { logs ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val client = PairedSessionClient(File("unused-synthetic-directory"), {}, {}, { request, _ ->
            when (request.operation) {
                SessionControl.Operation.RENDERED -> {
                    writes.incrementAndGet(); entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
                SessionControl.Operation.STATUS -> drained.countDown()
                else -> Unit
            }
            SessionState("owner", SessionPhase.INACTIVE)
        })
        try {
            client.command(SessionControl.Operation.RENDERED, "owner")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { client.command(SessionControl.Operation.RENDERED, "owner") }
            client.command(SessionControl.Operation.STATUS, "")
            release.countDown()
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
        } finally { release.countDown(); closeObserved(client, logs) }
    }

    @Test fun terminalFailureCancelsOneCoalescedOriginalAcknowledgmentAndFreshStatusProgresses() = controlLogs { logs ->
        val entered = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<SessionControl.Request>())
        val states = Collections.synchronizedList(mutableListOf<SessionState>())
        val unavailable = java.util.concurrent.atomic.AtomicInteger()
        val client = PairedSessionClient(File("unused-synthetic-directory"), { state ->
            states.add(state); if (state.epoch == "fresh-owner") fresh.countDown()
        }, { unavailable.incrementAndGet() }, { request, opened ->
            if (request.operation != SessionControl.Operation.STOP) requests.add(request)
            when (request.operation) {
                SessionControl.Operation.RENDERED -> {
                    opened(object : Socket() { override fun close() { socketClosed.countDown() } })
                    entered.countDown()
                    check(socketClosed.await(2, TimeUnit.SECONDS))
                    // A transport can still return after cancellation. Its original reply is stale.
                    SessionState("old-owner", SessionPhase.AVAILABLE,
                        receipt = AttemptReceipt(request.startEpoch, "old-owner", AttemptDisposition.OBSERVED))
                }
                SessionControl.Operation.FAILED -> {
                    failed.countDown()
                    SessionState("released-owner", SessionPhase.INACTIVE)
                }
                SessionControl.Operation.STATUS -> SessionState("fresh-owner", SessionPhase.AVAILABLE,
                    receipt = AttemptReceipt(request.startEpoch, "fresh-owner", AttemptDisposition.OBSERVED))
                else -> SessionState("released-owner", SessionPhase.INACTIVE)
            }
        })
        try {
            client.command(SessionControl.Operation.RENDERED, "old-owner")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { client.command(SessionControl.Operation.RENDERED, "old-owner") }
            client.command(SessionControl.Operation.FAILED, "old-owner")
            client.command(SessionControl.Operation.RENDERED, "old-owner")
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            client.command(SessionControl.Operation.STATUS, "")
            assertTrue(fresh.await(2, TimeUnit.SECONDS))
            val sent = requests.toList()
            assertEquals(listOf(SessionControl.Operation.RENDERED, SessionControl.Operation.FAILED, SessionControl.Operation.STATUS), sent.map { it.operation })
            assertTrue(sent.first().startEpoch.isNotEmpty())
            assertEquals(sent.first().startEpoch, sent[1].startEpoch)
            assertFalse(states.any { it.epoch == "old-owner" })
            assertEquals(0, unavailable.get())
        } finally { socketClosed.countDown(); closeObserved(client, logs) }
    }

    @Test fun originalRenderedAndStatusDeliveriesCannotReviveAnOwnerAfterFailedWasEnqueued() = controlLogs { logs ->
        val delivery = LinkedBlockingQueue<() -> Unit>()
        val states = Collections.synchronizedList(mutableListOf<SessionState>())
        val failed = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), states::add, { fail("Unexpected unavailable callback") },
            { request, _ ->
                if (request.operation == SessionControl.Operation.FAILED) {
                    failed.countDown(); SessionState("released-owner", SessionPhase.INACTIVE)
                } else SessionState("old-owner", SessionPhase.AVAILABLE,
                    receipt = AttemptReceipt(request.startEpoch, "old-owner", AttemptDisposition.OBSERVED))
            }, { delivery.put(it) })
        try {
            client.command(SessionControl.Operation.RENDERED, "old-owner")
            val rendered = delivery.poll(2, TimeUnit.SECONDS) ?: error("Rendered delivery missing")
            client.command(SessionControl.Operation.STATUS, "")
            val status = delivery.poll(2, TimeUnit.SECONDS) ?: error("Status delivery missing")
            client.command(SessionControl.Operation.FAILED, "old-owner")
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            rendered(); status()
            assertTrue(states.isEmpty())
            assertTrue(logs.any { it.contains("event=DELIVERY_CHECK") && it.contains("op=RENDERED") && it.contains("eligibility=STALE") })
            assertTrue(logs.any { it.contains("event=ATTACHMENT_SUPPRESSED") && it.contains("op=STATUS") })
        } finally { closeObserved(client, logs) }
    }

    @Test fun failedOwnerRetirementLetsExistingPollingObtainAFreshAdmission() = controlLogs { logs ->
        val attached = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        val failed = java.util.concurrent.atomic.AtomicBoolean()
        val oldAdmission = java.util.concurrent.atomic.AtomicReference<String>()
        val newAdmission = java.util.concurrent.atomic.AtomicReference<String>()
        val client = PairedSessionClient(File("unused-synthetic-directory"), { state ->
            when (state.epoch) { "old-owner" -> attached.countDown(); "fresh-owner" -> fresh.countDown() }
        }, {}, { request, _ ->
            if (request.operation == SessionControl.Operation.FAILED) failed.set(true)
            if (request.operation == SessionControl.Operation.STOP) SessionState("released", SessionPhase.INACTIVE)
            else if (!failed.get()) {
                oldAdmission.compareAndSet(null, request.startEpoch)
                SessionState("old-owner", SessionPhase.AVAILABLE,
                    receipt = AttemptReceipt(request.startEpoch, "old-owner", AttemptDisposition.OBSERVED))
            } else if (request.startEpoch == oldAdmission.get())
                SessionState("released", SessionPhase.INACTIVE,
                    receipt = AttemptReceipt(request.startEpoch, "old-owner", AttemptDisposition.RETIRED))
            else {
                newAdmission.set(request.startEpoch)
                SessionState("fresh-owner", SessionPhase.AVAILABLE,
                    receipt = AttemptReceipt(request.startEpoch, "fresh-owner", AttemptDisposition.OBSERVED))
            }
        })
        try {
            client.start()
            assertTrue(attached.await(2, TimeUnit.SECONDS))
            client.command(SessionControl.Operation.FAILED, "old-owner")
            assertTrue(fresh.await(4, TimeUnit.SECONDS))
            assertTrue(newAdmission.get().isNotEmpty())
            assertNotEquals(oldAdmission.get(), newAdmission.get())
        } finally { closeObserved(client, logs) }
    }

    @Test fun queuedAcknowledgmentDoesNotBorrowAdmissionFromAReconnect() = controlLogs { logs ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val oldAdmission = java.util.concurrent.atomic.AtomicReference<String>()
        val freshAdmission = java.util.concurrent.atomic.AtomicReference<String>()
        val acknowledgments = Collections.synchronizedList(mutableListOf<SessionControl.Request>())
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val client = PairedSessionClient(File("unused-synthetic-directory"), { state ->
            if (state.epoch == "fresh-owner") fresh.countDown()
        }, {}, { request, _ ->
            when (request.operation) {
                SessionControl.Operation.STATUS -> if (first.compareAndSet(true, false)) {
                    oldAdmission.set(request.startEpoch); entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    SessionState("old-owner", SessionPhase.AVAILABLE,
                        receipt = AttemptReceipt(request.startEpoch, "old-owner", AttemptDisposition.OBSERVED))
                } else {
                    freshAdmission.set(request.startEpoch)
                    SessionState("fresh-owner", SessionPhase.AVAILABLE,
                        receipt = AttemptReceipt(request.startEpoch, "fresh-owner", AttemptDisposition.OBSERVED))
                }
                SessionControl.Operation.RENDERED -> {
                    acknowledgments.add(request); rendered.countDown()
                    SessionState(request.epoch, SessionPhase.STREAMING,
                        receipt = AttemptReceipt(request.startEpoch, request.epoch, AttemptDisposition.OBSERVED))
                }
                SessionControl.Operation.START -> SessionState("fresh-owner", SessionPhase.AVAILABLE,
                    receipt = AttemptReceipt(request.startEpoch, "fresh-owner", AttemptDisposition.OBSERVED))
                else -> SessionState("released-owner", SessionPhase.INACTIVE,
                    receipt = AttemptReceipt(request.startEpoch, null, AttemptDisposition.CANCELED))
            }
        })
        try {
            client.command(SessionControl.Operation.STATUS, "")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.command(SessionControl.Operation.RENDERED, "old-owner")
            client.disconnect("old-owner")
            client.connect()
            release.countDown()
            assertTrue(fresh.await(2, TimeUnit.SECONDS))
            client.command(SessionControl.Operation.RENDERED, "fresh-owner")
            assertTrue(rendered.await(2, TimeUnit.SECONDS))
            assertNotEquals(oldAdmission.get(), freshAdmission.get())
            assertEquals(1, acknowledgments.size)
            assertEquals("fresh-owner", acknowledgments.single().epoch)
            assertEquals(freshAdmission.get(), acknowledgments.single().startEpoch)
        } finally { release.countDown(); closeObserved(client, logs) }
    }

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
            client.disconnect("ending", complete = { complete.countDown() })
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
