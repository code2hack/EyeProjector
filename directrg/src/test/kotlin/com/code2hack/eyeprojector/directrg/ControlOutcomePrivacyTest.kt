package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Production formatter/default transport, with disposable synthetic files and local mTLS only. */
class ControlOutcomePrivacyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun customNullResultDoesNotInventAMissingPeerOrUnavailableCallback() = controlLogs { logs ->
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val client = PairedSessionClient(File("unused-synthetic-directory"), { callbacks.incrementAndGet() },
            { callbacks.incrementAndGet() }, { _, _ -> null })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("source=CUSTOM_TRANSPORT outcome=NULL_RESULT"))
            assertTrue(result.contains("replyAuthentication=UNOBSERVED"))
            assertEquals(0, callbacks.get())
        } finally { closeObserved(client, logs) }
    }

    @Test fun absentPeerKeepsNullBranchWithoutReportingPhoneUnavailable() = controlLogs { logs ->
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val client = PairedSessionClient(temporary.newFolder(), { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("host=null pairingPort=null source=PEER_LOOKUP outcome=NO_REMEMBERED_PEER"))
            assertTrue(result.contains("replyAuthentication=UNOBSERVED"))
            assertEquals(0, callbacks.get())
            assertTrue(logs.none { it.contains("event=DELIVERY_CHECK") })
        } finally { closeObserved(client, logs) }
    }

    @Test fun failedPeerReadIsNotMisreportedAsAbsentPeer() = controlLogs { logs ->
        val directory = temporary.newFolder("private-directory-marker")
        File(directory, "paired-peer.properties").mkdir() // Exact production read fails; no key or identity needed.
        val unavailable = CountDownLatch(1)
        val client = PairedSessionClient(directory, { fail("Unexpected state") }, { unavailable.countDown() })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("host=null pairingPort=null source=PEER_LOOKUP outcome=EXCEPTION"))
            assertTrue(result.contains("FileNotFoundException@PeerStore.pairedPeer:"))
            assertFalse(result.contains("private-"))
            assertFalse(result.contains("paired-peer.properties"))
        } finally { closeObserved(client, logs) }
    }

    @Test fun defaultTransportReportsActualControlEndpointAndAuthenticatedReply() = controlLogs { logs ->
        val phone = PeerStore(temporary.newFolder("phone"))
        val glasses = PeerStore(temporary.newFolder("glasses"))
        val phoneIdentity = phone.ensureIdentity()
        val glassesIdentity = glasses.ensureIdentity()
        val executor = Executors.newSingleThreadExecutor()
        try { ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 5000
            val host = checkNotNull(InetAddress.getLoopbackAddress().hostAddress)
            val endpoint = PeerEndpoint(host, listener.localPort, 27183)
            phone.remember(RememberedPeer(glassesIdentity, endpoint))
            glasses.remember(RememberedPeer(phoneIdentity, endpoint))
            val server = executor.submit {
                repeat(2) {
                    listener.accept().use { raw -> SessionControl.serve(phone.directory, raw) { request ->
                        if (request.operation == SessionControl.Operation.STATUS)
                            SessionState("private-owner-marker", SessionPhase.UNKNOWN, "SESSION_CONTROL_STATE_UNAVAILABLE",
                                AttemptReceipt(request.startEpoch, "private-owner-marker", AttemptDisposition.RETRYABLE))
                        else {
                            assertEquals(SessionControl.Operation.STOP, request.operation)
                            SessionState("private-owner-marker", SessionPhase.INACTIVE, receipt =
                                AttemptReceipt(request.startEpoch, null, AttemptDisposition.CANCELED))
                        }
                    } }
                }
            }
            val unavailable = CountDownLatch(1)
            val client = PairedSessionClient(glasses.directory, { fail("Unexpected state") }, { unavailable.countDown() })
            try {
                client.command(SessionControl.Operation.STATUS, "private-epoch-marker")
                assertTrue(unavailable.await(8, TimeUnit.SECONDS))
                val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
                assertTrue(result.contains("endpointRole=CONTROL host=\"$host\" pairingPort=${listener.localPort} source=PAIRED_CONTROL"))
                assertTrue(result.contains("replyAuthentication=CONFIRMED phase=UNKNOWN receipt=RETRYABLE"))
                assertTrue(result.contains("retryable=true stateUnavailable=true"))
                assertFalse(result.contains("private-"))
                assertFalse(result.contains(phoneIdentity.subjectX500Principal.name))
                assertFalse(result.contains(glassesIdentity.subjectX500Principal.name))
            } finally { closeObserved(client, logs) }
            server.get(8, TimeUnit.SECONDS)
            assertEquals(2, logs.count { it.contains("event=RESULT") })
            assertTrue(logs.any { it.contains("eligibility=FINAL_STOP_NO_UI") && it.contains("replyAuthentication=CONFIRMED") })
        }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun errorFormattingUsesFixedFamiliesAndKnownApplicationFramesOnly() = controlLogs { logs ->
        val failure = object : IOException("private-exception-message") {
            override fun toString(): String = error("Throwable.toString must not be read")
            override val message: String get() = error("Throwable.message must not be read")
        }
        failure.stackTrace = arrayOf(
            StackTraceElement("private-class", "private-method", "private-file", 10),
            StackTraceElement("com.code2hack.eyeprojector.protocol.SessionControl", "exchange", "private-file", 27))
        val eof = java.io.EOFException("private-cause-message")
        eof.stackTrace = arrayOf(StackTraceElement("private-class", "private-method", "private-file", 20))
        failure.initCause(eof); eof.initCause(failure) // Cause cycle is diagnostic input, not a transport retry.
        val unavailable = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), {}, { unavailable.countDown() }, { request, _ ->
            if (request.operation == SessionControl.Operation.STATUS) throw failure
            SessionState("private-owner", SessionPhase.INACTIVE)
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            val result = awaitControlLog(logs) { it.contains("event=RESULT") && it.contains("op=STATUS") }
            assertTrue(result.contains("IOException@SessionControl.exchange:27,EOFException@UNKNOWN_SITE"))
            assertFalse(result.contains("private-"))
            assertFalse(result.contains("MORE_OR_CYCLE")) // The finite cycle was recognized.
        } finally { closeObserved(client, logs) }
    }

    @Test fun selectedHostFormattingEscapesLogSeparatorsWithoutEvaluatingOtherObjects() {
        val method = Class.forName("com.code2hack.eyeprojector.directrg.PairedSessionClientKt")
            .getDeclaredMethod("quotedHost", String::class.java).apply { isAccessible = true }
        assertEquals("\"loop\\\"back\\\\\\u000a\\u0009\"", method.invoke(null, "loop\"back\\\n\t"))
    }

    @Test fun diagnosticExtractionFailureCannotReplaceTheTransportErrorCallback() = controlLogs { logs ->
        val failure = object : IOException() {
            override val cause: Throwable? get() = throw AssertionError("injected diagnostic extraction failure")
        }
        val unavailable = CountDownLatch(1)
        val client = PairedSessionClient(File("unused-synthetic-directory"), { fail("Unexpected state") }, { unavailable.countDown() }, { request, _ ->
            if (request.operation == SessionControl.Operation.STATUS) throw failure
            SessionState("private-owner", SessionPhase.INACTIVE)
        })
        try {
            client.command(SessionControl.Operation.STATUS, "private-epoch")
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            // Formatting is allowed to fail; its failure cannot produce a false successful result.
            assertTrue(logs.none { it.contains("op=STATUS") })
        } finally { closeObserved(client, logs) }
    }
}

