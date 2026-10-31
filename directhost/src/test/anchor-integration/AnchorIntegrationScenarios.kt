package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.*

/** Changes only externally observed Android windows, never the production resolution algorithm. */
private class Windows {
    data class Window(val id: Int = 7, val pkg: String = "app", val type: Int = 1,
        val active: Boolean = true, val focused: Boolean = true,
        val resource: String? = "app:id/action", val bounds: UiBounds = UiBounds(300, 400, 340, 440))
    var current = listOf(Window())
    var reads = 0
    val reader = UiTargetReader(object : UiAccess {
        override val canRead = true
        override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
            reads++
            return mapOf(47 to current.map { value -> object : UiAccessWindow {
                override val metadata = UiWindowMetadata(47, value.id, value.type, 0, value.active, value.focused, UiBounds(0, 0, 480, 640))
                override fun root() = object : UiAccessNode {
                    override val packageName = value.pkg
                    override val windowId = value.id
                    override val childCount = 0
                    override fun child(index: Int): UiAccessNode? = null
                    override fun observation(ordinal: Int) = UiTargetObservation(ordinal, value.id, value.pkg,
                        value.resource, "Button", value.bounds, listOf(16), true, false, true, true)
                    override fun close() = Unit
                }
                override fun close() = Unit
            } })
        }
    }, { 0 })
}

/** Composes original production Phone and Glasses owners; Android/TLS/view and MotionTarget are boundaries. */
private class Joined(val runtime: AnchorAppRuntime) : AutoCloseable {
    val phone = Fixture()
    val glasses = HoldReceiverRig()
    val windows = Windows()
    val events = CopyOnWriteArrayList<Touch>()
    val ordinary = mutableListOf<String>()
    val fromPhone = mutableListOf<Frame>()
    val fromGlasses = mutableListOf<Frame>()
    val messages get() = fromPhone.filter { it.channel == Wire.ANCHOR }.map { AnchorMessage.from(it.payload) }
    val input get() = phone.get("inputControls") as AnchorSessionInput
    val wiring get() = glasses.wiring
    val display: ManagedProjectionDisplay
    var component = "app/.Main"
    val launches: ProjectedAppLaunch
    val appLaunches = mutableListOf<String>()
    var cancelBehavior: (Touch) -> Boolean = { true }
    private var phoneOffset = 0
    private var time = android.os.SystemClock.uptimeMillis()
    init {
        display = phone.holdManaged(false)
        launches = phone.adapter(object : ProjectionLaunchAdapter {
            override fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult {
                appLaunches.add(packageName)
                component = if (home) ProjectionLaunchCommands.HOME_COMPONENT else "$packageName/.Main"
                return ProjectionCommandResult(0, "Status: ok\n")
            }
            override fun foreground(displayId: Int) = component.substringBefore('/')
            override fun foregroundComponent(displayId: Int) = component
            override fun close() = Unit
        })
        phone.holdInstall(runtime.bind()); runtime.holdDrain()
        check(runtime.registrationProviderState == AnchorAppRuntime.ProviderState.INSTALLED)
        runtime.store.replace(savedHold); runtime.holdDrain()
        UiTargetAccess.currentReader = windows.reader
        input.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
            override fun geometry(next: Geometry) = Unit
            override fun touch(event: Touch): Boolean {
                events.add(event)
                return if (event.phase == Touch.Phase.CANCEL) cancelBehavior(event) else true
            }
            override fun tap(event: Tap): Boolean { ordinary.add("tap"); return true }
            override fun scroll(event: Scroll): Boolean { ordinary.add("scroll"); return true }
        })
        wiring.move(10f, 20f); pump()
        android.widget.Toast.messages.clear(); Toast.messages.clear()
    }
    fun pump() {
        repeat(24) {
            val sent = glasses.frames(); fromGlasses.addAll(sent); sent.forEach(phone::receive)
            phone.holdFlush(runtime)
            launches.snapshot(true) // Serial launch observation barrier, not a synthetic navigation decision.
            phone.holdFlush(runtime)
            val bytes = phone.peer.sink.toByteArray()
            val reader = DataInputStream(bytes.copyOfRange(phoneOffset, bytes.size).inputStream())
            val replies = buildList { while (reader.available() > 0) add(Wire.readFrame(reader)) }
            phoneOffset = bytes.size; fromPhone.addAll(replies); replies.forEach(glasses::receive)
            glasses.drain()
            if (sent.isEmpty() && replies.isEmpty()) return
        }
        error("Original cross-feature queues did not quiesce")
    }
    fun press() { wiring.short(time); time += 1000; pump() }
    fun register() { wiring.move(320f, 420f); pump(); wiring.register(time); wiring.releaseRegister(time); time += 1000; pump() }
    fun hold() {
        press(); check(input.anchor.holding && wiring.heldVisible)
        check(events.single().phase == Touch.Phase.DOWN)
    }
    fun noSuccessfulRelease() {
        check(messages.filterIsInstance<AnchorMessage.HeldState>().none { it.state == AnchorHoldState.RELEASED })
        check(messages.filterIsInstance<AnchorMessage.Feedback>().none { it.status == AnchorFeedback.RELEASE_SUBMITTED })
    }
    fun canceled() {
        check(!input.anchor.holding && !wiring.heldVisible)
        check(events.take(2).map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        check(events[0].contactId == events[1].contactId && events[0].target == events[1].target)
        noSuccessfulRelease()
    }
    fun replayOldHeld() {
        val held = messages.filterIsInstance<AnchorMessage.HeldState>().first { it.state == AnchorHoldState.HELD }
        glasses.receive(Frame(Wire.ANCHOR, payload = held.bytes()))
        check(!wiring.heldVisible)
    }
    fun retain(name: String) {
        val out = File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"))
        for ((side, frames) in listOf("phone" to fromPhone, "glasses" to fromGlasses))
            File(out, "$name-$side.bin").outputStream().use { sink ->
                val writer = DataOutputStream(sink); frames.forEach { Wire.writeFrame(writer, it) }
            }
        File(out, "$name-observation.txt").writeText("touches=$events\nordinary=$ordinary\nappLaunches=$appLaunches\n" +
            "heldVisible=${wiring.heldVisible}\nfeedback=$messages\nregistrationToasts=${Toast.messages}\nfeedbackToasts=${android.widget.Toast.messages}\n")
    }
    override fun close() {
        phone.close(); glasses.close(); runtime.invalidateBinding(); runtime.holdDrain()
        UiTargetAccess.currentReader = null
    }
}

