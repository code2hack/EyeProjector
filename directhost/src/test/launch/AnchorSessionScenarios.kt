package com.code2hack.eyeprojector.directhost

import android.os.Binder
import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.*

private val anchorGeometry = Geometry(480, 640)
private fun Fixture.anchorManaged(publishPointer: Boolean = true): ManagedProjectionDisplay {
    val value = ManagedProjectionDisplay::class.java.declaredConstructors.single { it.parameterCount == 1 }
        .apply { isAccessible = true }.newInstance({ _: String, _: Int?, _: Int? -> Unit }) as ManagedProjectionDisplay
    fun put(name: String, v: Any?) { value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(value, v) }
    put("capturedDisplayId", 47)
    put("observedSource", ManagedProjectionDisplay.SourceObservation(anchorGeometry, anchorGeometry, 160, 0, false))
    value.inputGeometry(anchorGeometry); set("managedDisplay", value)
    val launcher = adapter(object : ProjectionLaunchAdapter {
        override fun launch(displayId: Int, packageName: String, home: Boolean) = ProjectionCommandResult(0, "Status: ok\n")
        override fun foreground(displayId: Int) = "app"
        override fun close() = Unit
    })
    launcher.initialize(47); launcher.snapshot(true)
    fusionGeometry(); if (publishPointer) pointer()
    return value
}
private fun Fixture.install(client: IAnchorAppBridge) {
    type.getDeclaredMethod("installAnchorBridge", IAnchorAppBridge::class.java).apply { isAccessible = true }.invoke(session, client)
}
private fun Fixture.functions() {
    val done = CountDownLatch(1); (get("functionWork") as FunctionWork).submit { done.countDown() }; check(done.await(3, TimeUnit.SECONDS))
}
private fun AnchorAppRuntime.drain() { val done = CountDownLatch(1); onLane { done.countDown() }; check(done.await(3, TimeUnit.SECONDS)) }
private fun Fixture.anchorIntent(n: Long) = FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.GLASSES_NATIVE, n, n,
    FunctionIntentContext((session as CapturedProjection).receiverUsability().fact!!,
        HeadPointerState(1, n + 1, true, true, 10f, 20f, Fit(0, 0, 480, 640), anchorGeometry)))
private fun Fixture.feedback(): List<AnchorMessage> = DataInputStream(peer.sink.toByteArray().inputStream()).use { input ->
    buildList { while (input.available() > 0) { val frame = Wire.readFrame(input); if (frame.channel == Wire.ANCHOR) add(AnchorMessage.from(frame.payload)) } }
}
private fun reader(during: () -> Unit = {}): UiTargetReader = UiTargetReader(object : UiAccess {
    override val canRead = true
    override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
        during()
        return mapOf(47 to listOf(object : UiAccessWindow {
            override val metadata = UiWindowMetadata(47, 7, 1, 0, true, true, UiBounds(0, 0, 480, 640))
            override fun root() = object : UiAccessNode {
                override val packageName = "app"; override val windowId = 7; override val childCount = 0
                override fun child(index: Int): UiAccessNode? = null
                override fun observation(ordinal: Int) = UiTargetObservation(ordinal, 7, "app", "app:id/action", "Button",
                    UiBounds(300, 400, 340, 440), listOf(16), true, false, true, true)
                override fun close() = Unit
            }
            override fun close() = Unit
        }))
    }
}, { 0 })

