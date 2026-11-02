package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Both real Session endpoints, Binder-value adapter, reflected provider, FIFO, reader and store. */
fun main() {
    val directory = Files.createTempDirectory("eyeprojector-registration-app-").toFile()
    val evidence = File(System.getenv("EYEPROJECTOR_REGISTRATION_EVIDENCE")).apply { mkdirs() }
    val runtime = AnchorAppRuntime(android.content.Context(directory))
    fun appDrain() { val done = CountDownLatch(1); runtime.onLane { done.countDown() }; check(done.await(3, TimeUnit.SECONDS)) }
    appDrain()
    check(runtime.registrationProviderState == AnchorAppRuntime.ProviderState.INSTALLED) {
        "Actual121 provider reflection failed: ${runtime.registrationInstallationFailure}"
    }
    val old = AppAnchor(AnchorTargetDescriptor("app", null, 1, "app:id/previous", "Button"), AnchorMode.CLICK)
    var checks = 0
    try {
        for (scenario in listOf("click", "hold", "phone-tap", "cancel", "back", "external-cancel", "window-loss", "geometry-loss", "missing")) {
            Fixture().use { phone -> RegistrationReceiverRig().use { glasses ->
                phone.anchorManaged(false); phone.install(runtime.bind()); appDrain(); runtime.store.replace(old); appDrain()
                var window = 7
                var present = scenario != "missing"
                UiTargetAccess.currentReader = UiTargetReader(object : UiAccess {
                    override val canRead = true
                    override fun windowsOnAllDisplays() = mapOf(47 to listOf(object : UiAccessWindow {
                        override val metadata get() = UiWindowMetadata(47, window, 1, 0, true, true, UiBounds(0, 0, 480, 640))
                        override fun root() = object : UiAccessNode {
                            override val packageName = "app"; override val windowId get() = window; override val childCount = 0
                            override fun child(index: Int): UiAccessNode? = null
                            override fun observation(ordinal: Int): UiTargetObservation? = if (!present) null else
                                UiTargetObservation(ordinal, window, "app", "app:id/action", "Button", UiBounds(300, 400, 340, 440),
                                    listOf(16), true, false, true, true)
                            override fun close() = Unit
                        }
                        override fun close() = Unit
                    }))
                }) { 0L }
                val appInput = mutableListOf<String>()
                (phone.get("inputControls") as AnchorSessionInput).bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                    override fun geometry(next: Geometry) = Unit
                    override fun touch(event: Touch): Boolean { appInput.add("touch:${event.phase}"); return true }
                    override fun tap(event: Tap): Boolean { appInput.add("tap"); return true }
                    override fun scroll(event: Scroll): Boolean { appInput.add("scroll"); return true }
                })
                val fromGlasses = mutableListOf<Frame>(); val fromPhone = mutableListOf<Frame>()
                var phoneOffset = 0
                fun pump() {
                    repeat(8) {
                        val sent = glasses.frames(); fromGlasses.addAll(sent); sent.forEach(phone::receive)
                        phone.functions(); appDrain()
                        val bytes = phone.peer.sink.toByteArray()
                        val input = DataInputStream(bytes.copyOfRange(phoneOffset, bytes.size).inputStream())
                        val reply = buildList { while (input.available() > 0) add(Wire.readFrame(input)) }
                        phoneOffset = bytes.size; fromPhone.addAll(reply); reply.forEach(glasses::receive)
                        glasses.drain()
                        if (sent.isEmpty() && reply.isEmpty()) return
                    }
                    error("Owned registration transport did not quiesce")
                }
                Toast.messages.clear()
                glasses.register(); pump()
                glasses.releaseFunction(); pump() // Release arrives after the chooser's own mode transition.
                check(glasses.produced == listOf(FunctionIntentKind.REGISTER))
                check(runtime.store.read("app") == old && appInput.isEmpty())
                if (scenario == "missing") {
                    check(!glasses.wiring.active())
                    check(fromPhone.filter { it.channel == Wire.ANCHOR }.map { AnchorMessage.from(it.payload) }
                        .any { it is AnchorMessage.Feedback && it.status == AnchorFeedback.UNAVAILABLE }) {
                        "Missing hovered target did not publish actual unavailable feedback"
                    }
                } else {
                    check(glasses.wiring.active()) { "Actual OFFER/OPEN did not leave chooser active in $scenario; ${runtime.coordinator.lastFailure}" }
                    val anchor = fromGlasses.filter { it.channel == Wire.ANCHOR }.map { AnchorMessage.from(it.payload) }
                    val open = anchor.filterIsInstance<AnchorMessage.Open>().single()
                    val index = fromGlasses.indexOfFirst { it.channel == Wire.ANCHOR && AnchorMessage.from(it.payload) is AnchorMessage.Open }
                    check(fromGlasses[index + 1].channel == Wire.RECEIVER_USABILITY)
                    check(ReceiverUsability.from(fromGlasses[index + 1].payload) == open.chooser)
                    check(glasses.wiring.touchThrough() && glasses.wiring.scrollThrough() && appInput.isEmpty())
                    when (scenario) {
                        "click" -> glasses.wiring.choose(AnchorRegistrationAction.CLICK)
                        "hold" -> glasses.wiring.choose(AnchorRegistrationAction.HOLD)
                        "phone-tap" -> {
                            glasses.wiring.localInput.onTouch = { event ->
                                if (event.phase == Touch.Phase.UP) glasses.wiring.choose(AnchorRegistrationAction.HOLD)
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            val edge = PhoneHidEdge(PhoneHidAction.TAP, PhoneHidPhase.DOWN, 1, 67, 90, now, now)
                            phone.hid(8, edge); phone.hid(8, edge.copy(phase = PhoneHidPhase.UP, eventTimeMs = now + 20))
                        }
                        "cancel" -> glasses.wiring.choose(AnchorRegistrationAction.CANCEL)
                        "back" -> glasses.wiring.back()
                        "external-cancel" -> { runtime.store.replace(old.copy(mode = AnchorMode.HOLD)); appDrain(); glasses.wiring.back() }
                        "window-loss" -> { UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true)); appDrain() }
                        "geometry-loss" -> {
                            val managed = phone.get("managedDisplay")!!
                            managed.javaClass.getDeclaredField("observedSource").apply { isAccessible = true }.set(managed,
                                ManagedProjectionDisplay.SourceObservation(Geometry(480, 640), Geometry(480, 640), 160, 1, false))
                            glasses.wiring.choose(AnchorRegistrationAction.HOLD)
                        }
                    }
                    pump()
                    check(!glasses.wiring.active()) { "Original chooser did not complete/cancel for $scenario" }
                    val saved = runtime.store.read("app")!!
                    if (scenario in listOf("click", "hold", "phone-tap")) {
                        check(saved.descriptor.resourceId == "app:id/action")
                        check(saved.mode == if (scenario == "click") AnchorMode.CLICK else AnchorMode.HOLD)
                        check(Toast.messages.single().startsWith("Anchor saved:"))
                    } else {
                        check(saved == if (scenario == "external-cancel") old.copy(mode = AnchorMode.HOLD) else old)
                        check(Toast.messages.none { it.startsWith("Anchor saved:") })
                    }
                    check(glasses.wiring.appNavigation == 0)
                }
                check(appInput.isEmpty() && phone.control.sink.toString().isEmpty()) { "Registration emitted app input for $scenario" }
                fun artifact(name: String, frames: List<Frame>) {
                    File(evidence, "$scenario-$name.bin").outputStream().use { stream ->
                        val output = DataOutputStream(stream); frames.forEach { Wire.writeFrame(output, it) }
                    }
                }
                artifact("glasses", fromGlasses); artifact("phone", fromPhone)
                checks++; println("PASS $scenario actual grammar/Glasses Session -> Phone Session/Binder-value -> reflected121 runtime/provider/reader/store -> original Glasses owner/Activity methods/controller -> OPEN/choice -> store/result; no app input")
            } }
        }
    } finally { UiTargetAccess.currentReader = null; directory.deleteRecursively() }
    println("PASS $checks registration composition scenarios; Android Binder/TLS/renderer/widgets are named host boundaries, no device/accessibility-grant proof")
}
