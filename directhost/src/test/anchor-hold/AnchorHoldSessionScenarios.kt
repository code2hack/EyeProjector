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

internal val holdGeometry = Geometry(480, 640)
internal val savedHold = AppAnchor(AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.HOLD)
internal fun Fixture.holdManaged(publishPointer: Boolean = true): ManagedProjectionDisplay {
    val value = ManagedProjectionDisplay::class.java.declaredConstructors.single { it.parameterCount == 1 }
        .apply { isAccessible = true }.newInstance({ _: String, _: Int?, _: Int? -> Unit }) as ManagedProjectionDisplay
    value.javaClass.getDeclaredField("capturedDisplayId").apply { isAccessible = true }.set(value, 47)
    value.javaClass.getDeclaredField("observedSource").apply { isAccessible = true }.set(value,
        ManagedProjectionDisplay.SourceObservation(holdGeometry, holdGeometry, 160, 0, false))
    value.inputGeometry(holdGeometry); set("managedDisplay", value)
    adapter(object : ProjectionLaunchAdapter {
        override fun launch(displayId: Int, packageName: String, home: Boolean) = ProjectionCommandResult(0, "Status: ok\n")
        override fun foreground(displayId: Int) = "app"
        override fun close() = Unit
    }).also { it.initialize(47); it.snapshot(true) }
    fusionGeometry(); if (publishPointer) pointer()
    return value
}
internal fun holdReader() = UiTargetReader(object : UiAccess {
    override val canRead = true
    override fun windowsOnAllDisplays() = mapOf(47 to listOf(object : UiAccessWindow {
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
}, { 0 })
internal fun AnchorAppRuntime.holdDrain() { val done = CountDownLatch(1); onLane { done.countDown() }; check(done.await(3, TimeUnit.SECONDS)) }
internal fun Fixture.holdFunctions() {
    val done = CountDownLatch(1); (get("functionWork") as FunctionWork).submit { done.countDown() }; check(done.await(3, TimeUnit.SECONDS))
}
internal fun Fixture.holdFlush(runtime: AnchorAppRuntime) { holdFunctions(); runtime.holdDrain(); holdFunctions() }
internal fun Fixture.holdInstall(client: IAnchorAppBridge) {
    type.getDeclaredMethod("installAnchorBridge", IAnchorAppBridge::class.java).apply { isAccessible = true }.invoke(session, client)
}
private fun Fixture.holdIntent(n: Long, kind: FunctionIntentKind = FunctionIntentKind.ACTIVATE) = FunctionIntent(kind,
    FunctionSource.GLASSES_NATIVE, n, n, FunctionIntentContext((session as CapturedProjection).receiverUsability().fact!!,
        HeadPointerState(1, n + 1, true, true, if (kind == FunctionIntentKind.REGISTER) 320f else 10f,
            if (kind == FunctionIntentKind.REGISTER) 420f else 20f, Fit(0, 0, 480, 640), holdGeometry)))
private fun Fixture.holdPress(runtime: AnchorAppRuntime, n: Long, kind: FunctionIntentKind = FunctionIntentKind.ACTIVATE) {
    receive(Frame(Wire.FUNCTION_INTENT, payload = holdIntent(n, kind).bytes())); holdFlush(runtime)
}
internal fun Fixture.holdMessages() = DataInputStream(peer.sink.toByteArray().inputStream()).use { input ->
    buildList { while (input.available() > 0) { val f = Wire.readFrame(input); if (f.channel == Wire.ANCHOR) add(AnchorMessage.from(f.payload)) } }
}
internal fun Fixture.holdEvents(): MutableList<Touch> {
    val events = CopyOnWriteArrayList<Touch>()
    (get("inputControls") as AnchorSessionInput).bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
        override fun geometry(next: Geometry) = Unit
        override fun touch(event: Touch): Boolean { events.add(event); return true }
        override fun tap(event: Tap) = true
        override fun scroll(event: Scroll) = true
    })
    return events
}
private fun Fixture.checkTransportLock() {
    val output = object : OutputStream() {
        override fun write(value: Int) { check(!Thread.holdsLock(get("controlLock")!!)); peer.sink.write(value) }
        override fun write(data: ByteArray, offset: Int, length: Int) {
            check(!Thread.holdsLock(get("controlLock")!!)); peer.sink.write(data, offset, length)
        }
    }
    set("output", DataOutputStream(output))
}

fun main() {
    val directory = Files.createTempDirectory("eyeprojector-hold-app-").toFile()
    val runtime = AnchorAppRuntime(android.content.Context(directory))
    fun setup(f: Fixture): Pair<ManagedProjectionDisplay, IAnchorAppBridge> {
        UiTargetAccess.currentReader = holdReader()
        val display = f.holdManaged()
        val bridge = runtime.bind(); f.holdInstall(bridge); runtime.holdDrain()
        check(runtime.registrationProviderState == AnchorAppRuntime.ProviderState.INSTALLED)
        runtime.store.replace(savedHold); runtime.holdDrain()
        return display to bridge
    }
    try {
        Fixture().use { f ->
            setup(f); val events = f.holdEvents(); f.checkTransportLock()
            val at = android.os.SystemClock.uptimeMillis()
            fun press(id: Long, n: Long) {
                val down = PhoneHidEdge(PhoneHidAction.FUNCTION, PhoneHidPhase.DOWN, 1, 67, 90, at + n * 50, at + n * 50)
                f.hid(id, down); f.holdFlush(runtime)
                if (n == 0L) check(events.isEmpty())
                f.hid(id, down.copy(phase = PhoneHidPhase.UP, eventTimeMs = down.eventTimeMs + 20)); f.holdFlush(runtime)
            }
            press(1, 0)
            check(events.map { it.phase } == listOf(Touch.Phase.DOWN))
            val input = f.get("inputControls") as AnchorSessionInput
            check(input.anchor.holding && input.holding)
            repeat(5) { n -> f.receive(Frame(Wire.HEAD_POINTER, payload = HeadPointerState(1, 10L + n, true, true,
                400f + n, 500f, Fit(0, 0, 480, 640), holdGeometry).bytes())) }
            check(events.size == 1)
            press(2, 1)
            check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
            check(events[0].contactId == events[1].contactId && events.all { it.target == Tap(320, 420, holdGeometry) })
            val states = f.holdMessages().filterIsInstance<AnchorMessage.HeldState>()
            check(states.map { it.state } == listOf(AnchorHoldState.HELD, AnchorHoldState.RELEASED))
            check(states[0].intent == states[1].intent && states[1].revision > states[0].revision)
            File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"), "phone-held-state.bin").writeBytes(f.peer.sink.toByteArray())
            println("PASS actual Phone HID physical UP leaves original Anchor DOWN; head movement no MOVE; next ACTIVATE original UP; state transport callback holds no control lock")
        }
        Fixture().use { f ->
            setup(f); val events = f.holdEvents(); f.holdPress(runtime, 1)
            val storageField = AppAnchorStore::class.java.getDeclaredField("storage").apply { isAccessible = true }
            val original = storageField.get(runtime.store) as AppAnchorStorage
            storageField.set(runtime.store, object : AppAnchorStorage {
                override fun read(packageName: String): AppAnchor? = error("original release must not load saved values")
                override fun write(packageName: String, anchor: AppAnchor?) = original.write(packageName, anchor)
            })
            UiTargetAccess.currentReader = UiTargetReader(object : UiAccess {
                override val canRead: Boolean get() = error("original release must not read nodes")
                override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> = error("no fresh target")
            }, { 0 })
            try {
                f.holdPress(runtime, 2)
                check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
                check((f.holdMessages().last() as AnchorMessage.Feedback).status == AnchorFeedback.RELEASE_SUBMITTED)
            } finally { storageField.set(runtime.store, original) }
            println("PASS actual Session/Binder/runtime release succeeds with throwing saved-record and reader access")
        }
        for (cause in listOf("touch", "tap", "scroll", "hid-tap", "back", "home", "lock", "window", "access", "app",
            "geometry", "scale", "managed-scale", "mode", "incarnation", "input-revision", "binder-death", "binder-replace", "clear", "replace", "stop")) Fixture().use { f ->
            val (display, bridge) = setup(f)
            val events = f.holdEvents(); f.holdPress(runtime, 1)
            when (cause) {
                "touch" -> f.receive(Frame(Wire.TOUCH, payload = Touch(Touch.Phase.DOWN, 88, Tap(10, 20, holdGeometry)).bytes()))
                "tap" -> f.receive(Frame(Wire.TAP, payload = Tap(10, 20, holdGeometry).bytes()))
                "scroll" -> f.receive(Frame(Wire.SCROLL, payload = Scroll(Tap(10, 20, holdGeometry), 160).bytes()))
                "hid-tap", "lock" -> {
                    val at = android.os.SystemClock.uptimeMillis()
                    val down = PhoneHidEdge(if (cause == "lock") PhoneHidAction.LOCK else PhoneHidAction.TAP,
                        PhoneHidPhase.DOWN, 1, 66, 88, at, at)
                    f.hid(8, down); f.hid(8, down.copy(phase = PhoneHidPhase.UP, eventTimeMs = at + 20))
                }
                "back", "home" -> f.receive(Frame(Wire.NAVIGATE, payload = Navigate(
                    if (cause == "back") Navigation.BACK else Navigation.HOME, holdGeometry).bytes()))
                "window" -> UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true))
                "access" -> UiTargetAccess.event(null)
                "app" -> f.launch(20, "other.app")
                "geometry" -> (f.get("inputControls") as AnchorSessionInput).geometry(Geometry(640, 480))
                "scale", "managed-scale" -> {
                    if (cause == "managed-scale") {
                        val factory: ((String, Int?, Int?) -> Unit) -> ManagedProjectionDisplay = { display }
                        f.set("managedDisplayFactory", factory)
                    }
                    (f.get("scale") as LiveScale).observeManagedLayout(holdGeometry)
                    f.type.getDeclaredMethod("requestScale", Long::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(f.session, 9L, 1.5, true)
                    if (cause == "managed-scale") {
                        check(display.uiInputSnapshot() == null)
                        f.set("managedDisplayFactory", null) // No platform resource was constructed by this fixture.
                    }
                }
                "mode", "incarnation", "input-revision" -> {
                    val fact = (f.session as CapturedProjection).receiverUsability().fact!!
                    val next = when (cause) {
                        "mode" -> fact.copy(revision = fact.revision + 1, mode = ReceiverUsabilityMode.NONE)
                        "incarnation" -> fact.copy(revision = fact.revision + 1, incarnation = ReceiverIncarnation("replacement", 2))
                        "input-revision" -> fact.copy(revision = fact.revision + 1, inputRevision = fact.inputRevision + 1)
                        else -> fact.copy(revision = fact.revision + 1, ownerEpoch = "replacement-owner")
                    }
                    f.receive(Frame(Wire.RECEIVER_USABILITY, payload = next.bytes()))
                }
                "binder-death" -> (bridge as Binder).die()
                "binder-replace" -> f.holdInstall(runtime.bind())
                "clear" -> runtime.store.clear("app")
                "replace" -> runtime.store.replace(savedHold)
                "stop" -> f.closeSession()
            }
            if (cause != "stop") f.holdFlush(runtime) else runtime.holdDrain()
            val input = f.get("inputControls") as AnchorSessionInput
            check(!input.anchor.holding) { "$cause left original hold owned" }
            check(events.take(2).map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL)) { "$cause: $events" }
            check(events[0].contactId == events[1].contactId)
            // Ordinary input may reuse its own numeric contact ID after the original CANCEL.
            check(events.drop(1).takeWhile { it.phase != Touch.Phase.DOWN }.none { it.phase == Touch.Phase.UP })
            if (cause != "stop") check(f.holdMessages().filterIsInstance<AnchorMessage.HeldState>().last().state == AnchorHoldState.CANCELED)
            println("PASS actual Session/Binder $cause retires original held contact with CANCEL, no successful original UP")
        }
        for (clear in listOf(false, true)) Fixture().use { f ->
            setup(f)
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val observer = runtime.store.observe("app") { entered.countDown(); check(resume.await(3, TimeUnit.SECONDS)) }
            val events = f.holdEvents(); f.holdPress(runtime, 1)
            val input = f.get("inputControls") as AnchorSessionInput
            val metadata = input.anchor.heldSetting()!!
            val storageField = AppAnchorStore::class.java.getDeclaredField("storage").apply { isAccessible = true }
            val storage = storageField.get(runtime.store) as AppAnchorStorage
            val mutator = Thread { if (clear) runtime.store.clear("app") else runtime.store.replace(savedHold) }.apply { start() }
            try {
                check(entered.await(2, TimeUnit.SECONDS)); check(!runtime.store.isCurrent(metadata) && input.anchor.holding)
                storageField.set(runtime.store, object : AppAnchorStorage {
                    override fun read(packageName: String): AppAnchor? = error("stale held cancellation cannot read values")
                    override fun write(packageName: String, anchor: AppAnchor?) = storage.write(packageName, anchor)
                })
                UiTargetAccess.currentReader = UiTargetReader(object : UiAccess {
                    override val canRead: Boolean get() = error("stale held cancellation cannot read nodes")
                    override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> = error("no new target")
                }, { 0 })
                f.holdPress(runtime, 2)
                check(!input.anchor.holding) // Local cancellation handling completed through original Binder.
                check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
                check((f.holdMessages().last() as AnchorMessage.Feedback).status == AnchorFeedback.RETIRED)
                check(f.holdMessages().filterIsInstance<AnchorMessage.HeldState>().last().state == AnchorHoldState.CANCELED)
            } finally { storageField.set(runtime.store, storage); resume.countDown(); mutator.join(2000); observer.close() }
            check(!mutator.isAlive); runtime.holdDrain(); UiTargetAccess.currentReader = holdReader()
            f.holdPress(runtime, 3)
            check((f.holdMessages().last() as AnchorMessage.Feedback).status ==
                if (clear) AnchorFeedback.NO_ANCHOR else AnchorFeedback.HOLD_SUBMITTED)
            println("PASS actual Session/Binder committed ${if (clear) "Clear" else "replacement"} with blocked observer cancels/ACKs before later use without saved-record/node lookup")
        }
        Fixture().use { old ->
            setup(old); val oldEvents = old.holdEvents(); old.holdPress(runtime, 1)
            old.closeSession(); runtime.holdDrain()
            Fixture(ownerEpoch = "replacement-owner").use { fresh ->
                setup(fresh); val freshEvents = fresh.holdEvents()
                check(freshEvents.isEmpty()) // A successor Session never inherits or replays old Hold.
                check(oldEvents.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
                fresh.holdPress(runtime, 2); fresh.holdPress(runtime, 3)
                check(freshEvents.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
                check(oldEvents.size == 2)
                println("PASS actual original owner-epoch/Session close cancels old contact; replacement starts fresh with no replay or orphan release")
            }
        }
        Fixture().use { f ->
            setup(f)
            val events = CopyOnWriteArrayList<Touch>()
            (f.get("inputControls") as AnchorSessionInput).bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                override fun geometry(next: Geometry) = Unit
                override fun touch(event: Touch): Boolean { events.add(event); return event.phase != Touch.Phase.UP }
                override fun tap(event: Tap) = true
                override fun scroll(event: Scroll) = true
            })
            f.holdPress(runtime, 1); f.holdPress(runtime, 2)
            check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP, Touch.Phase.CANCEL))
            check((f.holdMessages().last() as AnchorMessage.Feedback).status == AnchorFeedback.INPUT_REJECTED)
            check(f.holdMessages().filterIsInstance<AnchorMessage.HeldState>().map { it.state } ==
                listOf(AnchorHoldState.HELD, AnchorHoldState.CANCELED))
            check(!(f.get("inputControls") as AnchorSessionInput).holding)
            println("PASS actual original release rejection cancels once, never reports RELEASED or falls through to new DOWN")
        }
        for (failure in listOf("up-throw", "state-transport", "blocked-down-stop")) Fixture().use { f ->
            setup(f)
            val events = CopyOnWriteArrayList<Touch>()
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val input = f.get("inputControls") as AnchorSessionInput
            input.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                override fun geometry(next: Geometry) = Unit
                override fun touch(event: Touch): Boolean {
                    events.add(event)
                    if (event.phase == Touch.Phase.UP && failure == "up-throw") throw IOException("original UP failed")
                    if (event.phase == Touch.Phase.DOWN && failure == "blocked-down-stop") {
                        entered.countDown()
                        while (resume.count > 0) try { resume.await() } catch (_: InterruptedException) {}
                    }
                    return true
                }
                override fun tap(event: Tap) = true
                override fun scroll(event: Scroll) = true
            })
            try {
                if (failure == "blocked-down-stop") {
                    f.receive(Frame(Wire.FUNCTION_INTENT, payload = f.holdIntent(1).bytes())); f.holdFunctions()
                    check(entered.await(2, TimeUnit.SECONDS))
                    val closer = Executors.newSingleThreadExecutor()
                    try { closer.submit { f.closeSession() }.get(2, TimeUnit.SECONDS) } finally { closer.shutdownNow() }
                    check(resume.count == 1L)
                    check(f.peer.released && f.control.released && !f.child.alive)
                    resume.countDown(); runtime.holdDrain()
                } else {
                    f.holdPress(runtime, 1)
                    if (failure == "up-throw") {
                        f.receive(Frame(Wire.FUNCTION_INTENT, payload = f.holdIntent(2).bytes())); runtime.holdDrain()
                    } else {
                        f.set("output", DataOutputStream(object : OutputStream() {
                            override fun write(value: Int) {
                                check(!Thread.holdsLock(f.get("controlLock")!!))
                                entered.countDown(); throw IOException("original state transport failed")
                            }
                        }))
                        f.receive(Frame(Wire.SCROLL, payload = Scroll(Tap(10, 20, holdGeometry), 160).bytes()))
                        check(entered.await(2, TimeUnit.SECONDS))
                    }
                    val worker = FunctionWork::class.java.getDeclaredField("workerThread").apply { isAccessible = true }
                        .get(f.get("functionWork")) as Thread
                    worker.join(3000); check(!worker.isAlive) // Failure's actual close turn has completed.
                    runtime.holdDrain()
                }
                check(f.peer.released && f.control.released && !f.child.alive)
                check(!input.anchor.holding && !input.holding)
                check(events.count { it.phase == Touch.Phase.CANCEL } == 1)
                check(f.holdMessages().filterIsInstance<AnchorMessage.HeldState>().none { it.state == AnchorHoldState.RELEASED })
                println("PASS actual Session $failure retires original input and releases sockets/child without successful release or replay")
            } finally { resume.countDown() }
        }
        Fixture().use { f ->
            setup(f); val events = f.holdEvents(); f.holdPress(runtime, 1)
            f.holdPress(runtime, 2, FunctionIntentKind.REGISTER)
            val offer = f.holdMessages().filterIsInstance<AnchorMessage.Offer>().single()
            check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
            val chooser = offer.intent.context.usability.copy(revision = 100, inputRevision = 2, mode = ReceiverUsabilityMode.LOCAL_PANEL)
            f.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Open(offer.transactionId, offer.intent, chooser).bytes()))
            f.receive(Frame(Wire.RECEIVER_USABILITY, payload = chooser.bytes()))
            f.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Choice(offer.transactionId, offer.intent, AnchorChoice.HOLD).bytes()))
            f.holdFlush(runtime)
            check((f.holdMessages().last() as AnchorMessage.Result).result == AnchorRegistrationResult.SAVED_HOLD)
            check(runtime.store.read("app")?.mode == AnchorMode.HOLD)
            check(runtime.store.read("app")?.descriptor?.resourceId == "app:id/action")
            check(events.none { it.phase == Touch.Phase.UP })
            println("PASS actual reflected121 Provider REGISTER cancels old hold without invalidating original Offer -> OPEN -> exact chooser fact -> saved Choice/Result")
        }
    } finally { UiTargetAccess.currentReader = null; runtime.invalidateBinding(); runtime.holdDrain(); directory.deleteRecursively() }
    println("PASS actual Hold Session source composition; framework Binder/Android handles doubled; no physical app-effect claim")
}
