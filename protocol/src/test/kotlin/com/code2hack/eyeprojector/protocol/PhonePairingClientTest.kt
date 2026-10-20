package com.code2hack.eyeprojector.protocol

import java.io.File
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PhonePairingClientTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }
    private class ProbeSocket : Socket() {
        var connected = false
        @Volatile var closedByCaller = false
        override fun connect(endpoint: SocketAddress, timeout: Int) { connected = true }
        override fun close() { closedByCaller = true }
    }
    private fun store() = PeerStore(File("build/tmp/phone-attempt-${System.nanoTime()}"))
    private fun peer(): RememberedPeer = store().run {
        ensureIdentity(); RememberedPeer(identityCertificate(), PeerEndpoint("127.0.0.1", 28002))
    }
    private fun discovered(peer: RememberedPeer) = DiscoveredPeer(peer.identityId, PeerRole.GLASSES, peer.endpoint)

    @Test fun canceledQueuedRequestCannotEnterExchangeAndFreshRequestStillWorks() {
        val local = store()
        val peer = peer()
        val queue = Queue()
        var sockets = 0
        val completed = mutableListOf<Boolean>()
        val client = PhonePairingClient(local, queue, {}, { sockets++; ProbeSocket() }) { _, _, _, _, revision ->
            local.completePairing(revision, peer, "127.0.0.1")
        }
        try {
            val old = client.pair("0123", discovered(peer), 28001, completed::add)!!
            assertTrue(client.cancel(old))
            val fresh = client.pair("0123", discovered(peer), 28001, completed::add)!!
            assertFalse(client.cancel(old)) // An old prompt cannot cancel the fresh request.
            queue.runNext()
            assertEquals(0, sockets)
            assertTrue(client.pairing)
            assertNull(local.pairedPeer())
            assertTrue(completed.isEmpty())
            queue.runNext()
            assertEquals(peer.identityId, local.pairedPeer()!!.identityId)
            assertEquals(listOf(true), completed)
            assertFalse(client.cancel(fresh)) // Normal success dismissal keeps the completed pairing.
        } finally { client.close() }
    }

    @Test fun cancelDuringExchangeClosesOnlyOldSocketAndPreventsItsLateCommitAndCallback() {
        val local = store()
        val peer = peer()
        val queue = Queue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sockets = mutableListOf<ProbeSocket>()
        val completed = mutableListOf<String>()
        var exchanges = 0
        val client = PhonePairingClient(local, queue, {}, { ProbeSocket().also(sockets::add) }) { _, _, _, _, revision ->
            if (exchanges++ == 0) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // Even an exchange that has finished I/O must retain its original revision.
            }
            local.completePairing(revision, peer, "127.0.0.1")
        }
        val old = client.pair("0123", discovered(peer), 28001) { completed.add("old:$it") }!!
        val worker = Thread(queue.tasks.removeFirst())
        try {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(client.cancel(old))
            assertTrue(sockets.first().closedByCaller)
            client.pair("0123", discovered(peer), 28001) { completed.add("fresh:$it") }
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertNull(local.pairedPeer())
            assertFalse(File(local.directory, "endpoint.properties").exists())
            assertTrue(completed.isEmpty())
            assertTrue(client.pairing)
            queue.runNext()
            assertEquals(listOf("fresh:true"), completed)
            assertEquals(peer.identityId, local.pairedPeer()!!.identityId)
        } finally { release.countDown(); client.close(); worker.join(5000) }
    }

    @Test fun cancelPreservesLocalIdentityAndPreviouslyRememberedPeer() {
        val local = store()
        local.ensureIdentity()
        val identity = local.identityId()
        val previous = peer()
        local.remember(previous)
        val queue = Queue()
        val client = PhonePairingClient(local, queue, {})
        try {
            val attempt = client.pair("0123", discovered(peer()), 28001) { fail("Canceled result must not reach its prompt") }!!
            client.cancel(attempt)
            queue.runNext()
            assertEquals(identity, local.identityId())
            assertEquals(previous.identityId, local.pairedPeer()!!.identityId)
        } finally { client.close() }
    }

    @Test fun closeInvalidatesQueuedAttemptWithoutLateResult() {
        val local = store()
        val peer = peer()
        val queue = Queue()
        var sockets = 0
        val client = PhonePairingClient(local, queue, {}, { sockets++; ProbeSocket() }) { _, _, _, _, revision ->
            local.completePairing(revision, peer, "127.0.0.1")
        }
        client.pair("0123", discovered(peer), 28001) { fail("Closed result must not reach a disposed panel") }
        client.close()
        queue.runNext()
        assertEquals(0, sockets)
        assertNull(local.pairedPeer())
        assertNull(client.pair("0123", discovered(peer), 28001) {})
    }

    @Test fun commitWinningBeforeCancelIsRetainedAsSuccess() {
        val local = store()
        val peer = peer()
        val queue = Queue()
        val completed = mutableListOf<Boolean>()
        val client = PhonePairingClient(local, queue, {}, { ProbeSocket() }) { _, _, _, _, revision ->
            local.completePairing(revision, peer, "127.0.0.1")
        }
        try {
            val attempt = client.pair("0123", discovered(peer), 28001, completed::add)!!
            queue.runNext()
            assertFalse(client.cancel(attempt))
            assertEquals(listOf(true), completed)
            assertEquals(peer.identityId, local.pairedPeer()!!.identityId)
        } finally { client.close() }
    }

    @Test fun cancelAfterTrustWriteDoesNotClaimRollbackOrDeliverStalePromptResult() {
        val local = store()
        val peer = peer()
        val queue = Queue()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        var callback = false
        val client = PhonePairingClient(local, queue, {}, { ProbeSocket() }) { _, _, _, _, revision ->
            local.completePairing(revision, peer, "127.0.0.1")
            committed.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val attempt = client.pair("0123", discovered(peer), 28001) { callback = true }!!
        val worker = Thread(queue.tasks.removeFirst())
        try {
            worker.start()
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            assertTrue(client.cancel(attempt))
            assertEquals("Paired with display device", client.status)
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertFalse(callback)
            assertEquals(peer.identityId, local.pairedPeer()!!.identityId)
        } finally { release.countDown(); client.close(); worker.join(5000) }
    }
}
