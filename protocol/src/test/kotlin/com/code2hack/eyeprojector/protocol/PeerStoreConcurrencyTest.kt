package com.code2hack.eyeprojector.protocol

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Synthetic directories and a delegating provider exercise the actual PeerStore monitor boundaries. */
class PeerStoreConcurrencyTest {
    @Test fun pausedIdentityLoadDoesNotBlockPeerReadsCancellationForgetOrCompletion() = withDirectory { directory ->
        val store = PeerStore(directory)
        val identity = store.ensureIdentity()
        val peer = RememberedPeer(identity, PeerEndpoint("127.0.0.1", 28700))
        store.completePairing(store.pairingRevision(), peer, "127.0.0.1")
        val alias = PeerStore(File(directory, "."))
        val revision = store.pairingRevision()
        withProvider { control ->
            val load = Task("identity-read") { store.identityCertificate() }
            control.pauseThread.set(load.thread)
            load.start()
            var peerOperations: Task<Unit>? = null
            try {
                assertTrue("actual PKCS12 load was entered", control.entered.await(5, TimeUnit.SECONDS))
                peerOperations = Task("peer-operations") {
                    assertEquals(peer.identityId, alias.pairedPeer()!!.identityId)
                    assertEquals(revision, alias.pairingRevision())
                    alias.cancelPendingPairing()
                    assertEquals(revision + 1, store.pairingRevision())
                    assertThrows(IllegalArgumentException::class.java) {
                        store.completePairing(revision, peer, "127.0.0.2")
                    }
                    alias.forget()
                    assertEquals(revision + 2, store.pairingRevision())
                    assertNull(store.pairedPeer())
                    assertFalse(File(directory, "endpoint.properties").exists())
                    assertTrue(File(directory, "identity.p12").exists())
                    assertThrows(IllegalArgumentException::class.java) {
                        store.completePairing(revision + 1, peer, "127.0.0.2")
                    }
                    store.completePairing(revision + 2, peer, "127.0.0.3")
                    assertEquals(peer.identityId, alias.pairedPeer()!!.identityId)
                    assertEquals(revision + 2, alias.pairingRevision())
                }.also { it.start() }
                assertTrue("peer-state operations waited for paused identity PKCS12 load", peerOperations.finished.await(2, TimeUnit.SECONDS))
                peerOperations.result()
                assertFalse("identity load must remain paused during peer operations", load.finished.await(0, TimeUnit.SECONDS))
            } finally {
                control.release.countDown()
                load.join()
                peerOperations?.join()
            }
            assertEquals(PeerStore.fingerprint(identity), PeerStore.fingerprint(load.result()))
            assertTrue("identity password must be wiped after load", control.password.get()!!.all { it == '\u0000' })
        }
    }

    @Test fun canonicalDirectoryInstancesSerializeIdentityInitializationAndRetainOneIdentity() = withDirectory { directory ->
        val first = PeerStore(directory)
        val second = PeerStore(File(directory, "."))
        withProvider { control ->
            val initialize = Task("identity-initialize-first") { first.ensureIdentity() }
            val concurrent = Task("identity-initialize-second") { second.ensureIdentity() }
            control.pauseThread.set(initialize.thread)
            control.observedThreads.add(initialize.thread)
            control.observedThreads.add(concurrent.thread)
            initialize.start()
            try {
                assertTrue(control.entered.await(5, TimeUnit.SECONDS))
                concurrent.start()
                assertTrue(concurrent.started.await(5, TimeUnit.SECONDS))
                assertFalse("second identity initialization must wait", concurrent.finished.await(250, TimeUnit.MILLISECONDS))
                assertEquals("only one initializer may enter PKCS12 creation", 1, control.initializationLoads.get())
                // Identity creation must also leave read-only peer state responsive.
                val read = Task("peer-read-during-initialize") { second.pairedPeer() }
                read.start()
                try {
                    assertTrue("peer read waited for identity generation", read.finished.await(2, TimeUnit.SECONDS))
                    assertNull(read.result())
                } finally {
                    control.release.countDown()
                    read.join()
                }
            } finally {
                control.release.countDown()
                initialize.join()
                if (concurrent.thread.state != Thread.State.NEW) concurrent.join()
            }
            assertEquals(PeerStore.fingerprint(initialize.result()), PeerStore.fingerprint(concurrent.result()))
            assertEquals(1, control.initializationLoads.get())
            assertTrue(control.password.get()!!.all { it == '\u0000' })
            assertFalse(File(directory, "identity.p12.new").exists())
            assertFalse(File(directory, "password.new").exists())
        }
    }

