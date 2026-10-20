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
    check(failures == 0) { "$failures adapter scenarios failed" }
    println("9 adapter scenarios passed; modeled host events, no device/Binder claim")
}