fun main() {
    val appDirectory = Files.createTempDirectory("eyeprojector-anchor-app-").toFile()
    val runtime = AnchorAppRuntime(android.content.Context(appDirectory))
    val saved = AppAnchor(AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.CLICK)
    try {
        UiTargetAccess.currentReader = reader()
        Fixture().use { f ->
            f.anchorManaged(false); f.install(runtime.bind()); runtime.store.replace(saved)
            val output = f.get("inputControls") as AnchorSessionInput
            val events = mutableListOf<Touch>()
            output.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                override fun geometry(next: Geometry) = Unit
                override fun touch(event: Touch): Boolean { events.add(event); return true }
                override fun tap(event: Tap) = true
                override fun scroll(event: Scroll) = true
            })
            val producer = File(System.getenv("EYEPROJECTOR_FUNCTION_PRODUCER_EVIDENCE"), "producer-wire.bin")
            check(producer.isFile) { "Run actual Glasses Function producer first" }
            var count = 0
            DataInputStream(producer.inputStream()).use { wire ->
                while (wire.available() > 0 && count < 2) {
                    val frame = Wire.readFrame(wire); f.receive(frame)
                    if (frame.channel == Wire.FUNCTION_INTENT) count++
                }
            }
            check(count == 2)
            f.functions(); runtime.drain()
            check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP, Touch.Phase.DOWN, Touch.Phase.UP))
            check(events.all { it.target == Tap(320, 420, anchorGeometry) })
            check(events[0].contactId == events[1].contactId && events[2].contactId == events[3].contactId)
            check(f.feedback().filterIsInstance<AnchorMessage.Feedback>().map { it.status } == List(2) { AnchorFeedback.CLICK_SUBMITTED })
            File(System.getenv("EYEPROJECTOR_ANCHOR_EVIDENCE"), "phone-feedback.bin").writeBytes(f.peer.sink.toByteArray())
            println("PASS actual Phone Session Function FIFO -> actual app Binder consumer/FIFO/private store/reader/resolver -> original Session Binder/contact -> matched wire10 feedback; two clicks independent of head aim")
            // Real Phone HID short pair enters the same installed consumer and app FIFO.
            f.pointer(sequence = 10)
            val at = android.os.SystemClock.uptimeMillis()
            val down = PhoneHidEdge(PhoneHidAction.FUNCTION, PhoneHidPhase.DOWN, 1, 67, 90, at, at)
            f.hid(8, down); f.hid(8, down.copy(phase = PhoneHidPhase.UP, eventTimeMs = at + 20))
            f.functions(); runtime.drain(); check(events.size == 6)
            runtime.store.clear("app"); runtime.drain()
            f.receive(Frame(Wire.FUNCTION_INTENT, payload = f.anchorIntent(3).bytes())); f.functions(); runtime.drain()
            check(events.size == 6 && (f.feedback().last() as AnchorMessage.Feedback).status == AnchorFeedback.NO_ANCHOR)
            println("PASS actual Phone HID ACTIVATE executes same owner; clear reconciles retirement and missing preference gives typed instruction without input")
        }
        for (change in listOf("input", "window", "settings", "stop", "binder")) Fixture().use { old -> Fixture().use { fresh ->
            old.anchorManaged(); fresh.anchorManaged(); runtime.store.replace(saved); old.install(runtime.bind())
            val source = old.get("inputControls") as AnchorSessionInput
            val successor = fresh.get("inputControls") as AnchorSessionInput
            val oldEvents = mutableListOf<Touch>(); val freshEvents = mutableListOf<Touch>()
            fun bind(output: AnchorSessionInput, events: MutableList<Touch>) = output.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                override fun geometry(next: Geometry) = Unit
                override fun touch(event: Touch): Boolean { events.add(event); return true }
                override fun tap(event: Tap) = true
                override fun scroll(event: Scroll) = true
            })
            bind(source, oldEvents); bind(successor, freshEvents)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            UiTargetAccess.currentReader = reader { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            old.receive(Frame(Wire.FUNCTION_INTENT, payload = old.anchorIntent(10).bytes())); old.functions()
            check(entered.await(2, TimeUnit.SECONDS))
            when (change) {
                "input" -> old.receive(Frame(Wire.SCROLL, payload = Scroll(Tap(10, 20, anchorGeometry), 160).bytes()))
                "window" -> UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true))
                "settings" -> runtime.store.clear("app")
                "stop" -> old.closeSession()
                "binder" -> runtime.invalidateBinding()
            }
            release.countDown(); runtime.drain()
            check(oldEvents.isEmpty() && freshEvents.isEmpty())
            check(!source.holding && !successor.holding)
            println("PASS actual Session in-flight app traversal retired by $change; no DOWN or successor input")
        } }
        for (stage in listOf("accept", "offer")) for (replacement in listOf(false, true)) Fixture().use { f ->
            UiTargetAccess.currentReader = reader(); f.anchorManaged()
            var calls = 0; var operation: IAnchorSession? = null
            fun client() = object : IAnchorAppBridge.Stub() {
                override fun onIntent(transactionId: Long, functionIntent: ByteArray, original: IAnchorSession) { calls++; operation = original }
                override fun onRegistration(message: ByteArray, original: IAnchorSession) = Unit
            }
            f.install(client())
            @Suppress("UNCHECKED_CAST")
            val shell = (f.get("anchorSession\$delegate") as Lazy<AnchorShellSession>).value
            val owner = f.get("inputControls") as AnchorSessionInput
            val original = f.anchorIntent(30).copy(kind = FunctionIntentKind.REGISTER)
            val ticket = owner.anchor.capture(original)!!
            if (stage == "offer") shell.accept(ticket)
            if (replacement) f.install(client()) else owner.anchor.retire()
            if (stage == "accept") { shell.accept(ticket); check(calls == 0) }
            else {
                check(calls == 1)
                operation!!.send(AnchorMessage.Offer(1, original, AnchorHighlight(300.0, 400.0, 340.0, 440.0)).bytes())
                check(f.feedback().isEmpty())
            }
            println("PASS actual original REGISTER capture -> ${if (replacement) "binding replacement" else "input retirement"} -> $stage cannot open successor offer")
        }
        for (change in listOf("window", "binding")) Fixture().use { f ->
            UiTargetAccess.currentReader = reader(); f.anchorManaged(); f.install(runtime.bind())
            var cancellations = 0
            runtime.installRegistration({ request ->
                request.original.send(AnchorMessage.Offer(request.original.transactionId, request.original.intent,
                    AnchorHighlight(300.0, 400.0, 340.0, 440.0)))
            }, { message, original ->
                if (message is AnchorMessage.Choice && message.choice == AnchorChoice.CANCEL) {
                    cancellations++
                    original.send(AnchorMessage.Result(message.transactionId, message.intent, AnchorRegistrationResult.CANCELED))
                }
            })
            runtime.drain()
            f.receive(Frame(Wire.FUNCTION_INTENT, payload = f.anchorIntent(40).copy(kind = FunctionIntentKind.REGISTER).bytes()))
            f.functions(); runtime.drain(); check(f.feedback().last() is AnchorMessage.Offer)
            if (change == "window") UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true)) else runtime.invalidateBinding()
            runtime.drain()
            check(cancellations == 1 && (f.feedback().last() as AnchorMessage.Result).result == AnchorRegistrationResult.CANCELED)
            println("PASS actual app $change retirement -> same FIFO registration cancellation -> matching original UI Result")
            runtime.onLane { runtime.coordinator.registration = null; runtime.coordinator.registrationMessage = null }; runtime.drain()
        }
        for (failure in listOf(false, true)) Fixture().use { f ->
            UiTargetAccess.currentReader = reader()
            f.anchorManaged(); f.install(runtime.bind()); runtime.store.replace(saved)
            val input = f.get("inputControls") as AnchorSessionInput
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            input.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                override fun geometry(next: Geometry) = Unit
                override fun touch(event: Touch): Boolean {
                    if (event.phase == Touch.Phase.DOWN) {
                        entered.countDown()
                        if (failure) throw IOException("modeled original input failure")
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                    }
                    return true
                }
                override fun tap(event: Tap) = true
                override fun scroll(event: Scroll) = true
            })
            try {
                f.receive(Frame(Wire.FUNCTION_INTENT, payload = f.anchorIntent(20).bytes())); f.functions()
                check(entered.await(2, TimeUnit.SECONDS))
                if (failure) runtime.drain() else {
                    val closer = Executors.newSingleThreadExecutor()
                    try { closer.submit { f.closeSession() }.get(2, TimeUnit.SECONDS) } finally { closer.shutdownNow() }
                    check(release.count == 1L) // Stop completed while original DOWN remained blocked.
                }
                check(f.peer.released && f.control.released && !f.child.alive)
                release.countDown(); runtime.drain()
                check(!input.holding)
                println("PASS actual Anchor original ${if (failure) "input failure" else "blocked DOWN + Stop"} preserves original Session/socket/child cleanup; no successor or completed action")
            } finally { release.countDown() }
        }
    } finally { UiTargetAccess.currentReader = null; appDirectory.deleteRecursively() }
    println("PASS15 actual Anchor Session/Binder-value composition scenarios; external Android Binder/UI handles doubled, no physical/accessibility grant or app-effect proof")
}
