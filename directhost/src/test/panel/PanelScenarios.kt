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
private class Fixture(val ownerEpoch: String = "owner-1") : AutoCloseable {
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
    val session: Any = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
        Peer { events.add("peer-close") }, DataInputStream(ByteArrayInputStream(byteArrayOf())),
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
        directory.deleteRecursively()
    }
}

private class Child(private val events: MutableList<String>) : java.lang.Process() {
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

private class Peer(private val onClose: () -> Unit) : SSLSocket() {
    override fun getOutputStream() = ByteArrayOutputStream()
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

private fun eventually(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
    check(predicate())
}

fun main() {
    var passed = 0
    for (reason in listOf(SessionStopReason.HOST_STOP, SessionStopReason.CONTROL_END, SessionStopReason.VIDEO_IO)) {
        Fixture().use { f ->
            check(f.off().off == PanelSubmission.SUBMITTED)
            f.held()
            f.close(reason)
            check(f.events.indexOf("cancel-attempt") < f.events.indexOf("restore-attempt"))
            check(f.events.indexOf("restore-attempt") < f.events.indexOf("control-close"))
            check(f.events.indexOf("control-close") < f.events.indexOf("child-wait"))
            check(!f.child.isAlive && !f.state().sessionActive)
            check(f.state().restoration == PanelRestoration.SUBMITTED)
            check(f.state().observation == PanelObservation.UNVERIFIED)
            check(f.receipt().contains("\"stopReason\":\"${reason.name}\""))
            check(f.receipt().contains("\"restorationSubmission\":\"SUBMITTED\""))
            f.close(reason)
            check(f.events.count { it == "restore-attempt" } == 1)
        }
        println("PASS actual Session close $reason cancels/restores before transport, reaps child; observation UNVERIFIED"); passed++
    }
    Fixture().use { f ->
        f.close(SessionStopReason.HOST_STOP)
        check(f.events.none { it == "restore-attempt" })
        check(f.state().restoration == PanelRestoration.NOT_NEEDED)
        check(!f.child.isAlive)
    }
    println("PASS never-blanked actual Session does not send ON"); passed++
    Fixture().use { f ->
        f.off(); f.held(); f.failCancel = true; f.failRestore = true
        f.close(SessionStopReason.CONTROL_IO)
        check(f.events.containsAll(listOf("cancel-attempt", "restore-attempt", "control-close", "child-wait")))
        check(f.state().restoration == PanelRestoration.FAILED && !f.child.isAlive)
        check(f.receipt().contains("\"restorationSubmission\":\"FAILED\""))
        check(f.receipt().contains("\"physicalObservation\":\"UNVERIFIED\""))
    }
    println("PASS cancel and restore failures cannot skip actual Session socket/child cleanup"); passed++
    Fixture().use { f ->
        f.failOff = true
        val response = f.off()
        check(response.off == PanelSubmission.FAILED && !response.sessionActive)
        check(response.restoration == PanelRestoration.SUBMITTED)
        check(f.state().restoration == PanelRestoration.SUBMITTED)
        check(f.events.containsAll(listOf("restore-attempt", "control-close", "child-wait")))
        check(f.receipt().contains("\"stopReason\":\"CONTROL_IO\""))
        check(f.receipt().contains("\"offSubmission\":\"FAILED\""))
        check(!f.child.isAlive)
    }
    println("PASS actual Session OFF write failure owes restoration and triggers complete failure cleanup"); passed++
    Fixture().use { old ->
        old.off(); old.held()
        old.blockCancel = CountDownLatch(1)
        val before = System.nanoTime()
        old.close(SessionStopReason.HOST_STOP)
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
        check(old.cancelEntered.count == 0L && old.socketClosed.get() && !old.child.isAlive)
        check(old.state().restoration == PanelRestoration.PENDING)
        check(old.receipt().contains("\"restorationSubmission\":\"PENDING\""))
        Fixture().use { fresh ->
            check(fresh.state().restoration == PanelRestoration.NOT_NEEDED)
            old.blockCancel!!.countDown()
            eventually { old.state().restoration == PanelRestoration.FAILED }
            check(fresh.writes.isEmpty())
            check(old.writes.none { it.contentEquals(byteArrayOf(10, 1)) })
            check(fresh.state().off == PanelSubmission.NOT_REQUESTED)
        }
    }
    println("PASS blocked cancellation leaves pending truthful receipt; late restore fails only on old closed socket"); passed++
    Fixture().use { f ->
        f.off(); f.blockRestore = CountDownLatch(1)
        val before = System.nanoTime()
        f.close(SessionStopReason.CONTROL_IO)
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
        check(f.restoreEntered.count == 0L && f.socketClosed.get() && !f.child.isAlive)
        check(f.state().restoration == PanelRestoration.PENDING)
        check(f.receipt().contains("\"restorationSubmission\":\"PENDING\""))
        f.blockRestore!!.countDown()
        eventually { f.state().restoration == PanelRestoration.FAILED }
    }
    println("PASS blocked restore cannot hold telemetry/control lock or prevent actual Session socket/child cleanup"); passed++
    Fixture().use { f ->
        f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
        val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
        val host = Bridge(f.directory, f.directory.resolve("unused.jar"), f.timer, listener, {}, f.ownerEpoch)
        @Suppress("UNCHECKED_CAST")
        val active = Bridge::class.java.getDeclaredField("active").apply { isAccessible = true }.get(host) as AtomicReference<Any?>
        active.set(f.session)
        try {
            check(host.requestPanelOff(321, f.state().context.ownerEpoch, f.state().context.projectionToken).off == PanelSubmission.SUBMITTED)
            check(host.panelState().requestId == 321L)
            host.close()
            check(active.get() == null && listener.isClosed && f.timer.isShutdown)
            check(host.panelState().restoration == PanelRestoration.SUBMITTED && !host.panelState().sessionActive)
            check(host.requestPanelOff(322, f.state().context.ownerEpoch, f.state().context.projectionToken).off == PanelSubmission.UNAVAILABLE)
            check(!f.child.isAlive)
        } finally { host.close() }
    }
    println("PASS actual Bridge routes to owned Session and retains final restoration evidence after release"); passed++
    Fixture().use { f ->
        (f.field("scale") as LiveScale).observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        f.blockOff = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val off = thread(isDaemon = true) { try { f.off() } catch (e: Throwable) { failures.add(e) } }
        check(f.offEntered.await(2, TimeUnit.SECONDS))
        val scale = thread(isDaemon = true) {
            try {
                f.session.javaClass.getDeclaredMethod("requestScale", java.lang.Long.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(f.session, 900L, 1.25, false)
            } catch (e: Throwable) { failures.add(e) }
        }
        eventually { scale.stackTrace.any { it.methodName == "writeControl" } }
        val firstDone = CountDownLatch(1); val secondDone = CountDownLatch(1)
        val first = thread(isDaemon = true) { try { f.close(SessionStopReason.HOST_STOP) } finally { firstDone.countDown() } }
        val second = thread(isDaemon = true) { try { f.close(SessionStopReason.HOST_STOP) } finally { secondDone.countDown() } }
        try {
            eventually { f.socketClosed.get() && !f.child.isAlive }
            check(firstDone.await(1, TimeUnit.SECONDS) && secondDone.await(1, TimeUnit.SECONDS)) {
                "R18-2 completed sockets/child but close/OwnedRelease still waits on blocked scale monitor"
            }
            check(f.receipt().contains("\"scaleStatus\":\"PENDING\""))
            check(f.receipt().contains("\"scaleSnapshotBasis\":\"LAST_KNOWN_REPORT_ATTEMPT\""))
            check(f.receipt().contains("\"finalAppliedScaleVerified\":false"))
            check(f.receipt().contains("\"requestedScale\":1.25"))
            check(f.receipt().contains("\"appliedScale\":1.0"))
        } finally {
            f.blockOff!!.countDown()
            listOf(off, scale, first, second).forEach { it.join(2000) }
        }
        check(failures.isEmpty()) { failures.toString() }
    }
    println("PASS R18-2 actual OFF plus actual scale overlap lets first/concurrent close and receipt finish"); passed++
    Fixture().use { old ->
        Fixture(old.ownerEpoch).use { fresh ->
            old.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
            val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
            val host = Bridge(old.directory, old.directory.resolve("unused.jar"), old.timer, listener, {}, old.ownerEpoch)
            @Suppress("UNCHECKED_CAST")
            val active = Bridge::class.java.getDeclaredField("active").apply { isAccessible = true }.get(host) as AtomicReference<Any?>
            val admission = Bridge::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(host)
            synchronized(admission) { active.set(old.session) }
            val original = old.state().context; val replacement = fresh.state().context
            check(original.ownerEpoch == replacement.ownerEpoch && original.projectionToken != replacement.projectionToken)
            old.blockOff = CountDownLatch(1)
            val reply = AtomicReference<PanelPowerState?>(); val failure = AtomicReference<Throwable?>()
            val pending = thread(isDaemon = true) {
                try { reply.set(host.requestPanelOff(700, original.ownerEpoch, original.projectionToken)) }
                catch (e: Throwable) { failure.set(e) }
            }
            check(old.offEntered.await(2, TimeUnit.SECONDS))
            try {
                synchronized(admission) { active.set(fresh.session) }
                check(host.panelState(original.ownerEpoch, original.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(host.requestPanelOff(701, original.ownerEpoch, original.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(host.requestPanelOff(702, "different-owner", replacement.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(fresh.writes.isEmpty())
                val current = host.requestPanelOff(703, replacement.ownerEpoch, replacement.projectionToken)
                check(current.context == replacement && current.off == PanelSubmission.SUBMITTED)
                old.close(SessionStopReason.HOST_STOP)
                check(old.state().restoration == PanelRestoration.PENDING && !old.child.isAlive)
                check(fresh.writes.size == 1)
            } finally {
                old.blockOff!!.countDown(); pending.join(2000); host.close()
            }
            check(!pending.isAlive && failure.get() == null)
            check(reply.get()!!.context == original && !reply.get()!!.sessionActive)
            check(old.writes.isEmpty())
            check(fresh.writes.size == 2)
            check(host.panelState().context == replacement)
        }
    }
    println("PASS actual Bridge atomically captures old Session and rejects stale media/owner tokens without fresh side effects"); passed++
    Fixture().use { f ->
        f.close(SessionStopReason.HOST_STOP)
        val receipt = f.receipt()
        check(receipt.contains("\"scaleSnapshotBasis\":\"UNKNOWN_BEFORE_FIRST_REPORT\""))
        check(receipt.contains("\"scaleStatus\":\"UNKNOWN\""))
        check(receipt.contains("\"scaleRequestId\":null"))
        check(receipt.contains("\"requestedScale\":null"))
        check(receipt.contains("\"appliedScale\":null"))
    }
    println("PASS before first actual scale report receipt retains unknown/null rather than default baseline success"); passed++
    Fixture().use { f ->
        val live = f.field("scale") as LiveScale
        live.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        live.request(800, Double.NaN)
        check(live.state().status == ScaleStatus.READY)
        f.close(SessionStopReason.HOST_STOP)
        val receipt = f.receipt()
        check(receipt.contains("\"scaleStatus\":\"REJECTED\""))
        check(receipt.contains("\"scaleSnapshotBasis\":\"LAST_KNOWN_REPORT_ATTEMPT\""))
        check(receipt.contains("\"requestedScale\":null"))
        check(receipt.contains("\"observedWidth\":480"))
        check(receipt.contains("\"finalAppliedScaleVerified\":false"))
    }
    println("PASS cached rejected report differs honestly from actual LiveScale value and keeps finite/observed semantics"); passed++
    Fixture().use { old ->
        Fixture(old.ownerEpoch).use { fresh ->
            fun bridge(f: Fixture): Bridge {
                f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
                val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
                val result = Bridge(f.directory, f.directory.resolve("unused.jar"), f.timer, listener, {}, f.ownerEpoch)
                @Suppress("UNCHECKED_CAST")
                val active = Bridge::class.java.getDeclaredField("active").apply { isAccessible = true }.get(result) as AtomicReference<Any?>
                active.set(f.session)
                return result
            }
            val oldBridge = bridge(old); val freshBridge = bridge(fresh)
            val routes = PanelSessionRoutes<Bridge> { it.invalidatePanelOperations() }
            try {
                val firstOwner = routes.begin(old.ownerEpoch); routes.publish(firstOwner, oldBridge)
                val captured = routes.capture(old.ownerEpoch)!!
                check(captured.target === oldBridge)
                routes.stop()
                val oldContext = old.state().context
                check(old.state().sessionActive) // Teardown has not begun: admission closes first.
                check(captured.target.requestPanelOff(1000, oldContext.ownerEpoch, oldContext.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(old.writes.isEmpty())
                val newOwner = routes.begin(fresh.ownerEpoch); routes.publish(newOwner, freshBridge)
                routes.complete(firstOwner, oldBridge, old.state())
                routes.publish(firstOwner, oldBridge) // A late accepted-worker publication cannot overwrite fresh.
                check(routes.current()!!.target === freshBridge)
                check(routes.lastState().off == PanelSubmission.UNAVAILABLE)
                val context = fresh.state().context
                check(routes.capture(context.ownerEpoch)!!.target.requestPanelOff(1001, context.ownerEpoch, context.projectionToken).off == PanelSubmission.SUBMITTED)
                routes.stop()
                routes.publish(newOwner, freshBridge) // Publication after Stop remains invalid even for that same Owner.
                check(routes.current() == null)
                check(freshBridge.requestPanelOff(1002, context.ownerEpoch, context.projectionToken).off == PanelSubmission.UNAVAILABLE)
            } finally { oldBridge.close(); freshBridge.close() }
        }
    }
    println("PASS actual Bridge route Stop invalidates before teardown and old publication/cleanup cannot overwrite fresh owner route"); passed++
    println("$passed actual Session composition scenarios passed; modeled Android socket/process edges, no physical proof")
}