private val logTestLock = Any()

internal fun <T> controlLogs(block: (CopyOnWriteArrayList<String>) -> T): T = synchronized(logTestLock) {
    val original = ControlOutcomeLog.sink
    val logs = CopyOnWriteArrayList<String>()
    val group = ThreadGroup("eyeprojector-control-test-${System.nanoTime()}")
    ControlOutcomeLog.sink = { row -> if (Thread.currentThread().threadGroup === group) logs.add(row) }
    val work = java.util.concurrent.FutureTask<T> {
        try { block(logs) } finally {
            // Join this test's actual owned workers independently of whether any log was produced.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                val threads = arrayOfNulls<Thread>(group.activeCount() * 2 + 8)
                group.enumerate(threads)
                val owned = threads.filterNotNull().filter { it !== Thread.currentThread() && it.isAlive }
                if (owned.isEmpty()) break
                owned.forEach { it.join(maxOf(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))) }
                check(System.nanoTime() < until || owned.none { it.isAlive }) { "Owned fixture worker did not finish" }
            } while (true)
        }
    }
    val worker = Thread(group, work, "control-diagnostic-fixture").apply { start() }
    try {
        try { work.get(30, TimeUnit.SECONDS) } catch (failure: java.util.concurrent.ExecutionException) { throw failure.cause ?: failure }
    } finally {
        try { worker.join(1000); check(!worker.isAlive) } finally { ControlOutcomeLog.sink = original }
    }
}

internal fun controlCall(row: String): String = row.substringAfter("call=").substringBefore(' ')

internal fun awaitControlLog(logs: List<String>, predicate: (String) -> Boolean): String {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    do {
        logs.firstOrNull(predicate)?.let { return it }
        Thread.sleep(1)
    } while (System.nanoTime() < until)
    error("Expected classified control record was not observed")
}

internal fun closeObserved(client: PairedSessionClient, logs: List<String>) {
    val before = logs.size
    client.close()
    awaitControlLog(logs) { logs.indexOf(it) >= before && it.contains("event=RESULT") && it.contains("eligibility=FINAL_STOP_NO_UI") }
    // controlLogs joins the actual test-owned group even if this observation/assertion fails.
}
