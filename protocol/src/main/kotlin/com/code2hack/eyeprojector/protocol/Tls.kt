package com.code2hack.eyeprojector.protocol

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Private mission-provisioned identities only; never modifies Android's system trust. */
object Tls {
    fun context(directory: File): SSLContext {
        val password = File(directory, "password").readText().trimEnd().toCharArray()
        try {
            val keys = KeyStore.getInstance("PKCS12")
            File(directory, "identity.p12").inputStream().use { keys.load(it, password) }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            km.init(keys, password)
            val peer = File(directory, "peer.crt").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("peer", peer) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            return SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
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
            val socket = context(directory).socketFactory.createSocket(raw, host, port, true) as SSLSocket
            try {
                socket.soTimeout = 4000; socket.tcpNoDelay = true; socket.sendBufferSize = 4096; socket.receiveBufferSize = 65536
                socket.enabledProtocols = socket.supportedProtocols.filter { it in listOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                return socket
            } catch (error: Exception) { socket.close(); throw error }
        } catch (error: Exception) { raw.close(); throw error }
    }
}
