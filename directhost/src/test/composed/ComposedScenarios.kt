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
    val service = ShizukuHostService(Context(old.directory), OwnedShellSessions(old.directory.resolve("owned-shell")), null)
    val lifecycle = field(service, "lifecycle") as OwnedHostLifecycle
    @Suppress("UNCHECKED_CAST") val routes = field(service, "panelRoutes") as PanelSessionRoutes<Bridge>
    val owner = routes.begin(old.ownerEpoch)
    val runGate = CountDownLatch(1)
    val started = CountDownLatch(1)
    val proxy = BinderAdapter(service)
    val host: HostBootstrap
    val model: SessionController
    init {
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
            if (operations) for (name in listOf("displayExecutor", "panelExecutor", "panelQueryExecutor", "scaleExecutor")) {
                val worker = runCatching { field(host, name) as ExecutorService }.getOrNull()
                worker?.submit {}?.get(2, TimeUnit.SECONDS)
            }
        }
    }
    override fun close() {
        old.blockOff?.countDown(); proxy.scaleGate?.countDown(); runGate.countDown()
        bridge.close(); old.close()
        for (name in listOf("displayExecutor", "panelExecutor", "panelQueryExecutor", "scaleExecutor", "executor")) {
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
    override fun launchApp(requestId: Long, packageName: String, expectedOwnerEpoch: String, expectedProjectionToken: String) =
        service.launchApp(requestId, packageName, expectedOwnerEpoch, expectedProjectionToken)
    override fun projectionDisplayState(expectedOwnerEpoch: String, expectedProjectionToken: String) =
        service.projectionDisplayState(expectedOwnerEpoch, expectedProjectionToken)
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

/** Fixed external launch replies only; Session admission/cancellation/queue/close stay actual. */
private data class CrossLaunchCommand(val displayId: Int, val packageName: String, val home: Boolean)
private class CrossLaunchAdapter : ProjectionLaunchAdapter {
    val commands = CopyOnWriteArrayList<CrossLaunchCommand>()
    val foregroundReads = AtomicInteger()
    val closes = AtomicInteger()
    val closed = AtomicBoolean()
    override fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult {
        check(!closed.get()) { "Closed old launch helper was invoked" }
        commands.add(CrossLaunchCommand(displayId, packageName, home))
        return ProjectionCommandResult(0, "Status: ok\nActivity: $packageName/.Modeled\n")
    }
    override fun foreground(displayId: Int): String {
        check(!closed.get())
        foregroundReads.incrementAndGet()
        return "example.cross.observed"
    }
    override fun close() { closes.incrementAndGet(); closed.set(true) }
}
private fun crossLaunch(fixture: PanelFixture, displayId: Int): Pair<ProjectedAppLaunch, CrossLaunchAdapter> {
    val launches = fixture.field("launches") as ProjectedAppLaunch
    val adapter = CrossLaunchAdapter()
    set(launches, "adapter", adapter)
    launches.initialize(displayId)
    val ready = launches.snapshot(observe = true)
    check(ready.displayId == displayId && ready.status == ProjectionLaunchStatus.STARTED)
    check(adapter.commands.single() == CrossLaunchCommand(displayId, ProjectionLaunchCommands.HOME_PACKAGE, true))
    return launches to adapter
}
private fun launchCancellationWaitsOn(fixture: PanelFixture): Boolean {
    val lockIdentity = System.identityHashCode(fixture.field("controlLock"))
    return java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(false, false).any { info ->
        info.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == lockIdentity &&
            info.stackTrace.any { it.className == "com.code2hack.eyeprojector.directhost.ProjectedAppLaunch" && it.methodName == "execute" }
    }
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
    scenario("cross-feature launch/query and panel replies share one actual Session owner/token") {
        ComposedFixture().use { f ->
            val (launches, adapter) = crossLaunch(f.old, 47)
            val expected = f.host.status().projectionContext
            val panelContext = f.old.state().context
            check(expected.available && expected.ownerEpoch == panelContext.ownerEpoch && expected.projectionToken == panelContext.projectionToken)
            check(launches.snapshot().context == expected && f.bridge.projectionContext() == expected)
            var launch: ProjectionDisplayState? = null
            f.host.launchApp("example.cross.accepted") { launch = it }; f.drain(true)
            check(launch?.status == ProjectionLaunchStatus.STARTED && launch?.context == expected && launch?.displayId == 47)
            check(launch?.requestedPackage == "example.cross.accepted" && launch?.commandExit == 0)
            var query: ProjectionDisplayState? = null
            f.host.projectionDisplayState { query = it }; f.drain(true)
            check(query?.context == expected && query?.displayId == 47 && query?.foregroundPackage == "example.cross.observed")
            var panel: PanelPowerState? = null
            f.host.requestPanelOff { panel = it }; f.drain(true)
            check(panel?.context == panelContext && panel?.off == PanelSubmission.SUBMITTED)
            check(panel?.observation == PanelObservation.UNVERIFIED)
            f.host.panelState { panel = it }; f.drain(true)
            check(panel?.context == panelContext && panel?.restoration == PanelRestoration.PENDING)
            check(adapter.commands == listOf(
                CrossLaunchCommand(47, ProjectionLaunchCommands.HOME_PACKAGE, true),
                CrossLaunchCommand(47, "example.cross.accepted", false)))
        }
    }
    scenario("cross-feature OFF plus scalar plus actual launch cancellation keeps Stop/release reachable and old work old") {
        ComposedFixture().use { f ->
            val (launches, oldAdapter) = crossLaunch(f.old, 47)
            val original = f.host.status().projectionContext
            val originalPanel = f.old.state().context
            check(launches.snapshot().context == original && original.projectionToken == originalPanel.projectionToken)
            f.old.held()
            val oldWritesBefore = f.old.writes.size
            f.old.blockOff = CountDownLatch(1)
            val offReplies = CopyOnWriteArrayList<PanelPowerState?>()
            val scaleReplies = CopyOnWriteArrayList<ScaleState?>()
            val launchReplies = CopyOnWriteArrayList<ProjectionDisplayState?>()
            f.host.requestPanelOff { offReplies.add(it) }
            check(f.old.offEntered.await(2, TimeUnit.SECONDS))
            f.host.requestScale(1.25) { scaleReplies.add(it) }
            check(f.proxy.scaleEntered.await(2, TimeUnit.SECONDS))
            eventually("actual scale did not publish PENDING before waiting on OFF") {
                (f.old.field("lastScaleReportAttempt") as ScaleState?)?.status == ScaleStatus.PENDING
            }
            f.host.launchApp("example.cross.old.pending") { launchReplies.add(it) }
            eventually("real launch cancellation did not wait on this Session controlLock") { launchCancellationWaitsOn(f.old) }
            val concurrentFailure = AtomicReference<Throwable?>()
            val concurrent = thread(isDaemon = true) {
                try {
                    eventually("first close did not reach actual peer release") { "peer-close" in f.old.events }
                    f.old.close(SessionStopReason.HOST_STOP)
                    f.bridge.close()
                } catch (e: Throwable) { concurrentFailure.set(e) }
            }
            try {
                var pending: PanelPowerState? = null
                f.host.panelState { pending = it }
                f.drain()
                (field(f.host, "panelQueryExecutor") as ExecutorService).submit {}.get(1, TimeUnit.SECONDS)
                f.drain()
                check(pending?.context == originalPanel && pending?.off == PanelSubmission.PENDING)
                val reads = f.proxy.statusReads.get()
                f.host.refresh(); f.model.stop()
                check(f.proxy.stopEntered.await(1, TimeUnit.SECONDS)) { "Stop starved behind actual OFF/scale/launch composition" }
                f.drain()
                concurrent.join(1000)
                check(!concurrent.isAlive && concurrentFailure.get() == null)
                check(f.host.status().backend == "STOPPED" && f.proxy.statusReads.get() > reads)
                check(f.old.socketClosed.get() && !f.old.child.isAlive)
                check(oldAdapter.closed.get() && oldAdapter.closes.get() == 1)
                check(launches.snapshot().status == ProjectionLaunchStatus.CLOSED)
                val receipt = f.old.receipt()
                check(receipt.contains("\"launchHelperReleased\":true"))
                check(receipt.contains("\"physicalObservation\":\"UNVERIFIED\""))
                check(receipt.contains("\"restorationSubmission\":\"PENDING\""))
                check(receipt.contains("\"scaleSnapshotBasis\":\"LAST_KNOWN_REPORT_ATTEMPT\""))
                check(receipt.contains("\"scaleStatus\":\"PENDING\""))
                check(receipt.contains("\"finalAppliedScaleVerified\":false"))
                check(offReplies.isEmpty() && scaleReplies.isEmpty() && launchReplies.isEmpty())
                check(f.old.blockOff!!.count == 1L && f.old.writes.size == oldWritesBefore)
                // Publish a distinct modeled accepted Owner/Bridge route using the existing fixture
                // mechanism. This is typed-operation routing proof, not a new Android Start claim.
                PanelFixture("cross-fresh-owner").use { fresh ->
                    val freshBridge = bridge(fresh)
                    val (_, freshAdapter) = crossLaunch(fresh, 88)
                    try {
                        replace(f, fresh, freshBridge, true)
                        val freshContext = freshBridge.projectionContext()
                        check(freshContext.available && freshContext.projectionToken != original.projectionToken)
                        val positive = ProjectionDisplayState.from(f.service.launchApp(800, "example.cross.fresh", freshContext.ownerEpoch, freshContext.projectionToken))
                        check(positive.context == freshContext && positive.status == ProjectionLaunchStatus.STARTED && positive.displayId == 88)
                        val freshCommands = freshAdapter.commands.toList()
                        f.old.blockOff!!.countDown(); f.drain(true)
                        check(offReplies.size == 1 && offReplies.single() == null)
                        check(scaleReplies.size == 1 && scaleReplies.single() == null)
                        check(launchReplies.size == 1 && launchReplies.single() == null)
                        check(f.old.writes.size == oldWritesBefore && fresh.writes.isEmpty())
                        check(oldAdapter.commands == listOf(CrossLaunchCommand(47, ProjectionLaunchCommands.HOME_PACKAGE, true)))
                        check(freshAdapter.commands == freshCommands && freshAdapter.commands.none { it.packageName == "example.cross.old.pending" })
                        check(f.routes.current()?.target === freshBridge)
                    } finally { freshBridge.close() }
                }
            } finally { f.old.blockOff!!.countDown(); concurrent.join(1000) }
        }
    }
    println("$passed composed scenarios passed; $failures failed; actual code with modeled Binder/resources, no physical claim")
    check(failures == 0)
}
