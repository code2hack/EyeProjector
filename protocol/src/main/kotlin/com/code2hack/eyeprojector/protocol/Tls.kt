package com.code2hack.eyeprojector.protocol

import android.annotation.SuppressLint
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** App-private identities and exact remembered peer pins; never modifies system trust. */
object Tls {
    fun context(directory: File): SSLContext {
        val password = File(directory, "password").readText().trimEnd().toCharArray()
        try {
            val keys = KeyStore.getInstance("PKCS12")
            File(directory, "identity.p12").inputStream().use { keys.load(it, password) }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            km.init(keys, password)
            fun peer(): X509Certificate = PeerStore(directory).pairedPeer()?.certificate
                ?: File(directory, "peer.crt").inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
            // Resolve trust again at admission: Forget also revokes new handshakes
            // on an already-created SSLContext using this same private directory.
            @SuppressLint("CustomX509TrustManager") // Delegates to default PKIX validation and additionally requires the exact leaf pin.
            val pin = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(peer())
                private fun validate(chain: Array<X509Certificate>, authType: String, client: Boolean) {
                    val remembered = peer()
                    require(chain.isNotEmpty() && MessageDigest.isEqual(chain[0].encoded, remembered.encoded)) { "Untrusted peer identity" }
                    remembered.checkValidity()
                    val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("peer", remembered) }
                    val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                    val validator = managers.trustManagers.filterIsInstance<X509TrustManager>().single()
                    if (client) validator.checkClientTrusted(chain, authType) else validator.checkServerTrusted(chain, authType)
                }
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = validate(chain, authType, true)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = validate(chain, authType, false)
            }
            return SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(pin), null) }
        } finally { password.fill('\u0000') }
    }

    fun settings(directory: File): Properties = Properties().apply {
        File(directory, "endpoint.properties").inputStream().use(::load)
    }

    fun connect(directory: File): SSLSocket {
        val settings = settings(directory)
        val host = settings.getProperty("host") ?: error("Endpoint missing")
        val port = settings.getProperty("port").toInt().also { require(it in 1024..65535) }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), 3000)
            val peerName = settings.getProperty("peerName") ?: host
            val socket = context(directory).socketFactory.createSocket(raw, peerName, port, true) as SSLSocket
            try {
                socket.soTimeout = 4000; socket.tcpNoDelay = true; socket.sendBufferSize = 4096; socket.receiveBufferSize = 65536
                socket.enabledProtocols = socket.supportedProtocols.filter { it in listOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                requireTrustedPeer(socket, directory)
                return socket
            } catch (error: Exception) { socket.close(); throw error }
        } catch (error: Exception) { raw.close(); throw error }
    }

    /** Call after every server handshake, including resumed sessions, before Wire admission. */
    fun requireTrustedPeer(socket: SSLSocket, directory: File) {
        val expected = PeerStore(directory).pairedPeer()?.certificate ?: File(directory, "peer.crt").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        expected.checkValidity()
        val actual = socket.session.peerCertificates.firstOrNull() as? X509Certificate
        require(actual != null && MessageDigest.isEqual(actual.encoded, expected.encoded)) { "Untrusted peer identity" }
    }
}
