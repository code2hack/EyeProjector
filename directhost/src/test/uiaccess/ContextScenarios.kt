package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.Geometry
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual HostBootstrap facade; the existing Android/Shizuku/Binder fixture supplies external events. */
private class ContextFixture : AutoCloseable {
    private val directory = Files.createTempDirectory("eyeprojector-ui-context-").toFile()
    val host = HostBootstrap::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        .newInstance(Context(directory))
    val expected = ProjectionContext("owner", "projection")
    val observed = ManagedUiSource(expected, "actual-generation", 34, null, "current.app", null,
        UiSourceGeometry(Geometry(400, 400), Geometry(1000, 800), 320, 0, false, Geometry(500, 400)))
    val service = FakeService()
    var queries = 0
    init {
        // Quiesce the modeled periodic status source before supplying precise observed replacements.
        val lifecycle = field("executor").get(host) as ExecutorService
        lifecycle.submit {}.get(3, TimeUnit.SECONDS)
        lifecycle.shutdownNow(); check(lifecycle.awaitTermination(3, TimeUnit.SECONDS))
        set("current", HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE, "STREAMING",
            sessionEpoch = expected.ownerEpoch, projectionToken = expected.projectionToken))
        set("remote", service)
        service.uiTargetProvider = { context -> check(context == expected); queries++; observed.bytes() }
    }
    fun field(name: String) = HostBootstrap::class.java.getDeclaredField(name).apply { isAccessible = true }
    fun set(name: String, value: Any?) = field(name).set(host, value)
    val uiLane get() = field("uiTargetExecutor").get(host) as ExecutorService
    override fun close() {
        for (name in listOf("executor", "uiTargetExecutor", "displayExecutor", "panelExecutor", "panelQueryExecutor", "scaleExecutor")) {
            val lane = field(name).get(host) as ExecutorService
            lane.shutdownNow(); check(lane.awaitTermination(3, TimeUnit.SECONDS))
        }
        directory.deleteRecursively()
    }
}

fun main() {
    ContextFixture().use { f ->
        val captured = f.host.captureUiTargetSource(f.expected)!!
        check(captured.current(f.expected) == f.observed && f.queries == 1)
        check(captured.current(ProjectionContext("other", "context")) == null && f.queries == 1)
        f.set("remote", FakeService().apply { uiTargetProvider = { error("Must not query successor") } })
        check(captured.current(f.expected) == null)
    }
    ContextFixture().use { f ->
        val captured = f.host.captureUiTargetSource(f.expected)!!
        f.set("current", f.host.status().copy(projectionToken = "replacement"))
        check(captured.current(f.expected) == null && f.queries == 0)
    }
    ContextFixture().use { f ->
        val captured = f.host.captureUiTargetSource(f.expected)!!
        f.service.uiTargetProvider = {
            f.field("bindingGeneration").setLong(f.host, f.field("bindingGeneration").getLong(f.host) + 1)
            f.observed.bytes()
        }
        check(captured.current(f.expected) == null)
    }
    ContextFixture().use { f ->
        f.service.uiTargetProvider = { f.observed.copy(context = ProjectionContext("new", "session")).bytes() }
        check(f.host.captureUiTargetSource(f.expected)!!.current(f.expected) == null)
        f.service.uiTargetProvider = { null }
        check(f.host.captureUiTargetSource(f.expected)!!.current(f.expected) == null)
    }
    ContextFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        f.uiLane.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        check(entered.await(3, TimeUnit.SECONDS))
        var result: ManagedUiSource? = f.observed
        f.host.uiTargetContext(f.expected) { result = it; completed.countDown() }
        f.set("remote", FakeService().apply { uiTargetProvider = { error("Must not query queued successor") } })
        release.countDown(); check(completed.await(3, TimeUnit.SECONDS))
        check(result == null && f.queries == 0)
    }
    ContextFixture().use { f ->
        val done = CountDownLatch(1)
        var result: UiTargetSnapshot? = null
        f.host.uiTargets(f.expected, "selected.app", 7) { result = it; done.countDown() }
        check(done.await(3, TimeUnit.SECONDS))
        check(result!!.status == UiTargetReadStatus.ACCESS_REQUIRED && result!!.windows.isEmpty())
        check(result!!.request!!.selectedPackage == "selected.app" && result!!.request!!.source.foregroundPackage == "current.app")
        check(result!!.request!!.windowId == 7 && result!!.request!!.source.foregroundActivity == null)
    }
    println("PASS actual HostBootstrap: captured original proxy/context/generation, post-query replacement, unavailable contract, queued replacement, app-mediated access-required result and selected/observed separation")
}
