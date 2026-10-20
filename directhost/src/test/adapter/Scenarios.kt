package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.protocol.*
import rikka.shizuku.Shizuku
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
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
    fun drain() {
        // A callback may enqueue more work behind the first barrier.
        repeat(3) { (field("executor") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS) }
    }
    fun attach(service: FakeService) {
        Shizuku.connection!!.onServiceConnected(Shizuku.args!!.component, service); drain()
    }
    fun expireAttachment() {
        HostBootstrap::class.java.getDeclaredField("bindStarted").apply { isAccessible = true }
            .setLong(host, System.nanoTime() - TimeUnit.SECONDS.toNanos(35))
    }
    fun backend() = (field("current") as HostBootstrap.Status).backend
    override fun close() {
        val executor = field("executor") as ScheduledExecutorService
        executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        directory.deleteRecursively()
    }
}

private var failures = 0
private fun scenario(name: String, run: (Fixture) -> Unit) {
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
    scenario("typed local scale preserves observed feedback and exposes unavailable transport honestly") { f ->
        f.host.start(); f.drain()
        val service = FakeService(); f.attach(service)
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
        val second = FakeService(); f.attach(second)
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
        val service = FakeService(); f.attach(service)
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
        val first = FakeService(); f.attach(first)
        f.host.requestPanelOff {}; f.drain()
        f.host.stop(); f.drain()
        check(first.revoked && first.destroys == 1 && f.backend() == "STOPPED")
        var result: PanelPowerState? = null
        f.host.panelState { result = it }; f.drain()
        check(result == null)
        f.host.start(); f.drain()
        val fresh = FakeService(); f.attach(fresh)
        f.host.panelState { result = it }; f.drain()
        check(result!!.off == PanelSubmission.NOT_REQUESTED && fresh.panelRequests.isEmpty())
    }
    check(failures == 0) { "$failures adapter scenarios failed" }
    println("16 adapter scenarios passed; modeled host events, no device/Binder claim")
}
