package com.code2hack.eyeprojector.protocol

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private fun store(name: String) = PeerStore(File("build/tmp/pairing-${System.nanoTime()}/$name")).apply { ensureIdentity() }

    @Test fun fiveMinuteExpiryRotatesAndInvalidatesInFlightCode() {
        var now = 100L
        val values = ArrayDeque(listOf("0123", "4567", "8901"))
        val code = PairingCode({ now }, { values.removeFirst() })
        val old = code.display()
        assertEquals("0123", old.code); assertEquals(300L, old.remainingSeconds)
        now += 299_001
        assertEquals(1L, code.display().remainingSeconds)
        assertTrue(code.isCurrent(old))
        now += 999
        assertEquals("4567", code.display().code)
        assertEquals(300L, code.display().remainingSeconds)
        assertFalse(code.isCurrent(old))
        code.rotate()
        assertEquals("8901", code.display().code)
    }

    private data class Result(val phone: PeerStore, val glasses: PeerStore, val clientAccepted: Boolean, val serverAccepted: Boolean)

    private fun pair(
        clientCode: String = "0123", serverCode: String = "0123",
        expected: String? = null, phone: PeerStore = store("phone"), glasses: PeerStore = store("glasses"),
        serverClock: () -> Long = { 0L },
    ): Result {
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            var generation = 0
            val codes = PairingCode(serverClock) { if (generation++ == 0) serverCode else "%04d".format((serverCode.toInt() + 1) % 10_000) }
            runCatching { server.accept().use { PairingExchange.pairGlasses(it, codes, glasses, 28002) } }.isSuccess
        })
        return try {
            val client = runCatching {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
                    PairingExchange.pairPhone(it, clientCode, phone, 28001, expected ?: glasses.identityId())
                }
            }.isSuccess
            Result(phone, glasses, client, future.get(20, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun codeAuthenticatesBothRememberedIdentitiesAcrossRestartAndAddressChange() {
        val result = pair()
        assertTrue(result.clientAccepted); assertTrue(result.serverAccepted)
        val phone = PeerStore(result.phone.directory)
        val glasses = PeerStore(result.glasses.directory)
        assertEquals(glasses.identityId(), phone.pairedPeer()!!.identityId)
        assertEquals(phone.identityId(), glasses.pairedPeer()!!.identityId)
        assertEquals("127.0.0.1", glasses.pairedPeer()!!.endpoint.host)
        assertTrue(glasses.updateEndpoint(phone.identityId(), PeerEndpoint("192.168.43.1", 28700)))
        assertEquals("192.168.43.1", PeerStore(glasses.directory).pairedPeer()!!.endpoint.host)
        assertFalse(glasses.updateEndpoint("0".repeat(64), PeerEndpoint("192.168.43.9", 28700)))
        assertEquals("192.168.43.1", glasses.pairedPeer()!!.endpoint.host)
    }

    @Test fun wrongCodeEstablishesNoTrustOnEitherSide() {
        val result = pair(clientCode = "0124")
        assertFalse(result.clientAccepted); assertFalse(result.serverAccepted)
        assertNull(result.phone.pairedPeer()); assertNull(result.glasses.pairedPeer())
    }

    @Test fun discoveryIdentitySubstitutionEstablishesNoTrust() {
        val result = pair(expected = "0".repeat(64))
        assertFalse(result.clientAccepted); assertFalse(result.serverAccepted)
        assertNull(result.phone.pairedPeer()); assertNull(result.glasses.pairedPeer())
    }

    @Test fun codeExpiringDuringAuthenticatedExchangeEstablishesNoTrust() {
        var calls = 0
        val result = pair(serverClock = { if (calls++ == 0) 0 else 300_000L })
        assertFalse(result.clientAccepted); assertFalse(result.serverAccepted)
        assertNull(result.phone.pairedPeer()); assertNull(result.glasses.pairedPeer())
    }

    @Test fun pairedGlassesDoNotReplaceRememberedPhoneWithAnotherCodeHolder() {
        val original = pair()
        val replacement = pair(glasses = original.glasses)
        assertFalse(replacement.clientAccepted); assertFalse(replacement.serverAccepted)
        assertEquals(original.phone.identityId(), original.glasses.pairedPeer()!!.identityId)
        assertNull(replacement.phone.pairedPeer())
    }

    @Test fun forgetRemovesTrustButRetainsLocalIdentity() {
        val result = pair()
        val originalIdentity = result.glasses.identityId()
        File(result.glasses.directory, "peer.crt").writeBytes(result.phone.identityCertificate().encoded)
        result.glasses.forget()
        assertNull(PeerStore(result.glasses.directory).pairedPeer())
        assertEquals(originalIdentity, PeerStore(result.glasses.directory).identityId())
        assertFalse(File(result.glasses.directory, "peer.crt").exists())
        assertFalse(File(result.glasses.directory, "endpoint.properties").exists())
        assertNotNull(result.phone.pairedPeer())
    }

    @Test fun forgetInvalidatesAConcurrentPairingCompletion() {
        val local = store("local")
        val other = store("other")
        val revision = local.pairingRevision()
        PeerStore(local.directory).forget() // Activity recreation uses a new store object.
        assertThrows(IllegalArgumentException::class.java) {
            local.completePairing(revision, RememberedPeer(other.identityCertificate(), PeerEndpoint("127.0.0.1", 28002)), "127.0.0.1")
        }
        assertNull(local.pairedPeer())
        assertFalse(File(local.directory, "endpoint.properties").exists())
        assertFalse(PeerStore(local.directory).discoveredEndpoint(other.identityId(), PeerEndpoint("127.0.0.1", 28002), PeerRole.GLASSES))
    }

    @Test fun forgetRejectsAdmissionOnAlreadyHandshakenSocket() {
        val result = pair()
        val listener = Tls.context(result.phone.directory).serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 5000
        result.glasses.writeTransportEndpoint("127.0.0.1", listener.localPort, PeerStore.tlsName(result.phone.identityCertificate()))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            (listener.accept() as SSLSocket).use {
                it.soTimeout = 5000; it.startHandshake()
                Tls.requireTrustedPeer(it, result.phone.directory)
                result.phone.forget()
                assertThrows(Exception::class.java) { Tls.requireTrustedPeer(it, result.phone.directory) }
                it.outputStream.write(42)
            }
        })
        try {
            Tls.connect(result.glasses.directory).use { assertEquals(42, it.inputStream.read()) }
            future.get(10, TimeUnit.SECONDS)
        } finally { listener.close(); executor.shutdownNow() }
    }

    @Test fun pairedIdentitiesMutuallyAuthenticateUsingStableNamesOnCurrentIp() {
        val result = pair()
        val listener = Tls.context(result.phone.directory).serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 5000
        result.glasses.writeTransportEndpoint("127.0.0.1", listener.localPort, PeerStore.tlsName(result.phone.identityCertificate()))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            (listener.accept() as SSLSocket).use { it.soTimeout = 5000; it.startHandshake(); it.outputStream.write(42) }
        })
        try {
            Tls.connect(result.glasses.directory).use { assertEquals(42, it.inputStream.read()) }
            future.get(10, TimeUnit.SECONDS)
        } finally { listener.close(); executor.shutdownNow() }
    }

    @Test fun discoveryDecodingUsesSourceAddressAndRejectsInvalidVersionOrBounds() {
        val id = "a".repeat(64)
        assertEquals(PeerEndpoint("192.168.43.1", 28700), LanDiscovery.decode("EPD1 PHONE $id 28700 27183", "192.168.43.1")!!.endpoint)
        assertNull(LanDiscovery.decode("EPD2 PHONE $id 28700 27183", "192.168.43.1"))
        assertNull(LanDiscovery.decode("EPD1 PHONE $id 70000 27183", "192.168.43.1"))
        assertNull(LanDiscovery.decode("EPD1 PHONE nope 28700 27183", "192.168.43.1"))
    }
}
