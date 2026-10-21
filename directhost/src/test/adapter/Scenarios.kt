package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.protocol.*
import rikka.shizuku.Shizuku
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Compiles and drives the actual HostBootstrap; doubles supply external API events. */
private class Fixture : AutoCloseable {
    val directory: File = Files.createTempDirectory("eyeprojector-adapter-test-").toFile()
    val host: HostBootstrap
    init {
        Shizuku.reset()
        directory.resolve("session").mkdirs()
        for (name in listOf("identity.p12", "password", "peer.crt", "endpoint.properties"))
            directory.resolve("session/$name").writeText("synthetic fixture")
        val constructor = HostBootstrap::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        host = constructor.newInstance(Context(directory))
        drain()
    }
    fun field(name: String): Any? = HostBootstrap::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host)
    fun drain(includePanelOperations: Boolean = true) {
        // A callback may enqueue more work behind the first barrier.
        repeat(3) {
            (field("executor") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS)
            if (includePanelOperations) for (name in listOf("displayExecutor", "panelExecutor", "panelQueryExecutor", "scaleExecutor")) {
                (field(name) as java.util.concurrent.ExecutorService).submit {}.get(3, TimeUnit.SECONDS)
            }
        }
    }
    fun lifecycle() { repeat(3) { (field("executor") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS) } }
    fun attach(service: FakeService) {
        Shizuku.connection!!.onServiceConnected(Shizuku.args!!.component, service); drain()
    }
    fun expireAttachment() {
        HostBootstrap::class.java.getDeclaredField("bindStarted").apply { isAccessible = true }
            .setLong(host, System.nanoTime() - TimeUnit.SECONDS.toNanos(35))
    }
    fun backend() = (field("current") as HostBootstrap.Status).backend
    override fun close() {
        for (name in listOf("displayExecutor", "panelExecutor", "panelQueryExecutor", "scaleExecutor")) {
            val operations = field(name) as java.util.concurrent.ExecutorService
            operations.shutdownNow(); operations.awaitTermination(3, TimeUnit.SECONDS)
        }
        val executor = field("executor") as ScheduledExecutorService
        executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        directory.deleteRecursively()
    }
}

private var failures = 0
private var scenarios = 0
private fun scenario(name: String, run: (Fixture) -> Unit) {
    scenarios++
    try {
        Fixture().use(run)
        println("PASS $name")
    } catch (error: Throwable) {
        failures++
        println("FAIL $name: $error")
        error.printStackTrace()
    }
}

