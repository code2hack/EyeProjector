package com.code2hack.eyeprojector.protocol

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

data class PeerEndpoint(val host: String, val pairingPort: Int, val projectionPort: Int = DEFAULT_PROJECTION_PORT) {
    init { require(host.isNotBlank() && pairingPort in 1..65535 && projectionPort in 1024..65535) }
    companion object { const val DEFAULT_PROJECTION_PORT = 27183 }
}

data class RememberedPeer(val certificate: X509Certificate, val endpoint: PeerEndpoint) {
    val identityId: String get() = PeerStore.fingerprint(certificate)
    val tlsName: String get() = PeerStore.tlsName(certificate)
}

/** One app-private directory, also consumed by the explicitly bootstrapped host. */
class PeerStore(val directory: File) {
    private class DirectoryState { var revision = 0L }
    private val state = states.computeIfAbsent(directory.canonicalPath) { DirectoryState() }
    private fun <T> locked(block: () -> T): T = synchronized(state, block)
    fun pairingRevision(): Long = locked { state.revision }
    fun cancelPendingPairing(): Unit = locked { state.revision++ }

    fun completePairing(revision: Long, peer: RememberedPeer, host: String, peerName: String? = null): Unit = locked {
        require(revision == state.revision) { "Pairing was forgotten" }
        val remembered = pairedPeer()
        require(remembered == null || remembered.identityId == peer.identityId) { "Already paired" }
        writeTransportEndpoint(host, peer.endpoint.projectionPort, peerName)
        remember(peer)
    }
    fun ensureIdentity(): X509Certificate = locked {
        directory.mkdirs()
        if (File(directory, "identity.p12").exists()) return@locked identityCertificate()
        val random = SecureRandom()
        val password = ByteArray(32).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }.toCharArray()
        try {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256, random) }.generateKeyPair()
            val name = "${UUID.randomUUID()}.eyeprojector"
            val subject = X500Name("CN=$name")
            val provider = BouncyCastleProvider()
            val builder = JcaX509v3CertificateBuilder(subject, BigInteger(128, random),
                Date(System.currentTimeMillis() - 86_400_000L), Date(System.currentTimeMillis() + 10L * 366 * 86_400_000), subject, keys.public)
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, name)))
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(keys.private)))
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, password); setKeyEntry("identity", keys.private, password, arrayOf(certificate))
            }
            atomicFile("password") { it.writeText(String(password)) }
            atomicFile("identity.p12") { file -> file.outputStream().use { store.store(it, password) } }
            return@locked certificate
        } finally { password.fill('\u0000') }
    }

    fun identityCertificate(): X509Certificate = locked {
        val password = File(directory, "password").readText().trimEnd().toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            File(directory, "identity.p12").inputStream().use { store.load(it, password) }
            return@locked store.getCertificate(store.aliases().nextElement()) as X509Certificate
        } finally { password.fill('\u0000') }
    }

    fun identityId(): String = fingerprint(identityCertificate())

    fun pairedPeer(): RememberedPeer? = locked {
        val file = File(directory, "paired-peer.properties")
        if (!file.exists()) return@locked null
        val p = Properties().apply { file.inputStream().use(::load) }
        val certificate = decodeCertificate(Base64.getDecoder().decode(p.getProperty("certificate")))
        require(fingerprint(certificate) == p.getProperty("identity"))
        return@locked RememberedPeer(certificate, PeerEndpoint(p.getProperty("host"), p.getProperty("pairingPort").toInt(), p.getProperty("projectionPort").toInt()))
    }

    fun remember(peer: RememberedPeer): Unit = locked {
        val p = Properties().apply {
            setProperty("identity", peer.identityId)
            setProperty("certificate", Base64.getEncoder().encodeToString(peer.certificate.encoded))
            setProperty("host", peer.endpoint.host)
            setProperty("pairingPort", peer.endpoint.pairingPort.toString())
            setProperty("projectionPort", peer.endpoint.projectionPort.toString())
        }
        atomicFile("paired-peer.properties") { file -> file.outputStream().use { p.store(it, "EyeProjector peer") } }
    }

    /** A discovery announcement can move an existing pin, never create or replace one. */
    fun updateEndpoint(peerId: String, endpoint: PeerEndpoint): Boolean = locked {
        val peer = pairedPeer() ?: return@locked false
        if (peer.identityId != peerId) return@locked false
        if (peer.endpoint != endpoint) remember(peer.copy(endpoint = endpoint))
        return@locked true
    }

    fun discoveredEndpoint(peerId: String, endpoint: PeerEndpoint, role: PeerRole, localHost: String? = null): Boolean = locked {
        if (!updateEndpoint(peerId, endpoint)) return@locked false
        val remembered = pairedPeer() ?: return@locked false
        if (role == PeerRole.GLASSES) writeTransportEndpoint(endpoint.host, endpoint.projectionPort, remembered.tlsName)
        else if (localHost != null) writeTransportEndpoint(localHost)
        return@locked true
    }

    fun writeTransportEndpoint(host: String, port: Int = PeerEndpoint.DEFAULT_PROJECTION_PORT, peerName: String? = null): Unit = locked {
        require(host.isNotBlank() && port in 1024..65535)
        val p = Properties().apply {
            setProperty("host", host); setProperty("port", port.toString())
            peerName?.let { setProperty("peerName", it) }
        }
        val current = File(directory, "endpoint.properties")
        if (current.exists() && Properties().apply { current.inputStream().use(::load) } == p) return@locked
        atomicFile("endpoint.properties") { file -> file.outputStream().use { p.store(it, "EyeProjector endpoint") } }
    }

    fun forget(): Unit = locked {
        state.revision++
        for (name in listOf("paired-peer.properties", "peer.crt", "endpoint.properties")) {
            val file = File(directory, name)
            check(!file.exists() || file.delete()) { "Could not remove peer trust" }
        }
    }

    private fun atomicFile(name: String, write: (File) -> Unit) {
        directory.mkdirs()
        val tmp = File(directory, "$name.new")
        try { write(tmp); check(tmp.renameTo(File(directory, name))) { "Could not save pairing" } }
        finally { tmp.delete() }
    }

    companion object {
        private val states = ConcurrentHashMap<String, DirectoryState>()
        fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun tlsName(certificate: X509Certificate): String = certificate.subjectAlternativeNames
            ?.firstOrNull { it[0] == GeneralName.dNSName }?.get(1) as? String ?: error("Peer has no TLS name")
        fun decodeCertificate(encoded: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(encoded.inputStream()) as X509Certificate
    }
}
