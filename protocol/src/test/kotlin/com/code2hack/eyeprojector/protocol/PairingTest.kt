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
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

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

    @Test fun fiveFailedOrInProgressAttemptsExhaustCodeAndRotationResetsBudget() {
        var now = 0L
        var next = 1000
        val codes = PairingCode({ now }, { (next++).toString() })
        val pending = (1..5).map { checkNotNull(codes.beginAttempt()) }
        assertEquals(0, codes.display().attemptsRemaining)
        assertNull(codes.beginAttempt())
        pending.forEach { codes.finishAttempt(it, false) }
        assertNull(codes.beginAttempt())
        now = 300_000
        assertEquals("1001", codes.display().code)
        assertEquals(5, codes.display().attemptsRemaining)
        assertNotNull(codes.beginAttempt())
        // A late success from an expired code cannot replenish the new code.
        codes.finishAttempt(pending.first(), true)
        assertEquals(4, codes.display().attemptsRemaining)
    }

    @Test fun successfulAttemptReleasesItsInProgressSlot() {
        val codes = PairingCode({ 0L }, { "0123" })
        val attempt = checkNotNull(codes.beginAttempt())
        assertEquals(4, codes.display().attemptsRemaining)
        codes.finishAttempt(attempt, true)
        assertEquals(5, codes.display().attemptsRemaining)
    }

    private data class Result(val phone: PeerStore, val glasses: PeerStore, val clientAccepted: Boolean, val serverAccepted: Boolean)

    private fun pair(
        clientCode: String = "0123", serverCode: String = "0123",
        expected: String? = null, phone: PeerStore = store("phone"), glasses: PeerStore = store("glasses"),
        serverClock: () -> Long = { 0L },
        sharedCodes: PairingCode? = null,
    ): Result {
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            var generation = 0
            val codes = sharedCodes ?: PairingCode(serverClock) { if (generation++ == 0) serverCode else "%04d".format((serverCode.toInt() + 1) % 10_000) }
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

    @Test fun codeAuthenticatesExistingCaIssuedLeafWithProofOfItsPrivateKey() {
        val phone = store("ca-phone")
        val original = phone.identityCertificate()
        val ca = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val provider = BouncyCastleProvider()
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            JcaX509v3CertificateBuilder(X500Name("CN=Existing private CA"), BigInteger.ONE,
                Date(System.currentTimeMillis() - 1000), Date(System.currentTimeMillis() + 86_400_000),
                X500Name(original.subjectX500Principal.name), original.publicKey)
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, PeerStore.tlsName(original))))
                .build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(ca.private)))
        val password = File(phone.directory, "password").readText().toCharArray()
        try {
            val keys = KeyStore.getInstance("PKCS12")
            File(phone.directory, "identity.p12").inputStream().use { keys.load(it, password) }
            val alias = keys.aliases().nextElement()
            val key = keys.getKey(alias, password)
            keys.setKeyEntry(alias, key, password, arrayOf(certificate))
            File(phone.directory, "identity.p12").outputStream().use { keys.store(it, password) }
        } finally { password.fill('\u0000') }
        val result = pair(phone = phone)
        assertTrue(result.clientAccepted); assertTrue(result.serverAccepted)
        assertEquals(PeerStore.fingerprint(certificate), result.glasses.pairedPeer()!!.identityId)
        assertMutualTls(result)
    }

    @Test fun wrongCodeEstablishesNoTrustOnEitherSide() {
        val result = pair(clientCode = "0124")
        assertFalse(result.clientAccepted); assertFalse(result.serverAccepted)
        assertNull(result.phone.pairedPeer()); assertNull(result.glasses.pairedPeer())
    }

    @Test fun networkExchangeRejectsSixthAttemptEvenWithRightCodeThenAcceptsRotatedCode() {
        var now = 0L
        var generation = 0
        val codes = PairingCode({ now }, { if (generation++ == 0) "0123" else "4567" })
        val glasses = store("budget-glasses")
        repeat(5) {
            val rejected = pair(clientCode = "9999", glasses = glasses, sharedCodes = codes)
            assertFalse(rejected.clientAccepted); assertFalse(rejected.serverAccepted)
        }
        val exhausted = pair(glasses = glasses, sharedCodes = codes)
        assertFalse(exhausted.clientAccepted); assertFalse(exhausted.serverAccepted)
        assertNull(glasses.pairedPeer())
        now = 300_000
        val rotated = pair(clientCode = "4567", glasses = glasses, sharedCodes = codes)
        assertTrue(rotated.clientAccepted); assertTrue(rotated.serverAccepted)
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
        assertMutualTls(pair())
    }

    private fun assertMutualTls(result: Result) {
        val listener = Tls.context(result.phone.directory).serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 5000
        result.glasses.writeTransportEndpoint("127.0.0.1", listener.localPort, PeerStore.tlsName(result.phone.identityCertificate()))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            (listener.accept() as SSLSocket).use {
                it.soTimeout = 5000; it.startHandshake(); Tls.requireTrustedPeer(it, result.phone.directory)
                it.outputStream.write(42)
            }
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
