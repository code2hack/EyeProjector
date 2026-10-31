package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.Geometry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun ManagedProjectionDisplay.field(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }
private fun ManagedProjectionDisplay.set(name: String, value: Any?) = field(name).set(this, value)
private fun Fixture.managed(displayId: Int): ManagedProjectionDisplay {
    val original = ManagedProjectionDisplay::class.java.declaredConstructors.single { it.parameterCount == 1 }
        .apply { isAccessible = true }.newInstance({ _: String, _: Int?, _: Int? -> Unit }) as ManagedProjectionDisplay
    original.set("capturedDisplayId", displayId)
    original.set("observedSource", ManagedProjectionDisplay.SourceObservation(Geometry(400, 400), Geometry(1000, 800), 320, 0, false))
    original.inputGeometry(Geometry(500, 400))
    set("managedDisplay", original)
    return original
}

private class UiAdapter : ProjectionLaunchAdapter {
    var current: String? = "current.app"
    var query: (() -> Unit)? = null
    var closeQuery: (() -> Unit)? = null
    var queries = 0
    override fun launch(displayId: Int, packageName: String, home: Boolean) =
        ProjectionCommandResult(0, "Status: ok\nActivity: $packageName/.PreviouslyLaunched\n")
    override fun foreground(displayId: Int): String? { queries++; query?.invoke(); return current }
    override fun close() { closeQuery?.invoke() }
}

private fun Fixture.prepare(displayId: Int = 34): Pair<ManagedProjectionDisplay, UiAdapter> {
    val mapping = managed(displayId)
    val adapter = UiAdapter()
    val launches = adapter(adapter)
    launches.initialize(displayId); launches.snapshot(true)
    return mapping to adapter
}

private class ObservedWindow(private val displayId: Int, private val onRoot: () -> Unit = {}) : UiAccessWindow {
    var closed = 0
    var nodesClosed = 0
    override val metadata = UiWindowMetadata(displayId, 7, 1, 0, true, true, UiBounds(0, 0, 400, 400))
    override fun root(): UiAccessNode {
        onRoot()
        return object : UiAccessNode {
            override val packageName = "current.app"
            override val windowId = 7
            override val childCount = 0
            override fun child(index: Int): UiAccessNode? = error("No child")
            override fun observation(ordinal: Int) = UiTargetObservation(ordinal, 7, packageName,
                "current.app:id/control", "android.widget.Button", UiBounds(20, 40, 100, 120), listOf(16), true, false, true, true)
            override fun close() { nodesClosed++ }
        }
    }
    override fun close() { closed++ }
}

private fun bridge(f: Fixture): Bridge {
    File(f.directory, "endpoint.properties").writeText("host=192.0.2.1\nport=44378\n")
    return Bridge(f.directory, File(f.directory, "unused.jar"), f.timer, Listener(), {}, f.ownerEpoch).also { it.active(f) }
}
private fun Bridge.active(f: Fixture) {
    val lock = javaClass.getDeclaredField("admission").apply { isAccessible = true }.get(this)
    @Suppress("UNCHECKED_CAST")
    val active = javaClass.getDeclaredField("active").apply { isAccessible = true }.get(this) as AtomicReference<Any?>
    synchronized(lock) { active.set(f.session) }
}

