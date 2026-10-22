package com.code2hack.eyeprojector.protocol

import java.security.KeyManagementException
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/** Test-only pause in actual SSLContext initialization; all TLS still uses the original provider. */
internal object DelayedTlsContext {
    class Gate(val delegateProvider: Provider) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clientInitializations = AtomicInteger()
        @Volatile var client: Thread? = null
            private set

        fun bindClient() {
            check(client == null)
            client = Thread.currentThread()
        }
    }

    private val lock = Any()
    @Volatile var active: Gate? = null
        private set

    fun <T> paused(block: (Gate) -> T): T = synchronized(lock) {
        // Capture before registration: looking up TLS through the new provider would recurse.
        val gate = Gate(SSLContext.getInstance("TLS").provider)
        val provider = object : Provider("EyeProjector-TlsOrder-${System.nanoTime()}", 1.0, "Test-only TLS initialization latch") {
            init { put("SSLContext.TLS", DelayedTlsContextSpi::class.java.name) }
        }
        active = gate
        try {
            check(Security.insertProviderAt(provider, 1) == 1)
            block(gate)
        } finally {
            gate.release.countDown()
            Security.removeProvider(provider.name)
            active = null
        }
    }
}

/** Public no-argument SPI constructor is required by the JCA provider loader. */
class DelayedTlsContextSpi : SSLContextSpi() {
    private val gate = checkNotNull(DelayedTlsContext.active)
    private val delegate = SSLContext.getInstance("TLS", gate.delegateProvider)

    override fun engineInit(keys: Array<out KeyManager>?, trust: Array<out TrustManager>?, random: SecureRandom?) {
        if (Thread.currentThread() === gate.client) {
            gate.clientInitializations.incrementAndGet()
            gate.entered.countDown()
            if (!gate.release.await(10, TimeUnit.SECONDS)) throw KeyManagementException("Test initialization latch was not released")
        }
        delegate.init(keys, trust, random)
    }

    override fun engineGetSocketFactory(): SSLSocketFactory = delegate.socketFactory
    override fun engineGetServerSocketFactory(): SSLServerSocketFactory = delegate.serverSocketFactory
    override fun engineCreateSSLEngine(): SSLEngine = delegate.createSSLEngine()
    override fun engineCreateSSLEngine(host: String, port: Int): SSLEngine = delegate.createSSLEngine(host, port)
    override fun engineGetServerSessionContext(): SSLSessionContext = delegate.serverSessionContext
    override fun engineGetClientSessionContext(): SSLSessionContext = delegate.clientSessionContext
    override fun engineGetDefaultSSLParameters(): SSLParameters = delegate.defaultSSLParameters
    override fun engineGetSupportedSSLParameters(): SSLParameters = delegate.supportedSSLParameters
}