    @Test fun forgetCannotInterleaveWithThePeerAndEndpointCompletionTransaction() = withDirectory { directory ->
        val store = PeerStore(directory)
        val certificate = store.ensureIdentity()
        val encodedEntered = CountDownLatch(1)
        val releaseEncoded = CountDownLatch(1)
        val blockingCertificate = object : ForwardingCertificate(certificate) {
            override fun getEncoded(): ByteArray {
                encodedEntered.countDown()
                check(releaseEncoded.await(10, TimeUnit.SECONDS)) { "test certificate gate was not released" }
                return super.getEncoded()
            }
        }
        val revision = store.pairingRevision()
        val completion = Task("peer-completion") {
            store.completePairing(revision, RememberedPeer(blockingCertificate, PeerEndpoint("127.0.0.1", 28700)), "127.0.0.1")
        }
        val forget = Task("peer-forget") { PeerStore(File(directory, ".")).forget() }
        completion.start()
        try {
            assertTrue(encodedEntered.await(5, TimeUnit.SECONDS))
            // The endpoint has been written, but peer serialization is deliberately incomplete.
            assertTrue(File(directory, "endpoint.properties").exists())
            forget.start()
            assertTrue(forget.started.await(5, TimeUnit.SECONDS))
            assertFalse("Forget interleaved with peer completion", forget.finished.await(250, TimeUnit.MILLISECONDS))
        } finally {
            releaseEncoded.countDown()
            completion.join()
            if (forget.thread.state != Thread.State.NEW) forget.join()
        }
        completion.result()
        forget.result()
        assertEquals(revision + 1, store.pairingRevision())
        assertNull(store.pairedPeer())
        assertFalse(File(directory, "endpoint.properties").exists())
        assertTrue(File(directory, "identity.p12").exists())
        assertEquals(PeerStore.fingerprint(certificate), store.identityId())
    }

    @Test fun actualProviderLoadFailureWipesPasswordAndAllowsIdentityAndPeerRecovery() = withDirectory { directory ->
        val store = PeerStore(directory)
        val certificate = store.ensureIdentity()
        val identity = File(directory, "identity.p12")
        val original = identity.readBytes()
        val passwordFile = File(directory, "password")
        val originalPassword = passwordFile.readBytes()
        try {
            identity.writeBytes(byteArrayOf(0, 1, 2))
            withProvider { control ->
                control.observePasswordThread.set(Thread.currentThread())
                assertThrows(java.io.IOException::class.java) { store.identityCertificate() }
                assertTrue("failure finally must wipe the actual supplied password array", control.password.get()!!.all { it == '\u0000' })
                assertArrayEquals(originalPassword, passwordFile.readBytes())
                identity.writeBytes(original)
                assertEquals(PeerStore.fingerprint(certificate), store.identityId())
                assertTrue(control.password.get()!!.all { it == '\u0000' })
                val revision = store.pairingRevision()
                store.completePairing(revision, RememberedPeer(certificate, PeerEndpoint("127.0.0.1", 28700)), "127.0.0.1")
                store.forget()
                assertEquals(revision + 1, store.pairingRevision())
                assertNull(store.pairedPeer())
                assertEquals(PeerStore.fingerprint(certificate), store.identityId())
            }
        } finally {
            identity.writeBytes(original)
            original.fill(0)
            originalPassword.fill(0)
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("eyeprojector-peerstore-concurrency-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private class Task<T>(name: String, block: () -> T) {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val future = FutureTask(Callable {
            started.countDown()
            try { block() } finally { finished.countDown() }
        })
        val thread = Thread(future, name).apply { isDaemon = true }
        fun start() = thread.start()
        fun result(): T = future.get(5, TimeUnit.SECONDS)
        fun join() {
            thread.join(5_000)
            assertFalse("test worker did not stop: ${thread.name}", thread.isAlive)
        }
    }