fun main() {
    val directory = Files.createTempDirectory("eyeprojector-anchor-integration-").toFile()
    val runtime = AnchorAppRuntime(android.content.Context(directory)); runtime.holdDrain()
    var count = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, test: (Joined) -> Unit) {
        Joined(runtime).use { joined ->
            try { test(joined); count++; println("PASS $name actual cross-feature source composition") }
            catch (failure: Throwable) {
                failures.add(name); println("FAIL $name: $failure"); failure.printStackTrace(System.out)
            }
            finally { joined.retain(name) }
        }
    }
    try {
        for (choice in AnchorRegistrationAction.entries) scenario("explicit-$choice") { j ->
            val before = runtime.store.snapshot("app")
            j.register(); check(j.wiring.active() && j.events.isEmpty())
            val open = j.fromGlasses.indexOfFirst { it.channel == Wire.ANCHOR && AnchorMessage.from(it.payload) is AnchorMessage.Open }
            check(open >= 0 && j.fromGlasses[open + 1].channel == Wire.RECEIVER_USABILITY)
            j.wiring.choose(choice); j.pump(); check(!j.wiring.active() && j.events.isEmpty())
            if (choice == AnchorRegistrationAction.CANCEL) check(runtime.store.snapshot("app") == before)
            else {
                check(runtime.store.read("app")?.mode == if (choice == AnchorRegistrationAction.CLICK) AnchorMode.CLICK else AnchorMode.HOLD)
                j.wiring.move(1f, 1f); j.pump(); j.press()
                check(j.events.first().target == Tap(320, 420, holdGeometry))
                if (choice == AnchorRegistrationAction.CLICK) check(j.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
                else { check(j.wiring.heldVisible); j.press(); check(!j.wiring.heldVisible) }
                val result = j.messages.filterIsInstance<AnchorMessage.Result>().last()
                val notices = Toast.messages.toList()
                j.glasses.receive(Frame(Wire.ANCHOR, payload = result.bytes()))
                check(Toast.messages == notices) // A completed result does not repeat its saved feedback.
            }
        }
        for (ordinary in listOf("tap", "scroll")) scenario("hold-$ordinary") { j ->
            j.hold()
            if (ordinary == "tap") check(j.wiring.tap(100f, 120f)) else check(j.wiring.scroll(100f, 120f))
            j.pump(); j.canceled(); j.replayOldHeld(); check(j.ordinary == listOf(ordinary))
            check(j.windows.reads == 1) // Ordinary handoff needs no target rediscovery.
        }
        scenario("ime-current-app-and-relayout") { j ->
            j.windows.current = listOf(Windows.Window(), Windows.Window(id = 90, pkg = "keyboard", type = 2))
            j.hold(); check(j.events.single().target == Tap(320, 420, holdGeometry))
            UiTargetAccess.event(UiTargetAccess.WindowChange(47, 90, false)); j.pump()
            check(j.input.anchor.holding && j.wiring.heldVisible) // Unrelated IME event does not retire the app contact.
            j.windows.current = listOf(Windows.Window(bounds = UiBounds(100, 200, 140, 240)), Windows.Window(id = 90, pkg = "keyboard", type = 2))
            UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true)); j.pump(); j.canceled(); j.replayOldHeld()
            j.press(); check(j.events.last().phase == Touch.Phase.DOWN && j.events.last().target == Tap(120, 220, holdGeometry))
        }
        for (replacement in listOf("modal", "ambiguous")) scenario("registration-$replacement") { j ->
            j.register(); check(j.wiring.active())
            val original = runtime.store.snapshot("app")
            val offer = j.messages.filterIsInstance<AnchorMessage.Offer>().last()
            when (replacement) {
                "modal" -> j.windows.current = listOf(Windows.Window(active = false, focused = false), Windows.Window(id = 8, resource = "app:id/modal"))
                "ambiguous" -> j.windows.current = listOf(Windows.Window(), Windows.Window(id = 8))
            }
            j.wiring.choose(AnchorRegistrationAction.CLICK); j.pump()
            check(!j.wiring.active() && runtime.store.snapshot("app") == original && j.events.isEmpty())
            check(j.messages.filterIsInstance<AnchorMessage.Result>().last().result == AnchorRegistrationResult.UNAVAILABLE)
            check(Toast.messages.last() == "Anchor unavailable")
            j.glasses.receive(Frame(Wire.ANCHOR, payload = offer.bytes())); j.pump()
            check(!j.wiring.active() && j.events.isEmpty()) // Delayed offer cannot adopt the post-chooser revision.
        }
        scenario("activity-window-replacement") { j ->
            j.hold()
            j.component = "app/.Other"
            j.windows.current = listOf(Windows.Window(id = 8))
            UiTargetAccess.event(UiTargetAccess.WindowChange(47, 8, true)); j.pump(); j.canceled()
            j.register(); check(j.wiring.active())
            val setting = runtime.store.snapshot("app")
            j.component = "app/.Third"
            j.windows.current = listOf(Windows.Window(id = 9))
            UiTargetAccess.event(UiTargetAccess.WindowChange(47, 9, true)); j.pump()
            j.wiring.choose(AnchorRegistrationAction.CLICK); j.pump()
            check(!j.wiring.active() && runtime.store.snapshot("app") == setting && j.events.size == 2)
            check(j.messages.filterIsInstance<AnchorMessage.Result>().last().result == AnchorRegistrationResult.CANCELED)
        }
        scenario("home-grid-panel-settings-back-launcher") { j ->
            j.hold(); j.wiring.home(); j.pump(); j.canceled()
            check(j.appLaunches == listOf(ProjectionLaunchCommands.HOME_PACKAGE) && !j.wiring.presentation.panelVisible)
            j.wiring.home(); j.pump()
            check(j.wiring.presentation.panelVisible && j.wiring.connectionView.visibility == View.VISIBLE)
            check(j.fromPhone.any { it.channel == Wire.RECEIVER_INPUT && ReceiverInput.from(it.payload).kind == ReceiverInputKind.PANEL })
            j.wiring.settings(); j.pump(); check(j.wiring.hidPanel.visibility == View.VISIBLE)
            val navigations = j.fromGlasses.count { it.channel == Wire.NAVIGATE }
            j.wiring.home(); j.pump()
            check(j.wiring.hidPanel.visibility == View.GONE && j.wiring.presentation.panelVisible)
            check(j.fromGlasses.count { it.channel == Wire.NAVIGATE } == navigations)
            j.wiring.settings(); j.pump(); j.wiring.back(); j.pump()
            check(j.wiring.hidPanel.visibility == View.GONE && j.wiring.presentation.panelVisible)
            j.wiring.home(); j.pump()
            check(j.wiring.launcherExits == 1 && !j.wiring.resumed && !j.wiring.bindingValid)
        }
        for (navigation in listOf("back", "lock", "chooser-back", "chooser-home")) scenario(navigation) { j ->
            j.hold()
            if (navigation.startsWith("chooser")) { j.register(); check(j.wiring.active()) }
            when (navigation) {
                "lock" -> j.wiring.lock()
                "chooser-home" -> j.wiring.home()
                else -> j.wiring.back()
            }
            j.pump(); j.canceled(); check(!j.wiring.active())
            if (navigation == "lock") check(j.wiring.shellPadContact.sleeps == 1)
            if (navigation == "chooser-back") check(j.fromGlasses.none { it.channel == Wire.NAVIGATE })
        }
        for (managed in listOf(false, true)) scenario("scale-${if (managed) "managed" else "legacy"}") { j ->
            j.hold()
            if (managed) {
                val factory: ((String, Int?, Int?) -> Unit) -> ManagedProjectionDisplay = { j.display }
                j.phone.set("managedDisplayFactory", factory)
            }
            try {
                (j.phone.get("scale") as LiveScale).observeManagedLayout(holdGeometry)
                j.phone.receive(Frame(Wire.SCALE_REQUEST, stamp = 7L, payload = ScaleRequest(1.5).bytes()))
                j.pump(); j.canceled(); j.replayOldHeld()
                check(j.fromPhone.filter { it.channel == Wire.SCALE_STATE }.map { ScaleState.from(it.payload) }.last().status == ScaleStatus.PENDING)
                if (managed) check(j.display.uiInputSnapshot() == null)
            } finally { j.phone.set("managedDisplayFactory", null) }
        }
        for (clear in listOf(false, true)) scenario("delayed-setting-${if (clear) "clear" else "replace"}") { j ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val observer = runtime.store.observe("app") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            try {
                j.hold()
                val setting = j.input.anchor.heldSetting()!!
                val mutator = Thread { if (clear) runtime.store.clear("app") else runtime.store.replace(savedHold) }.apply { start() }
                try {
                    check(entered.await(2, TimeUnit.SECONDS)); check(!runtime.store.isCurrent(setting))
                    check(j.input.anchor.holding && j.wiring.heldVisible) // Committed preference alone is not retirement ACK.
                    j.noSuccessfulRelease(); j.press(); j.canceled(); j.replayOldHeld()
                    check(j.events.size == 2 && j.messages.filterIsInstance<AnchorMessage.Feedback>().last().status == AnchorFeedback.RETIRED)
                } finally { release.countDown(); mutator.join(2000); check(!mutator.isAlive) }
                j.pump(); check(j.events.size == 2)
            } finally { observer.close(); release.countDown() }
        }
        for (failure in listOf("rejected", "failed")) scenario("modal-cleanup-$failure") { j ->
            j.hold()
            j.cancelBehavior = { if (failure == "failed") throw IOException("original CANCEL boundary failed") else false }
            UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true)); j.pump()
            j.canceled(); j.replayOldHeld()
            check(j.events.size == 2)
            if (failure == "failed") check(runtime.coordinator.lastFailure != null)
        }
        scenario("layout-pending-cleanup") { j ->
            j.hold()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            j.cancelBehavior = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); true }
            try {
                j.wiring.layout(640, 480)
                val sent = j.glasses.frames(); j.fromGlasses.addAll(sent)
                val receiver = Executors.newSingleThreadExecutor()
                try {
                    val receiving = receiver.submit { sent.forEach(j.phone::receive) }
                    check(entered.await(2, TimeUnit.SECONDS))
                    check(!j.wiring.heldVisible && !receiving.isDone)
                    // Local hiding is not an original completion or successful UP acknowledgment.
                    j.noSuccessfulRelease()
                    check(j.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
                    release.countDown(); receiving.get(2, TimeUnit.SECONDS)
                    j.pump(); j.canceled(); j.replayOldHeld()
                } finally { release.countDown(); receiver.shutdownNow() }
            } finally { release.countDown() }
        }
        scenario("binder-replacement") { j ->
            j.hold(); val old = j.messages.filterIsInstance<AnchorMessage.HeldState>().first()
            j.phone.holdInstall(runtime.bind()); j.pump(); j.canceled(); j.replayOldHeld()
            j.press(); check(j.input.anchor.holding && j.wiring.heldVisible)
            val latest = j.events.last()
            j.glasses.receive(Frame(Wire.ANCHOR, payload = old.bytes()))
            check(j.input.anchor.holding && j.wiring.heldVisible && j.events.last() == latest)
        }
        var stoppedState: AnchorMessage.HeldState? = null
        scenario("stop-original-transport") { j ->
            j.hold(); stoppedState = j.messages.filterIsInstance<AnchorMessage.HeldState>().first()
            j.glasses.receive(Frame(Wire.END)); j.phone.closeSession(); runtime.holdDrain()
            check(!j.wiring.heldVisible && !j.input.anchor.holding)
            check(j.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
            j.noSuccessfulRelease()
        }
        scenario("successor-session-old-held-and-feedback") { j ->
            val old = stoppedState!!
            check(j.glasses.session.observationUsability()?.incarnation != old.intent.context.usability.incarnation)
            android.widget.Toast.messages.clear()
            j.glasses.receive(Frame(Wire.ANCHOR, payload = old.bytes()))
            j.glasses.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Feedback(old.transactionId, old.intent, AnchorFeedback.NO_ANCHOR).bytes()))
            check(!j.wiring.heldVisible && j.events.isEmpty())
            check(android.widget.Toast.messages.isEmpty()) { "Old incarnation feedback rendered on successor: ${android.widget.Toast.messages}" }
        }
        scenario("current-unavailable-feedback") { j ->
            runtime.store.clear("app"); runtime.holdDrain(); j.press()
            check(j.events.isEmpty() && !j.wiring.heldVisible)
            check(j.messages.filterIsInstance<AnchorMessage.Feedback>().last().status == AnchorFeedback.NO_ANCHOR)
            check(android.widget.Toast.messages == listOf("Hover a target and long-press Function to set an Anchor"))
        }
        scenario("registration-pre-offer-unavailable") { j ->
            j.windows.current = listOf(Windows.Window(resource = null))
            val before = runtime.store.snapshot("app")
            j.register()
            check(!j.wiring.active() && j.events.isEmpty() && runtime.store.snapshot("app") == before)
            check(j.messages.none { it is AnchorMessage.Offer })
            check(j.messages.filterIsInstance<AnchorMessage.Feedback>().last().status == AnchorFeedback.UNAVAILABLE)
            check(android.widget.Toast.messages == listOf("Anchor unavailable"))
        }
        scenario("current-feedback-after-modal-target-loss") { j ->
            j.windows.current = listOf(Windows.Window(active = false, focused = false), Windows.Window(id = 8, resource = "app:id/modal"))
            j.press()
            check(j.events.isEmpty() && !j.wiring.heldVisible)
            check(j.messages.filterIsInstance<AnchorMessage.Feedback>().last().status == AnchorFeedback.UNAVAILABLE)
            check(android.widget.Toast.messages == listOf("Anchor unavailable"))
        }
        scenario("current-feedback-status-and-head-motion") { j ->
            j.hold()
            val original = j.messages.filterIsInstance<AnchorMessage.HeldState>().first()
            j.wiring.move(450f, 600f); j.pump()
            for (status in AnchorFeedback.entries) {
                android.widget.Toast.messages.clear()
                j.glasses.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Feedback(original.transactionId, original.intent, status).bytes()))
                val expected = when (status) {
                    AnchorFeedback.NO_ANCHOR -> listOf("Hover a target and long-press Function to set an Anchor")
                    AnchorFeedback.UNAVAILABLE, AnchorFeedback.RETIRED, AnchorFeedback.INPUT_REJECTED, AnchorFeedback.HOLD_MODE -> listOf("Anchor unavailable")
                    else -> emptyList()
                }
                check(android.widget.Toast.messages == expected) { "$status lost current feedback" }
            }
            check(j.events.size == 1 && j.wiring.heldVisible) // Feedback and aim changes own no contact or app ACK.
        }
        scenario("stale-feedback-identity-equal-numeric-revision") { j ->
            j.hold()
            val original = j.messages.filterIsInstance<AnchorMessage.HeldState>().first()
            val context = original.intent.context
            for (fact in listOf(
                context.usability.copy(ownerEpoch = "retired-owner"),
                context.usability.copy(incarnation = ReceiverIncarnation("retired-incarnation", context.usability.incarnation.sequence)),
                context.usability.copy(inputRevision = context.usability.inputRevision + 1),
                context.usability.copy(mode = ReceiverUsabilityMode.LOCAL_PANEL))) {
                android.widget.Toast.messages.clear()
                val intent = original.intent.copy(context = context.copy(usability = fact))
                j.glasses.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Feedback(original.transactionId, intent, AnchorFeedback.UNAVAILABLE).bytes()))
                check(android.widget.Toast.messages.isEmpty()) { "Stale original identity rendered: $fact" }
            }
            android.widget.Toast.messages.clear()
            val wrongGeometry = original.intent.copy(context = context.copy(pointer = context.pointer.copy(geometry = Geometry(640, 480))))
            j.glasses.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Feedback(original.transactionId, wrongGeometry, AnchorFeedback.UNAVAILABLE).bytes()))
            check(android.widget.Toast.messages.isEmpty())
            check(j.events.size == 1 && j.wiring.heldVisible)
        }
        for (transition in listOf("panel", "settings", "geometry", "pause")) scenario("late-unavailable-after-$transition") { j ->
            j.hold()
            val original = j.messages.filterIsInstance<AnchorMessage.HeldState>().first()
            when (transition) {
                "panel", "settings" -> j.wiring.showControlPanel()
                "geometry" -> j.wiring.layout(640, 480)
                "pause" -> j.wiring.onPause()
            }
            j.pump(); j.canceled()
            if (transition == "settings") { j.wiring.settings(); j.pump() }
            android.widget.Toast.messages.clear()
            j.glasses.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.Feedback(original.transactionId, original.intent, AnchorFeedback.UNAVAILABLE).bytes()))
            check(android.widget.Toast.messages.isEmpty()) { "Stale original feedback displayed after $transition transition: ${android.widget.Toast.messages}" }
        }

    } finally { runtime.invalidateBinding(); runtime.holdDrain(); UiTargetAccess.currentReader = null; directory.deleteRecursively() }
    check(failures.isEmpty()) { "Failed integration scenarios: $failures" }
    println("PASS $count new joined integration scenarios; host boundaries only, no app action acknowledgment or native115 capability claim")
}
