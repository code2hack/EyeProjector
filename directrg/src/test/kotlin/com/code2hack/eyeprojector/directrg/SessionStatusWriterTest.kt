package com.code2hack.eyeprojector.directrg

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStatusWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun blockedPersistenceDoesNotBlockProducerAndTerminalWinsOverHeartbeat() {
        val disk = Executors.newSingleThreadExecutor()
        val producer = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = Collections.synchronizedList(mutableListOf<String>())
        val writer = SessionStatusWriter(temporary.newFolder().resolve("status.json"), {}, disk) { _, value ->
            if (value == "initial") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            written.add(value)
        }
        try {
            writer.submit("initial")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            producer.submit {
                writer.submit("intermediate")
                writer.submit("DECODE_FAILED", terminal = true)
                writer.submit("late heartbeat")
            }.get(1, TimeUnit.SECONDS)
            assertTrue(written.isEmpty())
            release.countDown()
            disk.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("initial", "DECODE_FAILED"), written.toList())
        } finally { release.countDown(); producer.shutdownNow(); disk.shutdownNow() }
    }

    @Test fun newerSessionOwnsPathEvenWhenPredecessorHasBlockedWriteAndQueuedTerminal() {
        val disk = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val file = temporary.newFolder().resolve("status.json")
        val written = Collections.synchronizedList(mutableListOf<String>())
        val old = SessionStatusWriter(file, {}, disk) { _, value ->
            if (value == "old active") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            written.add(value)
        }
        try {
            old.submit("old active")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            old.submit("old terminal", terminal = true)
            val next = SessionStatusWriter(File(file.parentFile, "./status.json"), {}, disk) { _, value -> written.add(value) }
            next.submit("new STREAMING")
            old.submit("stale heartbeat")
            release.countDown()
            disk.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("old active", "new STREAMING"), written.toList())
        } finally { release.countDown(); disk.shutdownNow() }
    }

    @Test fun persistenceFailureIsReportedAndDoesNotPreventPendingTerminal() {
        val actions = ArrayDeque<Runnable>()
        val executor = Executor { actions.addLast(it) }
        val failure = IOException("modeled disk failure")
        val failures = mutableListOf<Throwable>()
        val written = mutableListOf<String>()
        lateinit var writer: SessionStatusWriter
        writer = SessionStatusWriter(temporary.newFolder().resolve("status.json"), {
            failures.add(it)
            writer.submit("terminal", terminal = true)
        }, executor) { _, value ->
            if (value == "first") throw failure
            written.add(value)
        }
        writer.submit("first")
        assertEquals(1, actions.size)
        actions.removeFirst().run()
        assertEquals(listOf(failure), failures)
        assertEquals(listOf("terminal"), written)
        assertTrue(actions.isEmpty())
    }

    @Test fun actualAtomicFileWriteRunsOnExecutorAndRetainsTerminalPayload() {
        val actions = ArrayDeque<Runnable>()
        val file = temporary.newFolder().resolve("status.json")
        val failures = mutableListOf<Throwable>()
        val writer = SessionStatusWriter(file, failures::add, Executor { actions.addLast(it) })
        writer.submit("{\"state\":\"STREAMING\"}")
        writer.submit("{\"state\":\"STOPPED\"}", terminal = true)
        assertFalse(file.exists())
        actions.removeFirst().run()
        assertEquals("{\"state\":\"STOPPED\"}", file.readText())
        assertTrue(failures.isEmpty())
    }
}
