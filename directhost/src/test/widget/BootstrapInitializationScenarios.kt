package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Actual HostBootstrap executes initialization/observe/setup/refreshNow/publish. Only external APIs are modeled. */
private class InitializationFixture : AutoCloseable {
    private val directory = Files.createTempDirectory("r19-bootstrap-initialization-").toFile()
    val host: HostBootstrap
    val initial: HostBootstrap.Status
    init {
        Shizuku.reset(); Shizuku.available = false; PackageManager.shizukuInstalled = true
        host = HostBootstrap::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(Context(directory))
        initial = host.status()
    }
    private fun field(name: String) = HostBootstrap::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host)
    fun lifecycle() { repeat(3) { (field("executor") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS) } }
    fun flush() { lifecycle(); Handler.drain(); lifecycle(); Handler.drain() }
    fun recover(owner: String) {
        val service = FakeService().apply { state = "STREAMING"; replaceProjection(owner) }
        Shizuku.peekVersion = 123; Shizuku.peekService = service; Shizuku.available = true
        host.refresh(); flush()
    }
    override fun close() {
        for (name in listOf("executor", "panelExecutor", "panelQueryExecutor", "displayExecutor", "scaleExecutor")) {
            (field(name) as ExecutorService).apply { shutdownNow(); awaitTermination(3, TimeUnit.SECONDS) }
        }
        Handler.drain(); directory.deleteRecursively()
    }
}
fun main() {
    var cases = 0
    fun case(name: String, run: (InitializationFixture) -> Unit) {
        InitializationFixture().use(run); cases++; println("PASS $name")
    }
    case("actual bootstrap starts with the complete UNAVAILABLE/UNKNOWN sentinel") { f ->
        check(f.initial == HostBootstrap.Status(HostBootstrap.Setup.UNAVAILABLE, "UNKNOWN"))
        f.flush(); check(f.host.status() === f.initial)
    }
    case("actual observe posts the same untouched sentinel; replay is not reconciliation") { f ->
        val seen = mutableListOf<HostBootstrap.Status>()
        f.host.observe { seen.add(it) }
        check(seen.isEmpty()); f.flush()
        check(seen.single() === f.initial && f.host.status() === f.initial)
    }
    case("actual unavailable refresh value dedup neither replaces sentinel nor publishes") { f ->
        val seen = mutableListOf<HostBootstrap.Status>()
        f.host.observe { seen.add(it) }; f.flush()
        f.host.refresh(); f.flush(); f.host.refresh(); f.flush()
        check(f.host.status() === f.initial && seen.size == 1 && seen.single() === f.initial)
    }
    case("actual setup failure publishes a distinct canonical status") { f ->
        val seen = mutableListOf<HostBootstrap.Status>()
        f.host.observe { seen.add(it) }; f.flush()
        PackageManager.shizukuInstalled = false
        f.host.refresh(); f.flush()
        check(f.host.status() !== f.initial && f.host.status().setup == HostBootstrap.Setup.MISSING)
        check(seen.last() === f.host.status())
    }
    case("actual same-owner attachment replaces sentinel with resolved owner and projection") { f ->
        val seen = mutableListOf<HostBootstrap.Status>()
        f.host.observe { seen.add(it) }; f.flush(); f.recover("same-owner")
        val current = f.host.status()
        check(current !== f.initial && current.setup == HostBootstrap.Setup.AVAILABLE && current.backend == "STREAMING")
        check(current.sessionEpoch == "same-owner" && current.projectionContext.available && seen.last() === current)
    }
    case("actual initial observe rereads latest canonical state at main delivery") { f ->
        val seen = mutableListOf<HostBootstrap.Status>()
        f.host.observe { seen.add(it) }
        // Finish lifecycle reconciliation before draining the queued main replay.
        val service = FakeService().apply { state = "STREAMING"; replaceProjection("later-owner") }
        Shizuku.peekVersion = 123; Shizuku.peekService = service; Shizuku.available = true
        f.host.refresh(); f.lifecycle(); check(seen.isEmpty())
        f.flush(); check(seen.first().sessionEpoch == "later-owner" && seen.first() !== f.initial)
    }
    println("$cases actual bootstrap initialization/reference/observe/dedup cases PASS; Android/Shizuku/Binder modeled, no physical proof")
}
