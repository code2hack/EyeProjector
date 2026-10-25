package com.code2hack.eyeprojector.directhost

import android.net.LocalSocket
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.net.ssl.*
import kotlin.concurrent.thread

/** Reflect only to arrange Android-owned resources; every command/close uses the actual Session. */
internal class PanelFixture(val ownerEpoch: String = "owner-1") : AutoCloseable {
    val directory = Files.createTempDirectory("eyeprojector-panel-adapter-").toFile()
    val events = CopyOnWriteArrayList<String>()
    val writes = CopyOnWriteArrayList<ByteArray>()
    val timer = Executors.newSingleThreadScheduledExecutor()
    val socketClosed = AtomicBoolean()
    var blockOff: CountDownLatch? = null
    val offEntered = CountDownLatch(1)
    var failOff = false
    var failRestore = false
    var failCancel = false
    var blockCancel: CountDownLatch? = null
    var blockRestore: CountDownLatch? = null
    val cancelEntered = CountDownLatch(1)
    val restoreEntered = CountDownLatch(1)
    private val type = Class.forName("com.code2hack.eyeprojector.directhost.Session")
    val peer = Peer { events.add("peer-close") }
    val session: Any = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
        peer, DataInputStream(ByteArrayInputStream(byteArrayOf())),
        directory, directory.resolve("unused.jar"), timer, ownerEpoch, {} as () -> Unit)
    val output = object : OutputStream() {
        override fun write(value: Int) = error("Expected whole stock messages")
        override fun write(payload: ByteArray) {
            if (payload.contentEquals(byteArrayOf(10, 0))) {
                offEntered.countDown(); blockOff?.await()
            }
            if (payload.contentEquals(byteArrayOf(10, 0)) && failOff) throw IOException("modeled partial OFF write failure")
            if (payload[0].toInt() == 2 && payload[1].toInt() == 3) {
                events.add("cancel-attempt"); cancelEntered.countDown()
                check(blockCancel?.await(5, TimeUnit.SECONDS) != false)
                if (failCancel) throw IOException("modeled cancellation failure")
            }
            if (payload.contentEquals(byteArrayOf(10, 1))) {
                events.add("restore-attempt")
                restoreEntered.countDown()
                check(blockRestore?.await(5, TimeUnit.SECONDS) != false)
                if (failRestore || socketClosed.get()) throw IOException("modeled restore transport failure")
            }
            if (socketClosed.get()) throw IOException("old transport is closed")
            writes.add(payload.copyOf())
        }
    }
    val child = Child(events)
    val input: PhoneTouchDispatcher get() = field("inputControls") as PhoneTouchDispatcher
    init {
        set("control", LocalSocket(output) { events.add("control-close"); socketClosed.set(true) })
        set("video", LocalSocket(onClose = { events.add("video-close") }))
        set("server", child)
        (field("frames") as AtomicLong).set(1)
        input.geometry(Geometry(480, 640))
    }
    fun field(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(session)
    private fun set(name: String, value: Any) { type.getDeclaredField(name).apply { isAccessible = true }.set(session, value) }
    private fun call(name: String, params: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
        type.getDeclaredMethod(name, *params).apply { isAccessible = true }.invoke(session, *args)
    fun off(id: Long = 100): PanelPowerState = call("requestPanelOff", arrayOf(Long::class.javaPrimitiveType!!), id) as PanelPowerState
    fun state() = call("panelState") as PanelPowerState
    fun close(reason: SessionStopReason) { call("close", arrayOf(SessionStopReason::class.java), reason) }
    fun held() { check(input.touch(Touch(Touch.Phase.DOWN, 2, Tap(10, 20, Geometry(480, 640))))) }
    fun receipt() = directory.resolve("last-session.json").readText()
    override fun close() {
        blockOff?.countDown()
        blockCancel?.countDown()
        blockRestore?.countDown()
        close(SessionStopReason.OWNER_CLOSE)
        timer.shutdownNow()
        // Fixture cleanup only; production Stop/deadlines never wait for native setup.
        (runCatching { field("nativeSetup") as ExecutorService }.getOrNull())?.let {
            check(it.awaitTermination(3, TimeUnit.SECONDS)) { "Owned native setup fixture did not retire" }
        }
        directory.deleteRecursively()
    }
}

internal class Child(private val events: MutableList<String>) : java.lang.Process() {
    private var alive = true
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int { alive = false; return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { events.add("child-wait"); alive = false; return true }
    override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
    override fun destroy() { events.add("child-destroy"); alive = false }
    override fun isAlive() = alive
}

internal class Peer(private val onClose: () -> Unit) : SSLSocket() {
    val sink = ByteArrayOutputStream()
    override fun getOutputStream() = sink
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getSession(): SSLSession = Proxy.newProxyInstance(SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
        when (method.name) { "getProtocol" -> "TLSv1.3"; else -> error("Unexpected SSL method ${method.name}") }
    } as SSLSession
    override fun close() { onClose() }
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

