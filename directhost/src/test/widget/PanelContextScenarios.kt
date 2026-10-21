package com.code2hack.eyeprojector.directhost

import android.content.Context
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

private class PanelFixture : AutoCloseable {
    val directory = Files.createTempDirectory("widget-panel-context-").toFile()
    val host: HostBootstrap
    val service = FakeService().apply { state = "STREAMING" }
    init {
        Shizuku.reset(); directory.resolve("session").mkdirs()
        for (name in listOf("identity.p12", "password", "peer.crt", "endpoint.properties")) directory.resolve("session/$name").writeText("synthetic fixture")
        host = HostBootstrap::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(Context(directory))
        drain(); host.start("widget-owner"); drain()
        Shizuku.connection!!.onServiceConnected(Shizuku.args!!.component, service); drain()
    }
    fun field(name: String) = HostBootstrap::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host)
    fun drain() { repeat(3) {
        (field("executor") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS)
        for (name in listOf("panelExecutor", "panelQueryExecutor", "displayExecutor", "scaleExecutor"))
            (field(name) as ExecutorService).submit {}.get(3, TimeUnit.SECONDS)
    } }
    override fun close() {
        for (name in listOf("executor", "panelExecutor", "panelQueryExecutor", "displayExecutor", "scaleExecutor")) {
            (field(name) as ExecutorService).apply { shutdownNow(); awaitTermination(3, TimeUnit.SECONDS) }
        }
        directory.deleteRecursively()
    }
}
fun main() {
    var count = 0
    fun scenario(name: String, run: (PanelFixture) -> Unit) { PanelFixture().use(run); count++; println("PASS $count $name") }
    scenario("expected current context submits with unverified observation") { f ->
        val expected = f.host.status().projectionContext
        var reply: PanelPowerState? = null
        f.host.requestPanelOff(expected) { reply = it }; f.drain()
        check(reply!!.off == PanelSubmission.SUBMITTED && reply!!.observation == PanelObservation.UNVERIFIED)
        check(f.service.panelContexts == listOf(PanelContext(expected.ownerEpoch, expected.projectionToken)))
    }
    scenario("stale explicit context after replacement cannot recapture current projection") { f ->
        val old = f.host.status().projectionContext
        f.service.replaceProjection("replacement-owner"); f.host.refresh(); f.drain()
        var reply: PanelPowerState? = null
        f.host.requestPanelOff(old) { reply = it }; f.drain()
        check(reply!!.off == PanelSubmission.UNAVAILABLE && f.service.panelRequests.isEmpty())
        val fresh = f.host.status().projectionContext
        f.host.requestPanelOff(fresh) { reply = it }; f.drain()
        check(reply!!.off == PanelSubmission.SUBMITTED && f.service.panelWrites.single().ownerEpoch == fresh.ownerEpoch)
    }
    scenario("same owner replacement token rejects original widget context") { f ->
        val old = f.host.status().projectionContext
        f.service.replaceProjection(old.ownerEpoch); f.host.refresh(); f.drain()
        check(f.host.status().projectionContext != old)
        var reply: PanelPowerState? = null
        f.host.requestPanelOff(old) { reply = it }; f.drain()
        check(reply!!.off == PanelSubmission.UNAVAILABLE && f.service.panelRequests.isEmpty())
    }
    scenario("unresolved explicit context never falls back to entry snapshot") { f ->
        var reply: PanelPowerState? = null
        f.host.requestPanelOff(ProjectionContext()) { reply = it }; f.drain()
        check(reply!!.off == PanelSubmission.UNAVAILABLE && f.service.panelRequests.isEmpty())
    }
    scenario("replacement before first lifecycle hop rejects captured intent") { f ->
        val old = f.host.status().projectionContext
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        (f.field("executor") as ScheduledExecutorService).execute {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            f.service.replaceProjection()
            HostBootstrap::class.java.getDeclaredMethod("readStatus", String::class.java).apply { isAccessible = true }.invoke(f.host, f.service.status())
        }
        check(entered.await(1, TimeUnit.SECONDS))
        var reply: PanelPowerState? = null
        try { f.host.requestPanelOff(old) { reply = it } } finally { release.countDown() }
        f.drain(); check(reply!!.off == PanelSubmission.UNAVAILABLE && f.service.panelRequests.isEmpty())
    }
    scenario("server validation rejects unobserved same Binder replacement") { f ->
        val old = f.host.status().projectionContext
        f.service.replaceProjection(old.ownerEpoch)
        var reply: PanelPowerState? = null
        f.host.requestPanelOff(old) { reply = it }; f.drain()
        check(reply == null || reply!!.off == PanelSubmission.UNAVAILABLE)
        check(f.service.panelWrites.isEmpty())
    }
    println("$count actual expected-context bootstrap cases PASS; Binder/Android modeled; no physical proof")
}