fun main() {
    scenario("R5-01 normal Stop survives delayed construction and waits for revocation/death") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { stopReady = false; exitOnDestroy = false }
        f.attach(service)
        f.host.stop(); f.drain()
        check(service.stops >= 1 && !service.revoked && service.destroys == 0)
        check(f.backend() != "STOPPED")
        // The owned construction finishes later, without a second user Stop.
        service.state = "STOPPED"; service.stopReady = true
        f.host.refresh(); f.drain()
        check(service.stops >= 2 && service.revoked && service.destroys == 1)
        check(f.backend() == "STOPPING") // Process is still alive despite closed bridge.
        service.die(); f.drain()
        check(f.backend() == "STOPPED")
        f.host.start(); f.drain()
        check(Shizuku.binds >= 2) // Fresh Start is available after verified completion.
    }
    scenario("R5-01 unavailable Stop stays pending through setup restoration") { f ->
        Shizuku.available = false
        f.host.stop(); f.drain()
        check(f.backend() != "STOPPED")
        Shizuku.available = true
        f.host.refresh(); f.drain()
        val service = FakeService(); f.attach(service)
        check(service.stops >= 1 && service.revoked && service.destroys == 1)
        check(f.backend() == "STOPPED")
    }
    scenario("R5-02 lost bind callback recovers and ignores late abandoned callbacks") { f ->
        f.host.start(); f.drain()
        val abandoned = Shizuku.boundConnections.single()
        f.expireAttachment(); f.host.refresh(); f.drain()
        check(Shizuku.binds >= 2 && Shizuku.detaches >= 1)
        f.host.stop(); f.drain()
        val late = FakeService()
        abandoned.onServiceConnected(Shizuku.args!!.component, late); f.drain()
        check(late.starts == 0 && late.stops == 0 && late.statusReads == 0)
        val current = FakeService(); f.attach(current)
        check(current.starts == 0 && current.stops >= 1 && current.destroys == 1)
        check(f.backend() == "STOPPED")
    }
    scenario("R5-03 stale peek revision cannot execute old loaded code") { f ->
        val old = FakeService()
        Shizuku.peekVersion = 122; Shizuku.peekService = old
        f.host.refresh(); f.drain()
        check(Shizuku.args!!.revision == 123)
        check(Shizuku.binds >= 1)
        check(old.starts == 0 && old.stops == 0 && old.statusReads == 0)
        f.host.start(); f.drain()
        val replacement = FakeService(); f.attach(replacement)
        check(replacement.starts == 1 && old.starts == 0)
    }
    scenario("matching revision reattaches without starting projection") { f ->
        val service = FakeService()
        Shizuku.peekVersion = 123; Shizuku.peekService = service
        f.host.refresh(); f.drain()
        check(Shizuku.binds == 0 && service.starts == 0 && service.statusReads >= 1)
        check(f.backend() == "READY")
    }
    scenario("Forget completes only after revocation and exact process death") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { stopReady = false; exitOnDestroy = false }
        f.attach(service)
        var completions = 0
        f.host.stopBeforeForget { completions++ }; f.drain()
        check(completions == 0)
        f.host.start(); f.drain()
        check(service.starts == 1) // Start cannot replace the in-progress Stop.
        service.stopReady = true
        f.host.refresh(); f.drain()
        check(service.revoked && service.destroys == 1 && completions == 0)
        service.die(); f.drain()
        check(completions == 1 && f.backend() == "STOPPED")
        f.host.refresh(); f.drain()
        check(completions == 1)
    }
    scenario("scale without an attached service reports no value and never starts projection") { f ->
        var replies = 0
        f.host.requestScale(1.25) { check(it == null); replies++ }
        f.host.scaleState { check(it == null); replies++ }
        f.drain()
        check(replies == 2 && Shizuku.binds == 0)
    }
    scenario("scalar without actual projection context stays unavailable and never dispatches") { f ->
        f.host.start(); f.drain()
        val service = FakeService(); f.attach(service)
        var replies = 0
        f.host.requestScale(1.25) { check(it == null); replies++ }
        f.host.scaleState { check(it == null); replies++ }
        f.drain()
        check(replies == 2 && service.scaleRequests.isEmpty() && service.scaleReads == 0)
    }
    scenario("typed local scale preserves observed feedback and exposes unavailable transport honestly") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { state = "STREAMING" }; f.attach(service)
        var response: ScaleState? = null
        f.host.requestScale(1.25) { response = it }; f.drain()
        check(service.scaleRequests.size == 1)
        check(response!!.requestId == service.scaleRequests.single().first)
        check(response!!.requested == 1.25 && response!!.status == ScaleStatus.PENDING)
        check(LayoutScale.applied(response!!.observed!!) == 1.0)
        service.currentScale = service.currentScale.copy(observed = Geometry(384, 512), status = ScaleStatus.OBSERVED, clientResize = true)
        f.host.scaleState { response = it }; f.drain()
        check(response!!.requested == 1.25 && LayoutScale.applied(response!!.observed!!) == 1.25)
        service.scalePayloadOverride = ByteArray(7)
        f.host.scaleState { response = it }; f.drain()
        check(response == null) // Decode failure must not become a fabricated 1.0x observation.
        service.scalePayloadOverride = null
        service.die(); f.drain()
        f.host.requestScale(2.0) { response = it }; f.drain()
        check(response == null && service.scaleRequests.size == 1)
    }
    scenario("Stop and fresh attachment cannot reuse the preceding session scale") { f ->
        f.host.start(); f.drain()
        val first = FakeService().apply {
            state = "STREAMING"
            currentScale = currentScale.copy(observed = Geometry(384, 512), requested = 1.25, status = ScaleStatus.OBSERVED)
        }
        f.attach(first)
        var response: ScaleState? = null
        f.host.scaleState { response = it }; f.drain()
        check(LayoutScale.applied(response!!.observed!!) == 1.25)
        f.host.stop(); f.drain()
        check(first.revoked && first.destroys == 1 && f.backend() == "STOPPED")
        f.host.scaleState { response = it }; f.drain()
        check(response == null)
        f.host.start(); f.drain()
        val second = FakeService().apply { state = "STREAMING" }; f.attach(second)
        f.host.scaleState { response = it }; f.drain()
        check(response!!.status == ScaleStatus.READY && LayoutScale.applied(response!!.observed!!) == 1.0)
        check(second.starts == 1 && first.starts == 1 && second.scaleRequests.isEmpty())
    }
    scenario("session epoch survives async bind, actual status and verified Stop") { f ->
        f.host.start("source8-first"); f.drain()
        check(f.host.status().sessionEpoch == "source8-first")
        val service = FakeService(); f.attach(service)
        check(service.sessionEpoch == "source8-first")
        check(f.host.status().sessionEpoch == "source8-first")
        service.state = "SESSION_STARTING"; f.host.refresh(); f.drain()
        check(f.host.status().backend == "SESSION_STARTING")
        service.state = "STREAMING"; f.host.refresh(); f.drain()
        check(f.host.status().backend == "STREAMING")
        f.host.stop(); f.drain()
        check(f.host.status().backend == "STOPPED" && f.host.status().sessionEpoch == "source8-first")
        f.host.start("source8-fresh"); f.drain()
        val fresh = FakeService(); f.attach(fresh)
        check(fresh.sessionEpoch == "source8-fresh" && service.starts == 1)
    }
    scenario("Stop during pending Start retains epoch even if backend was never created") { f ->
        f.host.start("source8-canceled"); f.drain()
        f.host.stop(); f.drain()
        val service = FakeService(); f.attach(service)
        check(service.starts == 0 && service.stops == 1)
        check(f.host.status().backend == "STOPPED" && f.host.status().sessionEpoch == "source8-canceled")
    }
    scenario("async setup rejection is correlated to the requested epoch without creating a backend") { f ->
        Shizuku.available = false
        f.host.start("source8-rejected"); f.drain()
        check(f.host.status().rejectedEpoch == "source8-rejected")
        check(f.host.status().detail == "START_SETUP_UNAVAILABLE" && Shizuku.binds == 0)
    }
    scenario("R8-04 actual main observer controls terminal atomic-store failure and retries") { f ->
        val stateDirectory = f.directory.resolve("control-state").apply { mkdir() }
        val savedDirectory = f.directory.resolve("preserved-control-state")
        val store = SessionAttemptStore(stateDirectory.resolve("attempts.properties"))
        val model = SessionController({ epoch -> f.host.start(epoch) }, { f.host.stop() },
            initialPhase = SessionPhase.UNKNOWN, retainCorrelation = store::write)
        var observerFailure: Throwable? = null
        var terminalReturned = false
        f.host.observe { value ->
            try {
                val combined = model.javaClass.methods.singleOrNull { it.name == "observeBackend" && it.parameterCount == 4 }
                if (combined != null) combined.invoke(model, value.sessionEpoch, value.backend, value.detail.orEmpty(), value.rejectedEpoch)
                else {
                    value.rejectedEpoch?.let { model.rejected(it, value.detail ?: "START_REJECTED") }
                    model.restore(value.sessionEpoch, value.backend)
                    model.backend(value.sessionEpoch, value.backend, value.detail.orEmpty())
                    if (value.backend == "FAILED" || value.detail == "SESSION_SETUP_REQUIRED")
                        model.backend(value.sessionEpoch, "FAILED", value.detail.orEmpty())
                }
                if (value.backend == "STOPPED") terminalReturned = true
            } catch (error: Throwable) { observerFailure = error; throw error }
        }
        // Resolve the initial absent service, then use actual bootstrap dispatch and service callbacks.
        f.host.stop(); f.drain()
        val absent = FakeService(); f.attach(absent)
        check(f.backend() == "STOPPED")
        val owner = model.start().epoch; f.drain()
        val service = FakeService().apply { stopReady = false; exitOnDestroy = false }
        f.attach(service)
        check(model.state().phase == SessionPhase.AVAILABLE)
        model.stop(owner); f.drain()
        terminalReturned = false
        Files.move(stateDirectory.toPath(), savedDirectory.toPath())
        stateDirectory.writeText("Synthetic blocked atomic-store parent")
        service.stopReady = true; service.state = "STOPPED"
        f.host.refresh(); f.drain(); service.die(); f.drain()
        check(observerFailure == null) { "Atomic retention error escaped actual main observer: $observerFailure" }
        check(terminalReturned && f.backend() == "STOPPED")
        check(model.state().phase == SessionPhase.INACTIVE)
        check(model.state().detail == "SESSION_CONTROL_STATE_UNAVAILABLE")
        check(service.starts == 1)
        stateDirectory.delete(); Files.move(savedDirectory.toPath(), stateDirectory.toPath())
        check(model.state().detail.isEmpty())
        check(store.read().owner == null)
        model.start(); f.drain()
        val fresh = FakeService(); f.attach(fresh)
        check(fresh.starts == 1 && model.state().phase == SessionPhase.AVAILABLE)
    }
    scenario("panel without attachment reports unknown transport and never starts projection") { f ->
        var replies = 0
        f.host.requestPanelOff { check(it == null); replies++ }
        f.host.panelState { check(it == null); replies++ }
        f.drain()
        check(replies == 2 && Shizuku.binds == 0)
    }
    scenario("panel adapter retains submission versus observation and malformed/dead transport") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { state = "STREAMING" }; f.attach(service)
        var result: PanelPowerState? = null
        f.host.requestPanelOff { result = it }; f.drain()
        check(result!!.requestId == service.panelRequests.single())
        check(result!!.off == PanelSubmission.SUBMITTED && result!!.restoration == PanelRestoration.PENDING)
        check(result!!.observation == PanelObservation.UNVERIFIED)
        service.currentPanel = service.currentPanel.copy(restoration = PanelRestoration.FAILED, sessionActive = false)
        f.host.panelState { result = it }; f.drain()
        check(result!!.restoration == PanelRestoration.FAILED && !result!!.sessionActive)
        service.panelPayloadOverride = ByteArray(7)
        f.host.panelState { result = it }; f.drain()
        check(result == null)
        service.panelPayloadOverride = null; service.die(); f.drain()
        f.host.requestPanelOff { result = it }; f.drain()
        check(result == null && service.panelRequests.size == 1)
    }
    scenario("fresh panel attachment cannot inherit preceding session submission") { f ->
        f.host.start(); f.drain()
        val first = FakeService().apply { state = "STREAMING" }; f.attach(first)
        f.host.requestPanelOff {}; f.drain()
        f.host.stop(); f.drain()
        check(first.revoked && first.destroys == 1 && f.backend() == "STOPPED")
        var result: PanelPowerState? = null
        f.host.panelState { result = it }; f.drain()
        check(result == null)
        f.host.start(); f.drain()
        val fresh = FakeService().apply { state = "STREAMING" }; f.attach(fresh)
        f.host.panelState { result = it }; f.drain()
        check(result!!.off == PanelSubmission.NOT_REQUESTED && fresh.panelRequests.isEmpty())
    }
    scenario("R18-1 actual SessionController Stop/status/death remain runnable during blocked panel Binder") { f ->
        val model = SessionController({ epoch -> f.host.start(epoch) }, { f.host.stop() })
        f.host.observe { model.observeBackend(it.sessionEpoch, it.backend, it.detail.orEmpty(), it.rejectedEpoch) }
        val owner = model.start().epoch; f.drain()
        val service = FakeService().apply { state = "STREAMING"; exitOnDestroy = false; panelGate = java.util.concurrent.CountDownLatch(1) }
        f.attach(service); model.receiverRendered(owner)
        check(model.state().phase == SessionPhase.STREAMING)
        var replies = 0
        f.host.requestPanelOff { check(it == null); replies++ }
        check(service.panelEntered.await(2, TimeUnit.SECONDS))
        try {
            val reads = service.statusReads
            f.host.refresh(); f.drain(false)
            check(service.statusReads > reads)
            var pending: PanelPowerState? = null
            f.host.panelState { pending = it }
            f.drain(false) // Ensure the lifecycle capture has actually queued the query before its barrier.
            (f.field("panelQueryExecutor") as java.util.concurrent.ExecutorService).submit {}.get(1, TimeUnit.SECONDS)
            f.drain(false)
            check(pending?.context == service.currentPanel.context)
            model.stop()
            check(service.stopEntered.await(1, TimeUnit.SECONDS)) { "R18-1 Stop blocked behind panel Binder" }
            f.drain(false)
            check(f.backend() == "STOPPING")
            service.die(); f.drain(false)
            check(model.state().phase == SessionPhase.INACTIVE && f.backend() == "STOPPED")
            check(replies == 0)
        } finally { service.panelGate!!.countDown() }
        f.drain(); check(replies == 1)
    }
    scenario("R18-1 blocked panel Binder cannot delay Forget revocation/death completion") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { state = "STREAMING"; exitOnDestroy = false; panelGate = java.util.concurrent.CountDownLatch(1) }
        f.attach(service)
        f.host.requestPanelOff {}
        check(service.panelEntered.await(2, TimeUnit.SECONDS))
        var completions = 0
        try {
            f.host.stopBeforeForget { completions++ }
            check(service.stopEntered.await(1, TimeUnit.SECONDS))
            f.drain(false); check(completions == 0 && service.revoked)
            service.die(); f.drain(false)
            check(completions == 1 && f.backend() == "STOPPED")
        } finally { service.panelGate!!.countDown() }
        f.drain(); check(completions == 1)
    }
    for (replaceOwner in listOf(true, false)) {
        scenario("queued OFF retains context across same Binder fresh ${if (replaceOwner) "owner" else "media Session"}") { f ->
            f.host.start(); f.drain()
            val service = FakeService().apply { state = "STREAMING"; panelGate = java.util.concurrent.CountDownLatch(1) }
            f.attach(service)
            val original = service.currentPanel.context
            val replies = java.util.concurrent.CopyOnWriteArrayList<PanelPowerState?>()
            f.host.requestPanelOff { replies.add(it) }
            check(service.panelEntered.await(2, TimeUnit.SECONDS))
            try {
                f.host.requestPanelOff { replies.add(it) }; f.drain(false)
                service.replaceProjection(if (replaceOwner) "fresh-owner" else original.ownerEpoch)
                val fresh = service.currentPanel.context
                check(original.projectionToken != fresh.projectionToken)
                f.host.refresh(); f.drain(false)
                var query: PanelPowerState? = null
                f.host.panelState { query = it }
                f.drain(false) // Ensure the lifecycle capture has actually queued the query before its barrier.
            (f.field("panelQueryExecutor") as java.util.concurrent.ExecutorService).submit {}.get(1, TimeUnit.SECONDS)
                f.drain(false)
                check(query!!.context == fresh)
            } finally { service.panelGate!!.countDown() }
            f.drain()
            check(service.panelContexts == listOf(original, original))
            check(service.panelWrites.isEmpty())
            check(replies.size == 2 && replies.all { it == null })
        }
    }
    scenario("unresolved projection context never dispatches delayed OFF or query") { f ->
        f.host.start(); f.drain()
        val service = FakeService(); f.attach(service)
        var responses = 0
        f.host.requestPanelOff { check(it?.off == PanelSubmission.UNAVAILABLE); responses++ }
        f.host.panelState { check(it?.off == PanelSubmission.UNAVAILABLE); responses++ }
        f.drain()
        check(responses == 2 && service.panelRequests.isEmpty() && service.panelReads == 0)
    }
    scenario("blocked panel query leaves lifecycle Stop/status reachable") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { state = "STREAMING"; panelQueryGate = java.util.concurrent.CountDownLatch(1) }
        f.attach(service); f.host.panelState {}
        check(service.queryEntered.await(2, TimeUnit.SECONDS))
        try {
            val reads = service.statusReads
            f.host.refresh(); f.host.stop()
            check(service.stopEntered.await(1, TimeUnit.SECONDS))
            f.drain(false); check(service.statusReads > reads && f.backend() == "STOPPED")
        } finally { service.panelQueryGate!!.countDown() }
        f.drain()
    }
    scenario("public OFF intent captures old context before its first lifecycle hop") { f ->
        f.host.start(); f.drain()
        val service = FakeService().apply { state = "STREAMING" }; f.attach(service)
        val original = service.currentPanel.context
        val gate = java.util.concurrent.CountDownLatch(1); val entered = java.util.concurrent.CountDownLatch(1)
        val executor = f.field("executor") as ScheduledExecutorService
        executor.execute {
            entered.countDown(); gate.await()
            service.replaceProjection()
            HostBootstrap::class.java.getDeclaredMethod("readStatus", String::class.java).apply { isAccessible = true }
                .invoke(f.host, service.status())
        }
        check(entered.await(1, TimeUnit.SECONDS))
        var response: PanelPowerState? = null
        try { f.host.requestPanelOff { response = it } }
        finally { gate.countDown() }
        f.drain()
        check(response!!.context == original && response!!.off == PanelSubmission.UNAVAILABLE)
        check(service.currentPanel.context != original && service.panelRequests.isEmpty())
    }
    scenario("launch/display without attachment does not start or bind a backend") { f ->
        var replies = 0
        f.host.launchApp("example.app") { check(it == null); replies++ }
        f.host.projectionDisplayState { check(it == null); replies++ }
        f.drain()
        check(replies == 2 && Shizuku.binds == 0)
    }
    scenario("actual launch adapter preserves generation/results and rejects malformed or dead-service replies") { f ->
        f.host.start(); f.drain()
        val first = FakeService().apply { state = "STREAMING" }; f.attach(first)
        var response: ProjectionDisplayState? = null
        f.host.launchApp("example.phone") { response = it }; f.drain()
        check(first.launches == listOf("example.phone"))
        check(response!!.displayId == 47 && response!!.generation == "fixture:child")
        check(response!!.requestedPackage == "example.phone" && response!!.foregroundPackage == "example.phone")
        first.displayPayloadOverride = ByteArray(4)
        f.host.projectionDisplayState { response = it }; f.drain(); check(response == null)
        first.displayPayloadOverride = null
        f.host.stop(); f.drain()
        f.host.projectionDisplayState { response = it }; f.drain(); check(response == null)
        f.host.start(); f.drain()
        val fresh = FakeService().apply { state = "STREAMING"; display = display.copy(generation = "fresh:child", displayId = 88) }
        f.attach(fresh)
        f.host.projectionDisplayState { response = it }; f.drain()
        check(response!!.generation == "fresh:child" && response!!.displayId == 88 && response!!.foregroundPackage == null)
    }
    scenario("long Binder launch does not block Stop and late reply cannot cross revoked binding") { f ->
        f.host.start(); f.drain()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val service = FakeService().apply { state = "STREAMING"; beforeLaunch = { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) } }
        f.attach(service)
        var replies = 0
        try {
            f.host.launchApp("example.slow") { check(it == null); replies++ }
            check(entered.await(2, TimeUnit.SECONDS))
            f.host.stop()
            repeat(3) { (f.field("executor") as ScheduledExecutorService).submit {}.get(2, TimeUnit.SECONDS) }
            check(service.stops > 0 && service.destroys == 1 && f.backend() == "STOPPED")
            finish.countDown(); f.drain()
            check(replies == 1 && service.launches.isEmpty())
        } finally { finish.countDown() }
    }
    scenario("R1 actual controller normal end and same-live-Binder restart cannot retarget queued launch/query") { f ->
        val controller = SessionController({ f.host.start(it) }, { f.host.stop() }, initialPhase = SessionPhase.UNKNOWN)
        f.host.observe { s -> controller.observeBackend(s.sessionEpoch,s.backend,s.detail.orEmpty(),s.rejectedEpoch) }
        val oldOwner = controller.start().epoch; f.drain()
        val service = FakeService().apply { state = "STREAMING" }; f.attach(service)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val gate = (f.field("displayExecutor") as ExecutorService).submit { entered.countDown(); check(release.await(4,TimeUnit.SECONDS)) }
        check(entered.await(2,TimeUnit.SECONDS))
        var oldReplies = 0
        try {
            f.host.launchApp("example.old.pending") { check(it==null);oldReplies++ }
            f.host.projectionDisplayState { check(it==null);oldReplies++ }
            f.lifecycle()
            service.state = "STOPPED";f.host.refresh();f.lifecycle()
            check(controller.state().phase==SessionPhase.INACTIVE)
            service.state = "READY"
            val freshOwner = controller.start().epoch;f.lifecycle()
            check(oldOwner!=freshOwner && service.starts==2 && service.stops==0 && service.destroys==0 && service.isBinderAlive)
            service.display = service.display.copy(generation="replacement:child",displayId=88,context=ProjectionContext("","replacement-token"))
            service.state="STREAMING";f.host.refresh();f.lifecycle()
            release.countDown();gate.get(2,TimeUnit.SECONDS);f.drain()
            check(oldReplies==2 && service.launches.isEmpty() && service.displayReads==0)
            var positive: ProjectionDisplayState?=null
            f.host.launchApp("example.fresh") { positive=it };f.drain()
            check(service.launches==listOf("example.fresh") && positive!!.displayId==88 && positive!!.context==service.context)
        }finally{release.countDown()}
    }
    scenario("R1 same owner replacement media token rejects old queued work without replacement observation") { f ->
        f.host.start("same-owner");f.drain()
        val service=FakeService().apply{state="STREAMING"};f.attach(service)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        (f.field("displayExecutor") as ExecutorService).submit { entered.countDown();check(release.await(4,TimeUnit.SECONDS)) }
        check(entered.await(2,TimeUnit.SECONDS))
        try {
            f.host.launchApp("example.old.media") { check(it==null) }
            f.host.projectionDisplayState { check(it==null) }
            service.display=service.display.copy(generation="different-generation",displayId=88,context=ProjectionContext("","fresh-media-token"))
            f.host.refresh();f.lifecycle();release.countDown();f.drain()
            check(service.launches.isEmpty() && service.displayReads==0)
            f.host.projectionDisplayState { check(it!!.context==service.context && it.displayId==88) };f.drain()
            check(service.displayReads==1)
        }finally{release.countDown()}
    }
    scenario("R1 first lifecycle hop cannot replace an intent context captured at API entry") { f ->
        f.host.start("first-owner");f.drain()
        val service=FakeService().apply{state="STREAMING"};f.attach(service)
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        (f.field("executor") as ExecutorService).submit { entered.countDown();check(release.await(4,TimeUnit.SECONDS)) }
        (f.field("displayExecutor") as ExecutorService).submit { entered.countDown();check(release.await(4,TimeUnit.SECONDS)) }
        check(entered.await(2,TimeUnit.SECONDS))
        try {
            service.sessionEpoch="second-owner";service.display=service.display.copy(context=ProjectionContext("","second-token"))
            f.host.refresh() // Queued ahead of the old implementation's first dispatch hop.
            f.host.launchApp("example.first.hop.old") { check(it==null || it.status==ProjectionLaunchStatus.UNAVAILABLE) }
            release.countDown();f.drain()
            check(service.launches.isEmpty())
        }finally{release.countDown()}
    }
    scenario("R1 replacement after client precheck is rejected by correlated server operation") { f ->
        f.host.start("owner-a");f.drain()
        val service=FakeService().apply{state="STREAMING"};f.attach(service)
        val original=f.host.status().projectionContext
        service.beforeLaunch={ service.sessionEpoch="owner-b";service.display=service.display.copy(context=ProjectionContext("","token-b"),displayId=88) }
        var response: ProjectionDisplayState?=null
        f.host.launchApp("example.precheck.race") { response=it };f.drain()
        check(service.launches.isEmpty() && response!!.status==ProjectionLaunchStatus.UNAVAILABLE)
        check(response!!.context==original && response!!.commandExit==null && response!!.foregroundPackage==null)
        f.host.projectionDisplayState { check(it!!.context==original && it.foregroundPackage==null) };f.drain()
        check(service.displayReads==0)
    }
    scenario("R1 actual Home Activity retains original intent context across new intent and late callback") { f ->
        HomeContextScenarios.run(f.host, { f.drain() }, f::lifecycle, f::field, f::attach)
    }
    scenario("R1 fixed Home context works before status poll without inventing a Phone target or rebinding") { f ->
        f.host.start("home-first-poll");f.drain()
        val service=FakeService();f.attach(service)
        check(!f.host.status().projectionContext.available)
        f.host.launchApp("example.no.actual.context") { check(it==null) };f.drain()
        check(service.launches.isEmpty())
        val homeContext=service.context // Original fixed intent from the actual Session.
        var result:ProjectionDisplayState?=null
        f.host.launchApp("example.explicit.home",homeContext) { result=it };f.drain()
        check(result!!.context==homeContext && service.launches==listOf("example.explicit.home"))
        service.display=service.display.copy(context=ProjectionContext("","replacement-before-poll"),displayId=88)
        f.host.launchApp("example.old.explicit.home",homeContext) { result=it };f.drain()
        check(service.launches==listOf("example.explicit.home"))
        check(result!!.status==ProjectionLaunchStatus.UNAVAILABLE && result!!.context==homeContext && result!!.displayId==null)
    }
    check(failures == 0) { "$failures adapter scenarios failed" }
    println("$scenarios adapter scenarios passed; modeled host events, no device/Binder claim")
}
