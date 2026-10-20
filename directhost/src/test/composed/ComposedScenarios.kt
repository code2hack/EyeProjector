package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.os.IBinder
import com.code2hack.eyeprojector.protocol.*
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.net.ssl.*
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

/** Android IPC and resources are modeled; every dispatch, route, scale and cleanup body is actual. */
private class ComposedFixture : AutoCloseable {
    val old = PanelFixture("composed-owner")
    val bridge = bridge(old)
    val service = ShizukuHostService(Context(old.directory))
    val lifecycle = field(service, "lifecycle") as OwnedHostLifecycle
    @Suppress("UNCHECKED_CAST") val routes = field(service, "panelRoutes") as PanelSessionRoutes<Bridge>
    val owner = routes.begin(old.ownerEpoch)
    val runGate = CountDownLatch(1)
    val started = CountDownLatch(1)
    val proxy = BinderAdapter(service)
    val host: HostBootstrap
    val model: SessionController
    init {
        set(service, "sessions", OwnedShellSessions(old.directory.resolve("owned-shell")))
        set(service, "sessionEpoch", old.ownerEpoch)
        set(service, "projectionBridge", bridge) // Exact-parent scalar route; corrected code uses routes instead.
        routes.publish(owner, bridge)
        lifecycle.start { ready ->
            ready()
            object : OwnedHostLifecycle.Backend {
                override fun run() { started.countDown(); runGate.await() }
                override fun close() {
                    try { bridge.close() }
                    finally { runGate.countDown(); routes.complete(owner, bridge, bridge.panelState()) }
                }
            }
        }
        check(started.await(2, TimeUnit.SECONDS)); lifecycle.report(OwnedHostLifecycle.State.STREAMING)
        (old.field("scale") as LiveScale).observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        Shizuku.reset(); Shizuku.peekVersion = 123; Shizuku.peekService = proxy
        host = HostBootstrap::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(Context(old.directory))
        drain()
        check(host.status().projectionToken == old.state().context.projectionToken)
        model = SessionController({}, { host.stop() })
        host.observe { model.observeBackend(it.sessionEpoch, it.backend, it.detail.orEmpty(), it.rejectedEpoch) }
    }
    fun drain(operations: Boolean = false) {
        repeat(3) {
            (field(host, "executor") as ScheduledExecutorService).submit {}.get(2, TimeUnit.SECONDS)
            if (operations) for (name in listOf("panelExecutor", "panelQueryExecutor", "scaleExecutor")) {
                val worker = runCatching { field(host, name) as ExecutorService }.getOrNull()
                worker?.submit {}?.get(2, TimeUnit.SECONDS)
            }
        }
    }
    override fun close() {
        old.blockOff?.countDown(); proxy.scaleGate?.countDown(); runGate.countDown()
        bridge.close(); old.close()
        for (name in listOf("panelExecutor", "panelQueryExecutor", "scaleExecutor", "executor")) {
            val worker = runCatching { field(host, name) as ExecutorService }.getOrNull()
            worker?.shutdownNow(); worker?.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}

private class BinderAdapter(val service: ShizukuHostService) : IHostBootstrap, IBinder {
    private val deaths = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var alive = true
    val stopEntered = CountDownLatch(1)
    val scaleEntered = CountDownLatch(1)
    var scaleGate: CountDownLatch? = null
    val scaleContexts = CopyOnWriteArrayList<PanelContext>()
    val rawReplies = CopyOnWriteArrayList<ByteArray>()
    val statusReads = AtomicInteger()
    val stops = AtomicInteger()
    override val isBinderAlive get() = alive
    override fun asBinder() = this
    override fun linkToDeath(recipient: () -> Unit, flags: Int) { deaths.add(recipient) }
    override fun status(): String { check(alive); statusReads.incrementAndGet(); return service.status() }
    override fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String = error("No backend creation in host fixture")
    override fun stop(): String { stops.incrementAndGet(); stopEntered.countDown(); return service.stop() }
    // An actual process exit is modeled as exact Binder death; service.destroy would exit this JVM.
    override fun destroy() { die() }
    fun die() { alive = false; deaths.toList().forEach { it() } }
    override fun requestPanelOff(requestId: Long, expectedOwnerEpoch: String, expectedProjectionToken: String) =
        service.requestPanelOff(requestId, expectedOwnerEpoch, expectedProjectionToken)
    override fun panelState(expectedOwnerEpoch: String, expectedProjectionToken: String) = service.panelState(expectedOwnerEpoch, expectedProjectionToken)
    override fun requestScale(requestId: Long, scale: Double, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        scaleContexts.add(PanelContext(expectedOwnerEpoch, expectedProjectionToken))
        scaleEntered.countDown(); scaleGate?.await()
        return service.requestScale(requestId, scale, expectedOwnerEpoch, expectedProjectionToken).also { rawReplies.add(it) }
    }
    override fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        scaleContexts.add(PanelContext(expectedOwnerEpoch, expectedProjectionToken))
        scaleEntered.countDown(); scaleGate?.await()
        return service.scaleState(expectedOwnerEpoch, expectedProjectionToken).also { rawReplies.add(it) }
    }
}

private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
private fun set(target: Any, name: String, value: Any) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
private fun bridge(f: PanelFixture): Bridge {
    f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
    val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
    val bridge = Bridge(f.directory, f.directory.resolve("unused.jar"), f.timer, listener, {}, f.ownerEpoch)
    @Suppress("UNCHECKED_CAST") val active = field(bridge, "active") as AtomicReference<Any?>
    active.set(f.session)
    return bridge
}
private fun replace(f: ComposedFixture, fresh: PanelFixture, freshBridge: Bridge, replaceOwner: Boolean) {
    if (replaceOwner) {
        val owner = f.routes.begin(fresh.ownerEpoch)
        f.routes.publish(owner, freshBridge)
        set(f.service, "sessionEpoch", fresh.ownerEpoch)
        set(f.service, "projectionBridge", freshBridge)
        // Late old completion/publication must not replace the accepted fresh owner route.
        f.routes.complete(f.owner, f.bridge, f.old.state())
        f.routes.publish(f.owner, f.bridge)
        check(f.routes.current()?.target === freshBridge)
    } else {
        @Suppress("UNCHECKED_CAST") val active = field(f.bridge, "active") as AtomicReference<Any?>
        synchronized(field(f.bridge, "admission")!!) { active.set(fresh.session) }
    }
}
private fun eventually(message: String, test: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!test() && System.nanoTime() < until) Thread.sleep(5)
    check(test()) { message }
}
private var failures = 0
private var passed = 0
private fun scenario(name: String, run: () -> Unit) {
    try { run(); passed++; println("PASS $name") }
    catch (e: Throwable) { failures++; println("FAIL $name: $e"); e.printStackTrace() }
}

