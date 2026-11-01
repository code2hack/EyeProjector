package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val directory = Files.createTempDirectory("eyeprojector-cancel-only-core-").toFile()
    val runtime = AnchorAppRuntime(android.content.Context(directory)); runtime.holdDrain()
    var passed = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, test: (Joined) -> Unit) {
        Joined(runtime).use { joined ->
            try { test(joined); passed++; println("PASS $name actual Phone core source") }
            catch (failure: Throwable) { failures.add(name); println("FAIL $name: $failure"); failure.printStackTrace(System.out) }
            finally { joined.retain(name) }
        }
    }
    fun frame(j: Joined, channel: Int, bytes: ByteArray) { j.phone.receive(Frame(channel, payload = bytes)); j.pump() }
    val geometry = Geometry(480, 640)
    try {
        if (System.getenv("EYEPROJECTOR_C206_NATIVE_ONLY")=="1") {
            pinProducerScenarios(::scenario)
            println("RESULT native-first $passed passed; ${failures.size} failed")
            check(failures.isEmpty()){failures.joinToString()};return
        }
        if (System.getenv("EYEPROJECTOR_R187_PANEL_ONLY")=="1") {
            scenario("C206-2-actual-panel-final-writer-after-first-NORMAL",::panelFinalReceiptScenario)
            scenario("C206-2-actual-panel-final-writer-wait-and-FIFO-H2",::panelFinalWaitScenario)
            println("RESULT panel-final $passed passed; ${failures.size} failed")
            check(failures.isEmpty()){failures.joinToString()};return
        }
        scenario("C206-2-actual-panel-final-writer-after-first-NORMAL",::panelFinalReceiptScenario)
        scenario("C206-2-actual-panel-final-writer-wait-and-FIFO-H2",::panelFinalWaitScenario)
        if (System.getenv("EYEPROJECTOR_PENDING_LAUNCH") == "1") {
            scenario("PENDING-queued-normal-Home-must-not-cancel-or-launch-after-successor-Hold") { j ->
                queuedHomeBefore(j)
            }
            println("PENDING RESULT $passed passed; ${failures.size} failed; actual queued Home remains outside current source allocation")
            check(failures.isEmpty()) { failures.joinToString() }
            return
        }
        bridgeReceiptScenarios(::scenario)
        pinProducerScenarios(::scenario)
        scenario("held-wire-tap-cancels-only-and-distinct-tap-recovers") { j ->
            j.hold(); val setting = runtime.store.snapshot("app")
            frame(j, Wire.TAP, Tap(200, 100, geometry).bytes()); j.canceled()
            check(j.ordinary.isEmpty()) { "Cancelling Tap also tapped: ${j.ordinary}" }
            check(runtime.store.snapshot("app") == setting)
            frame(j, Wire.TAP, Tap(200, 100, geometry).bytes()); check(j.ordinary == listOf("tap"))
        }
        scenario("held-wire-scroll-cancels-only") { j ->
            j.hold(); frame(j, Wire.SCROLL, Scroll(Tap(200, 100, geometry), 100).bytes()); j.canceled()
            check(j.ordinary.isEmpty()) { "Cancelling Scroll also scrolled: ${j.ordinary}" }
        }
        scenario("held-wire-back-cancels-only-before-nativeAction") { j ->
            j.hold(); val before = j.phone.control.sink.toString()
            frame(j, Wire.NAVIGATE, Navigate(Navigation.BACK, geometry).bytes()); j.canceled()
            check(j.phone.control.sink.toString() == before) { "Cancelling Back also wrote navigation" }
        }
        scenario("held-wire-home-cancels-only-before-launch") { j ->
            j.hold(); frame(j, Wire.NAVIGATE, Navigate(Navigation.CONTEXTUAL_HOME, geometry).bytes()); j.canceled()
            check(j.appLaunches.isEmpty()) { "Cancelling Home also launched: ${j.appLaunches}" }
            check(j.fromPhone.none { it.channel == Wire.RECEIVER_INPUT })
        }
        scenario("held-long-function-has-no-registration") { j ->
            j.hold(); val saved = runtime.store.snapshot("app"); val reads = j.windows.reads
            j.register(); j.canceled()
            check(!j.wiring.active() && j.messages.none { it is AnchorMessage.Offer }) { "Long also opened registration" }
            check(j.windows.reads == reads && runtime.store.snapshot("app") == saved) { "Cancel-only Long performed lookup/save" }
        }
        scenario("short-held-release-is-UP-only") { j ->
            j.hold(); val reads = j.windows.reads
            j.press()
            check(j.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
            check(j.events[0].contactId == j.events[1].contactId && !j.input.anchor.holding)
            check(j.windows.reads == reads)
        }
        for(h2 in listOf(false,true)) scenario("raw-Short-lifetime-H1-canceled-H2-$h2") { j ->
            j.hold();val at=android.os.SystemClock.uptimeMillis()
            val source=j.phone.session as CapturedProjection
            val down=PhoneHidEdge(PhoneHidAction.FUNCTION,PhoneHidPhase.DOWN,7,195,700,at,at)
            source.phoneHidEdge(99,down);frame(j,Wire.TAP,Tap(20,30,geometry).bytes())
            if(h2) j.press()
            val before=j.events.toList()
            source.phoneHidEdge(99,down.copy(phase=PhoneHidPhase.UP,eventTimeMs=at+100));j.pump()
            check(j.events.toList()==before&&j.input.anchor.holding==h2) {"Raw original Short recaptured as fresh: ${j.events}"}
            val fresh=down.copy(downTimeMs=at+200,eventTimeMs=at+200)
            source.phoneHidEdge(100,fresh);source.phoneHidEdge(100,fresh.copy(phase=PhoneHidPhase.UP,eventTimeMs=at+250));j.pump()
            check(j.input.anchor.holding!=h2)
        }
        for(h2 in listOf(false,true)) scenario("completed-Short-queued-not-inspected-H1-canceled-H2-$h2") { j ->
            j.hold();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            runtime.onLane {entered.countDown();check(release.await(3,TimeUnit.SECONDS))};check(entered.await(2,TimeUnit.SECONDS))
            try {
                j.wiring.short(3000);j.glasses.frames().forEach(j.phone::receive);j.phone.holdFunctions()
                j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()))
                j.phone.holdFunctions()
                if(h2) startResolvedHold(j)
            } finally {release.countDown()}
            val before=j.events.toList();j.pump()
            check(j.events.toList()==before&&j.input.anchor.holding==h2) {"Queued Short bypassed original generation: ${j.events}"}
        }
        for (h2 in listOf(false,true)) scenario("actual-Session-original-Long-cannot-register-or-cancel-H2-$h2") { j ->
            j.hold()
            val at=android.os.SystemClock.uptimeMillis()
            val down=PhoneHidEdge(PhoneHidAction.FUNCTION,PhoneHidPhase.DOWN,7,195,700,at,at)
            val source=j.phone.session as CapturedProjection
            source.phoneHidEdge(88,down)
            frame(j,Wire.TAP,Tap(20,30,geometry).bytes())
            if(h2) j.press()
            val before=j.events.toList()
            source.phoneHidEdge(88,down.copy(phase=PhoneHidPhase.UP,eventTimeMs=at+810));j.pump()
            check(j.events.toList()==before&&j.input.anchor.holding==h2&&j.messages.none{it is AnchorMessage.Offer})
        }
        scenario("both-Shorts-force-queued-before-first-FIFO-DOWN") { j ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            runtime.onLane { entered.countDown();check(release.await(3,TimeUnit.SECONDS)) }
            check(entered.await(2,TimeUnit.SECONDS))
            try {
                j.wiring.short(1000);j.wiring.short(2000)
                j.glasses.frames().forEach(j.phone::receive);j.phone.holdFunctions()
                check(j.events.isEmpty())
            } finally {release.countDown()}
            j.pump()
            check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP)&&!j.input.anchor.holding)
        }
        scenario("outer-Session-HID-CANCEL-throw-closes-original-without-normal-output") { j ->
            j.hold();j.cancelBehavior={error("original backend CANCEL failed")}
            val at=android.os.SystemClock.uptimeMillis()
            (j.phone.session as CapturedProjection).phoneHidEdge(77,PhoneHidEdge(PhoneHidAction.TAP,PhoneHidPhase.DOWN,7,195,700,at,at))
            awaitOriginalStopped(j)
            check(!(j.phone.get("running") as java.util.concurrent.atomic.AtomicBoolean).get())
            check(j.ordinary.isEmpty()&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
            check(j.messages.none{it is AnchorMessage.Offer})
            j.cancelBehavior={true}
        }
        scenario("outer-Binder-FIFO-Long-CANCEL-throw-retires-original-Session") { j ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            runtime.onLane {entered.countDown();check(release.await(3,TimeUnit.SECONDS))};check(entered.await(2,TimeUnit.SECONDS))
            j.cancelBehavior={error("Binder consume CANCEL failed")}
            try {
                j.wiring.short(1000);j.wiring.move(320f,420f);j.wiring.register(2000);j.wiring.releaseRegister(2000)
                j.glasses.frames().forEach(j.phone::receive);j.phone.holdFunctions();check(j.events.isEmpty())
            } finally {release.countDown()}
            runtime.holdDrain()
            awaitOriginalStopped(j)
            check(!(j.phone.get("running") as java.util.concurrent.atomic.AtomicBoolean).get()) {"Binder output failure left original Session active"}
            check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&j.ordinary.isEmpty())
            j.cancelBehavior={true}
        }
        scenario("distinct-queued-shorts-preserve-FIFO-DOWN-UP") { j ->
            j.wiring.short(1000); j.wiring.short(2000); j.pump()
            check(j.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP)) { "Distinct Shorts were coalesced or both snapshotted: ${j.events}" }
            check(!j.input.anchor.holding)
        }
        scenario("queued-activate-then-register-is-consumed-at-app-FIFO") { j ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            runtime.onLane { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            check(entered.await(2, TimeUnit.SECONDS))
            try {
                j.wiring.short(1000)
                j.wiring.move(320f, 420f); j.wiring.register(2000); j.wiring.releaseRegister(2000)
                j.glasses.frames().forEach(j.phone::receive)
                j.phone.holdFunctions()
                check(!j.input.anchor.holding) // Both early shell checks ran before actual DOWN.
            } finally { release.countDown() }
            j.pump()
            check(j.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
            check(!j.wiring.active() && j.messages.none { it is AnchorMessage.Offer }) { "FIFO REGISTER continued after earlier ACTIVATE established Hold" }
            check(j.windows.reads == 1) { "Consumed FIFO REGISTER read targets: ${j.windows.reads}" }
        }
        scenario("no-hold-ordinary-and-fresh-long-remain-normal") { j ->
            frame(j, Wire.TAP, Tap(200, 100, geometry).bytes())
            frame(j, Wire.SCROLL, Scroll(Tap(200, 100, geometry), 100).bytes())
            check(j.ordinary == listOf("tap", "scroll"))
            j.register(); check(j.wiring.active() && j.messages.any { it is AnchorMessage.Offer })
        }
        scenario("registration-observer-yields-to-real-activation-and-setting-change-retires-Hold") { j ->
            j.register(); j.wiring.choose(AnchorRegistrationAction.CANCEL); j.pump()
            check(!j.wiring.active())
            j.press(); check(j.input.anchor.holding)
            runtime.store.replace(savedHold.copy(mode = com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode.CLICK))
            runtime.holdDrain(); j.pump(); j.canceled()
        }
        centralGateScenarios(::scenario)
        phoneIngressScenarios(::scenario)
        localGateScenarios(::scenario)
        traceScenarios(::scenario)
        rawGlassesScenarios(::scenario)
        scopedLaunchScenarios(::scenario)
        bootstrapTraceScenarios { name, test ->
            try { test(); passed++; println("PASS $name actual bootstrap/observer source") }
            catch(failure:Throwable) {failures.add(name);println("FAIL $name: $failure");failure.printStackTrace(System.out)}
        }
        coreUnitScenarios { name, test ->
            try { test(); passed++; println("PASS $name actual Phone core source") }
            catch (failure: Throwable) { failures.add(name); println("FAIL $name: $failure"); failure.printStackTrace(System.out) }
        }
        println("RESULT $passed passed; ${failures.size} failed; Owner119 bounded Phone/Glasses source composition; full Android/runtime acceptance remains separate")
        check(failures.isEmpty()) { failures.joinToString() }
    } finally { runtime.invalidateBinding(); runtime.holdDrain(); directory.deleteRecursively() }
}
