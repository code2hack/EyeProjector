package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class TlsTest {
    companion object {
        private lateinit var root: File
        private const val PASSWORD = "unit-test-only"
        @BeforeClass @JvmStatic fun identities() {
            root = File("build/tmp/tls-${System.nanoTime()}").apply { mkdirs() }
            val tool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            for (name in listOf("host", "receiver", "rogue")) {
                val dir = File(root, name).apply { mkdir() }
                File(dir, "password").writeText(PASSWORD)
                fun keytool(vararg args: String) {
                    val process = ProcessBuilder(tool, *args).redirectErrorStream(true).start()
                    val output = process.inputStream.readBytes()
                    check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Test identity generation failed (${output.size} bytes)" }
                }
                keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", File(dir, "identity.p12").path,
                    "-storepass", PASSWORD, "-keypass", PASSWORD)
                keytool("-exportcert", "-rfc", "-alias", name, "-keystore", File(dir, "identity.p12").path,
                    "-storepass", PASSWORD, "-file", File(dir, "public.crt").path)
            }
            File(root, "receiver/public.crt").copyTo(File(root, "host/peer.crt"))
            for (name in listOf("receiver", "rogue")) File(root, "host/public.crt").copyTo(File(root, "$name/peer.crt"))
        }
    }

    private fun exchange(client: (Int) -> Unit): Int {
        val listener = Tls.context(File(root, "host")).serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 5000
        val accepted = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            try {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 3000; socket.startHandshake()
                    if (Wire.readRequest(DataInputStream(socket.inputStream)) == Wire.STREAM) {
                        accepted.incrementAndGet(); Wire.writeWelcome(DataOutputStream(socket.outputStream))
                    }
                }
            } catch (_: Exception) { /* Rejected peers must never reach media/control admission. */ }
        })
        try { client(listener.localPort); future.get(8, TimeUnit.SECONDS) }
        finally { listener.close(); executor.shutdownNow() }
        return accepted.get()
    }

    private fun endpoint(name: String, port: Int, host: String = "localhost"): File = File(root, name).also {
        File(it, "endpoint.properties").writeText("host=$host\nport=$port\n")
    }

    @Test fun authenticatedPeerExchangesPinnedWelcome() {
        assertEquals(1, exchange { port ->
            Tls.connect(endpoint("receiver", port)).use { socket ->
                Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.STREAM)
                Wire.readWelcome(DataInputStream(socket.inputStream))
            }
        })
    }

    @Test fun untrustedClientGetsNoWelcome() {
        assertEquals(0, exchange { port ->
            assertThrows(Exception::class.java) {
                Tls.connect(endpoint("rogue", port)).use { socket ->
                    Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.STREAM)
                    Wire.readWelcome(DataInputStream(socket.inputStream))
                }
            }
        })
    }

    @Test fun absentClientIdentityGetsNoWelcome() {
        val certificate = File(root, "host/public.crt").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("host", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val context = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        assertEquals(0, exchange { port ->
            assertThrows(Exception::class.java) {
                (context.socketFactory.createSocket("localhost", port) as SSLSocket).use { socket ->
                    socket.soTimeout = 3000; socket.startHandshake()
                    Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.STREAM)
                    Wire.readWelcome(DataInputStream(socket.inputStream))
                }
            }
        })
    }

    @Test fun wrongServerHostnameIsRejected() {
        assertEquals(0, exchange { port ->
            assertThrows(Exception::class.java) { Tls.connect(endpoint("receiver", port, "127.0.0.1")).close() }
        })
    }
}
