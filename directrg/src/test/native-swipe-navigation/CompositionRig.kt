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
internal class Windows {
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
internal class Joined(val runtime: AnchorAppRuntime) : AutoCloseable {
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
    var upBehavior: (Touch) -> Boolean = { true }
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
                return when(event.phase) { Touch.Phase.CANCEL -> cancelBehavior(event); Touch.Phase.UP -> upBehavior(event); else -> true }
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
            // Exact source reply queue barrier; old frozen before-source has no such field.
            try {
                val replies=phone.get("controlReplies") as com.code2hack.eyeprojector.directhost.input.FunctionWork
                val done=CountDownLatch(1);replies.submit{done.countDown()};check(done.await(3,TimeUnit.SECONDS))
            } catch (_:NoSuchFieldException) { }
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
    fun register() {
        wiring.move(320f, 420f); pump()
        val at=time; wiring.register(at); time+=1000; pump()
        if(!wiring.active()) { wiring.releaseRegister(at); pump() }
    }
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
