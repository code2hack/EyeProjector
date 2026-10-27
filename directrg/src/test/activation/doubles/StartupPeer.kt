package com.code2hack.eyeprojector.directrg
import java.io.*
import java.net.InetAddress
import java.lang.reflect.Proxy
import javax.net.ssl.*
internal class StartupPeer : SSLSocket() {
    val bytes = ByteArrayOutputStream()
    private val input = PipedInputStream()
    val server = PipedOutputStream(input)
    override fun getInputStream() = input
    override fun getOutputStream() = bytes
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getSession(): SSLSession = Proxy.newProxyInstance(SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
        when (method.name) { "getProtocol" -> "TLSv1.3"; else -> error("Unexpected SSL method ${method.name}") }
    } as SSLSession
    override fun close() { input.close(); server.close() }
    override fun getSupportedCipherSuites() = emptyArray<String>()
    override fun getEnabledCipherSuites() = emptyArray<String>()
    override fun setEnabledCipherSuites(suites: Array<String>) = Unit
    override fun getSupportedProtocols() = arrayOf("TLSv1.3")
    override fun getEnabledProtocols() = supportedProtocols
    override fun setEnabledProtocols(protocols: Array<String>) = Unit
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = Unit
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = Unit
    override fun startHandshake() = Unit
    override fun getNeedClientAuth() = true
    override fun setNeedClientAuth(need: Boolean) = Unit
    override fun getWantClientAuth() = false
    override fun setWantClientAuth(want: Boolean) = Unit
    override fun getUseClientMode() = false
    override fun setUseClientMode(mode: Boolean) = Unit
    override fun getEnableSessionCreation() = true
    override fun setEnableSessionCreation(flag: Boolean) = Unit
}