fun main() {
    Fixture().use { f ->
        check((f.session as CapturedProjection).uiTargetContext() == null) // Legacy/no managed source.
        val (mapping, adapter) = f.prepare()
        f.launch(4, "previously.requested")
        val original = f.session as CapturedProjection
        val source = checkNotNull(original.uiTargetContext()) // Dispatches the real Session override.
        check(source.context == f.state().context && source.displayId == 34)
        check(source.displayGeneration == f.state().generation && source.displayUniqueId == null)
        check(source.foregroundPackage == "current.app" && source.foregroundActivity == null)
        check(source.foregroundPackage != f.state().launchedComponent?.substringBefore('/'))

        // Independent lane must obtain original Session/input locks while the metadata observer runs.
        val lockLane = Executors.newSingleThreadExecutor()
        try {
            adapter.query = {
                lockLane.submit {
                    synchronized(f.get("managedDisplayGate")!!) {
                        synchronized(f.get("controlLock")!!) {
                            synchronized(mapping.field("inputOperations").get(mapping)) { true }
                        }
                    }
                }.get(2, TimeUnit.SECONDS)
            }
            val window = ObservedWindow(34) {
                check(!Thread.holdsLock(f.get("managedDisplayGate")))
                check(!Thread.holdsLock(f.get("controlLock")))
                check(!Thread.holdsLock(mapping.field("inputOperations").get(mapping)))
            }
            val access = object : UiAccess {
                override val canRead = true // Modeled framework response; no permission claim.
                override fun windowsOnAllDisplays() = mapOf(34 to listOf(window))
            }
            val provider = UiTargetSnapshotProvider({ expected -> original.uiTargetContext()?.takeIf { it.context == expected } },
                { UiTargetReader(access, { 100 }) }, { 100 })
            val result = provider.read(source.context, "current.app", 7)
            check(result.status == UiTargetReadStatus.READ && result.windows.single().targets.size == 1)
            check(window.closed == 1 && window.nodesClosed == 1)
            val bounds = result.windows.single().targets.single().bounds
            val rectangle = result.request!!.source.geometry.sourceToReceiver(bounds, 1200, 800)
            check(rectangle == UiRectangle(240.0, 80.0, 400.0, 240.0))
            val other = provider.read(source.context, "requested.other", 7)
            check(other.windows.single().status == UiWindowReadStatus.OTHER_PACKAGE && other.windows.single().targets.isEmpty())
            check(other.request!!.selectedPackage == "requested.other" && other.request!!.source.foregroundPackage == "current.app")
            adapter.current = null
            check(original.uiTargetContext()!!.foregroundPackage == null)
        } finally { lockLane.shutdownNow() }
        println("PASS real Session -> current managed context -> provider/reader -> independent Receiver geometry, selected/observed separation and released metadata/traversal locks")
    }
    for (change in listOf("transition", "mapping", "original", "retired")) {
        Fixture().use { f ->
            val (mapping, adapter) = f.prepare()
            adapter.query = {
                when (change) {
                    "transition" -> mapping.set("sourceTransitioning", true)
                    "mapping" -> {
                        mapping.set("observedSource", ManagedProjectionDisplay.SourceObservation(Geometry(800, 800), Geometry(1000, 800), 320, 0, false))
                        mapping.inputGeometry(Geometry(500, 400))
                    }
                    "original" -> f.managed(34)
                    "retired" -> f.set("managedDisplayCloseRequested", true)
                }
            }
            check((f.session as CapturedProjection).uiTargetContext() == null)
        }
        println("PASS original Session context unavailable after $change during foreground observation")
    }
    Fixture().use { old -> Fixture(old.ownerEpoch).use { fresh ->
        val (_, a) = old.prepare(34); fresh.prepare(88)
        val c = checkNotNull((old.session as CapturedProjection).uiTargetContext()).context
        bridge(old).use { bridge ->
            val captured = checkNotNull(bridge.captureProjection(c))
            bridge.active(fresh)
            check(bridge.captureProjection(c) == null)
            check(captured.uiTargetContext()!!.displayId == 34)
            val next = (fresh.session as CapturedProjection).uiTargetContext()!!
            check(bridge.captureProjection(next.context) === fresh.session && next.displayId == 88)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            a.query = { entered.countDown(); while (release.count > 0) try { release.await() } catch (_: InterruptedException) {} }
            a.closeQuery = { release.countDown() }
            val caller = Executors.newSingleThreadExecutor()
            try {
                val pending = caller.submit<ManagedUiSource?> { captured.uiTargetContext() }
                check(entered.await(2, TimeUnit.SECONDS))
                old.closeSession()
                check(pending.get(2, TimeUnit.SECONDS) == null && captured.uiTargetContext() == null)
                check((fresh.session as CapturedProjection).uiTargetContext()!!.displayId == 88)
            } finally { release.countDown(); caller.shutdownNow() }
        }
        println("PASS actual Bridge captures original Session across same-owner replacement; in-flight retirement yields unavailable and leaves successor untouched")
    } }
    println("PASS 6 UI-target Session composition scenarios; modeled framework/foreground observations, no permission/encoded/device proof")
}