    /** The gate pauses the real selected provider only for the designated test worker. */
    private class LoadControl {
        val pauseThread = AtomicReference<Thread>()
        val observePasswordThread = AtomicReference<Thread>()
        val observedThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val password = AtomicReference<CharArray>()
        val initializationLoads = AtomicInteger()
        fun beforeLoad(stream: InputStream?, suppliedPassword: CharArray?) {
            val current = Thread.currentThread()
            if (current in observedThreads && stream == null) initializationLoads.incrementAndGet()
            if (current == pauseThread.get() || current == observePasswordThread.get()) password.set(suppliedPassword)
            if (current == pauseThread.get()) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "test PKCS12 gate was not released" }
            }
        }
    }

    private fun withProvider(block: (LoadControl) -> Unit) = synchronized(providerLock) {
        val providersBefore = Security.getProviders().toList()
        val original = KeyStore.getInstance("PKCS12").provider
        val control = LoadControl()
        val provider = object : Provider("EyeProjectorPeerStoreTest", 1.0, "Test-only delegating PKCS12 load gate") {
            init {
                putService(object : Service(this, "KeyStore", "PKCS12", DelegatingKeyStore::class.java.name, emptyList(), emptyMap()) {
                    override fun newInstance(constructorParameter: Any?): Any = DelegatingKeyStore(KeyStore.getInstance("PKCS12", original), control)
                })
            }
        }
        check(Security.insertProviderAt(provider, 1) == 1)
        try { block(control) } finally {
            control.release.countDown()
            Security.removeProvider(provider.name)
            check(Security.getProvider(provider.name) == null)
            assertEquals("provider list/order must be restored", providersBefore, Security.getProviders().toList())
        }
    }

    private class DelegatingKeyStore(private val delegate: KeyStore, private val control: LoadControl) : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) {
            control.beforeLoad(stream, password)
            delegate.load(stream, password)
        }
        override fun engineStore(stream: OutputStream, password: CharArray?) = delegate.store(stream, password)
        override fun engineGetKey(alias: String, password: CharArray?): Key? = delegate.getKey(alias, password)
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = delegate.getCertificateChain(alias)
        override fun engineGetCertificate(alias: String): Certificate? = delegate.getCertificate(alias)
        override fun engineGetCreationDate(alias: String): Date? = delegate.getCreationDate(alias)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) = delegate.setKeyEntry(alias, key, password, chain)
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = delegate.setKeyEntry(alias, key, chain)
        override fun engineSetCertificateEntry(alias: String, certificate: Certificate) = delegate.setCertificateEntry(alias, certificate)
        override fun engineDeleteEntry(alias: String) = delegate.deleteEntry(alias)
        override fun engineAliases(): Enumeration<String> = delegate.aliases()
        override fun engineContainsAlias(alias: String): Boolean = delegate.containsAlias(alias)
        override fun engineSize(): Int = delegate.size()
        override fun engineIsKeyEntry(alias: String): Boolean = delegate.isKeyEntry(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = delegate.isCertificateEntry(alias)
        override fun engineGetCertificateAlias(certificate: Certificate): String? = delegate.getCertificateAlias(certificate)
    }

    private open class ForwardingCertificate(private val certificate: X509Certificate) : X509Certificate() {
        override fun getEncoded(): ByteArray = certificate.encoded
        override fun checkValidity() = certificate.checkValidity()
        override fun checkValidity(date: Date) = certificate.checkValidity(date)
        override fun getVersion(): Int = certificate.version
        override fun getSerialNumber(): java.math.BigInteger = certificate.serialNumber
        override fun getIssuerDN(): java.security.Principal = certificate.issuerDN
        override fun getSubjectDN(): java.security.Principal = certificate.subjectDN
        override fun getNotBefore(): Date = certificate.notBefore
        override fun getNotAfter(): Date = certificate.notAfter
        override fun getTBSCertificate(): ByteArray = certificate.tbsCertificate
        override fun getSignature(): ByteArray = certificate.signature
        override fun getSigAlgName(): String = certificate.sigAlgName
        override fun getSigAlgOID(): String = certificate.sigAlgOID
        override fun getSigAlgParams(): ByteArray? = certificate.sigAlgParams
        override fun getIssuerUniqueID(): BooleanArray? = certificate.issuerUniqueID
        override fun getSubjectUniqueID(): BooleanArray? = certificate.subjectUniqueID
        override fun getKeyUsage(): BooleanArray? = certificate.keyUsage
        override fun getBasicConstraints(): Int = certificate.basicConstraints
        override fun verify(key: java.security.PublicKey) = certificate.verify(key)
        override fun verify(key: java.security.PublicKey, provider: String) = certificate.verify(key, provider)
        override fun getPublicKey(): java.security.PublicKey = certificate.publicKey
        override fun toString(): String = "Synthetic forwarding test certificate"
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = certificate.criticalExtensionOIDs
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = certificate.nonCriticalExtensionOIDs
        override fun getExtensionValue(oid: String): ByteArray? = certificate.getExtensionValue(oid)
        override fun hasUnsupportedCriticalExtension(): Boolean = certificate.hasUnsupportedCriticalExtension()
    }

    companion object { private val providerLock = Any() }
}
