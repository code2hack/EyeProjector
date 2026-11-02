package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files

/** Complete held-input/value route with exact current Activity indicator/lifecycle source blocks. */
fun main() {
    val directory = Files.createTempDirectory("eyeprojector-held-ui-app-").toFile()
    val evidence = File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"))
    val runtime = AnchorAppRuntime(android.content.Context(directory))
    runtime.holdDrain()
    check(runtime.registrationProviderState == AnchorAppRuntime.ProviderState.INSTALLED)
    var count = 0
    try {
        for (scenario in listOf("toggle", "register", "focus", "pause", "destroy", "geometry", "clear", "window", "stop")) {
            Fixture().use { phone -> HoldReceiverRig().use { glasses ->
                phone.holdManaged(publishPointer = false); phone.holdInstall(runtime.bind()); runtime.holdDrain()
                runtime.store.replace(savedHold); runtime.holdDrain(); UiTargetAccess.currentReader = holdReader()
                val events = phone.holdEvents()
                val fromGlasses = mutableListOf<Frame>(); val fromPhone = mutableListOf<Frame>()
                var phoneOffset = 0
                fun pump() {
                    repeat(12) {
                        val sent = glasses.frames(); fromGlasses.addAll(sent); sent.forEach(phone::receive)
                        phone.holdFlush(runtime)
                        val bytes = phone.peer.sink.toByteArray()
                        val input = DataInputStream(bytes.copyOfRange(phoneOffset, bytes.size).inputStream())
                        val replies = buildList { while (input.available() > 0) add(Wire.readFrame(input)) }
                        phoneOffset = bytes.size; fromPhone.addAll(replies); replies.forEach(glasses::receive)
                        glasses.drain()
                        if (sent.isEmpty() && replies.isEmpty()) return
                    }
                    error("Original UI/control pipeline did not quiesce")
                }
                glasses.wiring.move(10f, 20f); pump()
                val at = android.os.SystemClock.uptimeMillis()
                glasses.wiring.short(at); pump()
                check(glasses.wiring.produced == listOf(FunctionIntentKind.ACTIVATE))
                check(events.map { it.phase } == listOf(Touch.Phase.DOWN))
                check(glasses.wiring.heldVisible)
                check(glasses.wiring.label.text == "Anchor held" && !glasses.wiring.label.isClickable && !glasses.wiring.label.isFocusable)
                check(glasses.wiring.root.children.contains(glasses.wiring.label))
                val oldHeldFrame = fromPhone.first { it.channel == Wire.ANCHOR &&
                    (AnchorMessage.from(it.payload) as? AnchorMessage.HeldState)?.state == AnchorHoldState.HELD }
                when (scenario) {
                    "toggle" -> {
                        repeat(3) { glasses.wiring.move(400f + it, 550f); pump(); check(glasses.wiring.heldVisible) }
                        check(events.size == 1)
                        glasses.wiring.short(at + 1000); pump()
                        check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
                        check(events[0].contactId == events[1].contactId)
                        check(!glasses.wiring.heldVisible)
                        glasses.receive(oldHeldFrame)
                        check(!glasses.wiring.heldVisible) // Late original HELD cannot undo RELEASED.
                    }
                    "register" -> {
                        glasses.wiring.move(320f, 420f); pump()
                        glasses.wiring.register(at + 1000); pump()
                        check(glasses.wiring.active() && !glasses.wiring.heldVisible)
                        check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
                        glasses.wiring.releaseRegister(at + 1000); pump()
                        check(glasses.wiring.produced == listOf(FunctionIntentKind.ACTIVATE, FunctionIntentKind.REGISTER))
                        check(events.size == 2)
                        val index = fromGlasses.indexOfFirst { it.channel == Wire.ANCHOR && AnchorMessage.from(it.payload) is AnchorMessage.Open }
                        check(index >= 0)
                        val open = AnchorMessage.from(fromGlasses[index].payload) as AnchorMessage.Open
                        check(ReceiverUsability.from(fromGlasses[index + 1].payload) == open.chooser)
                        glasses.wiring.choose(AnchorRegistrationAction.HOLD); pump()
                        check(!glasses.wiring.active() && !glasses.wiring.heldVisible)
                        check(events.size == 2 && glasses.wiring.appNavigation == 0)
                        check(runtime.store.read("app")?.mode == com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode.HOLD)
                        check(fromPhone.filter { it.channel == Wire.ANCHOR }.map { AnchorMessage.from(it.payload) }
                            .any { it is AnchorMessage.Result && it.result == AnchorRegistrationResult.SAVED_HOLD })
                    }
                    "focus" -> { glasses.wiring.focused = false; glasses.wiring.onWindowFocusChanged(false); pump() }
                    "pause" -> { glasses.wiring.onPause(); pump() }
                    "destroy" -> { glasses.wiring.onDestroy(); pump() }
                    "geometry" -> { glasses.receive(Frame(Wire.GEOMETRY, payload = Geometry(640, 480).bytes())); pump() }
                    "clear" -> { runtime.store.clear("app"); runtime.holdDrain(); pump() }
                    "window" -> { UiTargetAccess.event(UiTargetAccess.WindowChange(47, 7, true)); runtime.holdDrain(); pump() }
                    "stop" -> { glasses.receive(Frame(Wire.END)); phone.closeSession(); runtime.holdDrain() }
                }
                check(!glasses.wiring.heldVisible)
                if (scenario != "toggle") {
                    check(events.take(2).map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
                    check(events.none { it.phase == Touch.Phase.UP })
                    glasses.wiring.view.anchorHeld(glasses.session, AnchorMessage.from(oldHeldFrame.payload) as AnchorMessage.HeldState)
                    check(!glasses.wiring.heldVisible)
                }
                fun artifact(name: String, frames: List<Frame>) {
                    File(evidence, "$scenario-$name.bin").outputStream().use { stream ->
                        val out = DataOutputStream(stream); frames.forEach { Wire.writeFrame(out, it) }
                    }
                }
                artifact("glasses", fromGlasses); artifact("phone", fromPhone)
                count++; println("PASS $scenario actual Function capture/dispatch -> Glasses Session -> Phone original owner/reflected121 provider -> state wire -> Glasses owner/Activity label and lifecycle; no app acknowledgment claim")
            } }
        }
    } finally { UiTargetAccess.currentReader = null; runtime.invalidateBinding(); runtime.holdDrain(); directory.deleteRecursively() }
    println("PASS $count merged Hold UI/registration pipeline scenarios; actual Activity source blocks, named Android/TLS/renderer/widget boundaries, no physical hit-test proof")
}