fun main() {
    for (operation in listOf("request", "query")) for (action in listOf("Stop", "Forget", "death")) {
        scenario("continuous actual OFF + scalar $operation keeps $action/status and release reachable while OFF held") {
            ComposedFixture().use { f ->
                f.old.blockOff = CountDownLatch(1)
                f.host.requestPanelOff {}
                check(f.old.offEntered.await(2, TimeUnit.SECONDS))
                // Query itself must wait on the same actual LiveScale monitor as a pending request.
                val live = f.old.field("scale") as LiveScale
                val monitor = if (operation == "query") thread(isDaemon = true) {
                    runCatching {
                        f.old.session.javaClass.getDeclaredMethod("requestScale", Long::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(f.old.session, 900L, 1.25, false)
                    }
                } else null
                if (monitor != null) eventually("actual request did not hold LiveScale behind OFF") {
                    (f.old.field("lastScaleReportAttempt") as ScaleState?)?.status == ScaleStatus.PENDING
                }
                val callbacks = CopyOnWriteArrayList<ScaleState?>()
                if (operation == "request") f.host.requestScale(1.25) { callbacks.add(it) }
                else f.host.scaleState { callbacks.add(it) }
                check(f.proxy.scaleEntered.await(2, TimeUnit.SECONDS))
                if (operation == "request") eventually("actual request did not publish PENDING before blocked stock write") {
                    (f.old.field("lastScaleReportAttempt") as ScaleState?)?.status == ScaleStatus.PENDING
                }
                val concurrentFailure = AtomicReference<Throwable?>()
                val concurrent = thread(isDaemon = true) {
                    try {
                        eventually("first close did not reach actual peer release") { "peer-close" in f.old.events }
                        f.old.close(SessionStopReason.HOST_STOP)
                        f.bridge.close()
                    } catch (e: Throwable) { concurrentFailure.set(e) }
                }
                try {
                    val reads = f.proxy.statusReads.get()
                    f.host.refresh()
                    var forgotten = 0
                    when (action) {
                        "Stop" -> f.model.stop()
                        "Forget" -> f.host.stopBeforeForget { forgotten++ }
                        "death" -> { f.proxy.die(); f.host.refresh() }
                    }
                    f.drain()
                    if (action == "death") {
                        check(field(f.host, "remote") == null && f.host.status().backend == "UNKNOWN")
                        f.bridge.close() // Binder death cannot run a dead process's close; separately exercise owned cleanup.
                    } else {
                        check(f.proxy.stopEntered.await(1, TimeUnit.SECONDS)) { "Stop starved behind scalar Binder" }
                        check(f.proxy.statusReads.get() > reads)
                        check(f.host.status().backend == "STOPPED")
                        if (action == "Forget") check(forgotten == 1)
                    }
                    concurrent.join(1000)
                    check(!concurrent.isAlive && concurrentFailure.get() == null) { "concurrent OwnedRelease remained blocked: ${concurrentFailure.get()}" }
                    check(f.old.socketClosed.get() && !f.old.child.isAlive)
                    val receipt = f.old.receipt()
                    check(receipt.contains("\"physicalObservation\":\"UNVERIFIED\""))
                    check(receipt.contains("\"restorationSubmission\":\"PENDING\""))
                    check(receipt.contains("\"finalAppliedScaleVerified\":false"))
                    check(callbacks.isEmpty())
                    check(f.old.blockOff!!.count == 1L)
                } finally { f.old.blockOff!!.countDown(); monitor?.join(1000); concurrent.join(1000) }
                f.drain(true)
                check(callbacks.size == 1 && callbacks.single() == null)
                check(f.proxy.rawReplies.all { it.size == 40 && ScaleState.from(it).bytes().contentEquals(it) })
                check(f.old.writes.none { it.firstOrNull()?.toInt() == 10 }) // Late old OFF/restore cannot write closed transport.
            }
        }
    }
    for (replaceOwner in listOf(true, false)) for (operation in listOf("request", "query")) {
        scenario("queued scalar $operation retains original context on same Binder fresh ${if (replaceOwner) "owner" else "media Session"}") {
            ComposedFixture().use { f ->
                PanelFixture(if (replaceOwner) "fresh-owner" else f.old.ownerEpoch).use { fresh ->
                    val freshBridge = if (replaceOwner) bridge(fresh) else f.bridge
                    try {
                        val original = f.old.state().context
                        val responses = CopyOnWriteArrayList<ScaleState?>()
                        f.proxy.scaleGate = CountDownLatch(1)
                        if (operation == "request") f.host.requestScale(1.25) { responses.add(it) }
                        else f.host.scaleState { responses.add(it) }
                        check(f.proxy.scaleEntered.await(2, TimeUnit.SECONDS))
                        if (operation == "request") f.host.requestScale(2.0) { responses.add(it) }
                        else f.host.scaleState { responses.add(it) }
                        f.drain() // Both calls have captured the original context before replacing it.
                        replace(f, fresh, freshBridge, replaceOwner)
                        f.host.refresh(); f.drain()
                        check(f.host.status().projectionToken == fresh.state().context.projectionToken)
                        f.proxy.scaleGate!!.countDown(); f.drain(true)
                        check(f.proxy.scaleContexts == listOf(original, original))
                        check(responses.size == 2 && responses.all { it == null })
                        check(f.proxy.rawReplies.all { it.size == 40 && ScaleState.from(it).status == ScaleStatus.UNAVAILABLE })
                        check(fresh.writes.isEmpty() && f.old.writes.isEmpty())
                        // The fresh actual route remains usable after rejecting the old queued calls.
                        (fresh.field("scale") as LiveScale).observe(ScrcpyRecord.Session(Geometry(480, 640), false))
                        var positive: ScaleState? = null
                        f.host.requestScale(1.25) { positive = it }; f.drain(true)
                        check(positive?.status == ScaleStatus.PENDING && fresh.writes.size == 1)
                        check(f.proxy.scaleContexts.last() == fresh.state().context)
                        f.host.scaleState { positive = it }; f.drain(true)
                        check(positive?.requested == 1.25 && positive?.observed == Geometry(480, 640))
                    } finally { f.proxy.scaleGate?.countDown(); if (replaceOwner) freshBridge.close() }
                }
            }
        }
        scenario("public scalar $operation captures context BEFORE first lifecycle hop across fresh ${if (replaceOwner) "owner" else "media Session"}") {
            ComposedFixture().use { f ->
                PanelFixture(if (replaceOwner) "fresh-owner" else f.old.ownerEpoch).use { fresh ->
                    val freshBridge = if (replaceOwner) bridge(fresh) else f.bridge
                    val entered = CountDownLatch(1); val gate = CountDownLatch(1)
                    try {
                        val lifecycle = field(f.host, "executor") as ScheduledExecutorService
                        lifecycle.execute {
                            entered.countDown(); gate.await()
                            replace(f, fresh, freshBridge, replaceOwner)
                            HostBootstrap::class.java.getDeclaredMethod("readStatus", String::class.java).apply { isAccessible = true }
                                .invoke(f.host, f.proxy.status())
                        }
                        check(entered.await(1, TimeUnit.SECONDS))
                        val responses = CopyOnWriteArrayList<ScaleState?>()
                        if (operation == "request") f.host.requestScale(1.25) { responses.add(it) }
                        else f.host.scaleState { responses.add(it) }
                        gate.countDown(); f.drain(true)
                        check(responses.size == 1 && responses.single() == null)
                        check(f.proxy.scaleContexts.isEmpty() && fresh.writes.isEmpty() && f.old.writes.isEmpty())
                    } finally { gate.countDown(); if (replaceOwner) freshBridge.close() }
                }
            }
        }
    }
    for (replaceOwner in listOf(true, false)) {
        scenario("actual scalar target captured before blocked stock write stays old across fresh ${if (replaceOwner) "owner" else "media Session"}") {
            ComposedFixture().use { f ->
                PanelFixture(if (replaceOwner) "fresh-owner" else f.old.ownerEpoch).use { fresh ->
                    val freshBridge = if (replaceOwner) bridge(fresh) else f.bridge
                    f.old.blockOff = CountDownLatch(1)
                    try {
                        f.host.requestPanelOff {}; check(f.old.offEntered.await(2, TimeUnit.SECONDS))
                        val responses = CopyOnWriteArrayList<ScaleState?>()
                        f.host.requestScale(1.25) { responses.add(it) }
                        eventually("actual scale request did not reach old control writer") {
                            (f.old.field("lastScaleReportAttempt") as ScaleState?)?.status == ScaleStatus.PENDING
                        }
                        replace(f, fresh, freshBridge, replaceOwner)
                        f.host.refresh(); f.drain()
                        f.old.close(SessionStopReason.HOST_STOP)
                        check(f.old.socketClosed.get() && !f.old.child.isAlive)
                        check(f.old.receipt().contains("\"restorationSubmission\":\"PENDING\""))
                        check(fresh.writes.isEmpty() && f.old.blockOff!!.count == 1L)
                        f.old.blockOff!!.countDown(); f.drain(true)
                        check(responses.size == 1 && responses.single() == null)
                        check(fresh.writes.isEmpty() && f.old.writes.isEmpty())
                    } finally { f.old.blockOff!!.countDown(); if (replaceOwner) freshBridge.close() }
                }
            }
        }
    }
    scenario("raw40 scalar ABI retains no-op/rejected/interrupted/observed semantics on actual Session") {
        ComposedFixture().use { f ->
            var response: ScaleState? = null
            f.host.requestScale(1.0) { response = it }; f.drain(true)
            check(response?.status == ScaleStatus.PENDING && f.old.writes.size == 1) // No-op still reaches stock.
            val live = f.old.field("scale") as LiveScale
            val phases = mutableListOf<String>()
            live.observe(ScrcpyRecord.Session(Geometry(480, 640), false), Any()) {
                check(live.state().status == ScaleStatus.INTERRUPTED)
                check((f.old.field("lastScaleReportAttempt") as ScaleState).status == ScaleStatus.PENDING)
                phases.add("before-publication")
            }
            check(phases == listOf("before-publication"))
            f.host.scaleState { response = it }; f.drain(true)
            check(response?.status == ScaleStatus.INTERRUPTED)
            f.host.requestScale(Double.NaN) { response = it }; f.drain(true)
            check(response?.status == ScaleStatus.REJECTED && response!!.requested.isNaN())
            f.host.scaleState { response = it }; f.drain(true)
            check(response?.status == ScaleStatus.INTERRUPTED) // Rejected reply is not an applied model mutation.
            f.host.requestScale(1.25) { response = it }; f.drain(true)
            live.observe(ScrcpyRecord.Session(Geometry(384, 512), true))
            f.host.scaleState { response = it }; f.drain(true)
            check(response?.status == ScaleStatus.OBSERVED && response?.clientResize == true)
            check(LayoutScale.applied(response!!.observed!!) == 1.25)
            check(f.proxy.rawReplies.all { it.size == 40 && ScaleState.from(it).bytes().contentEquals(it) })
        }
    }
    println("$passed composed scenarios passed; $failures failed; actual code with modeled Binder/resources, no physical claim")
    check(failures == 0)
}
