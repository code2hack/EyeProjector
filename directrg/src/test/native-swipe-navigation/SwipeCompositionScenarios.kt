package com.code2hack.eyeprojector.directhost

import android.content.Intent as BroadcastIntent
import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
private const val HOME = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"

fun main() {
    val directory = Files.createTempDirectory("eyeprojector-native-swipe-").toFile()
    val runtime = AnchorAppRuntime(android.content.Context(directory)); runtime.holdDrain()
    var passed = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, test: (Joined) -> Unit) {
        Joined(runtime).use { joined ->
            try { test(joined); passed++; println("PASS $name actual source composition") }
            catch (failure: Throwable) { failures.add(name); println("FAIL $name: $failure"); failure.printStackTrace(System.out) }
            finally { joined.retain(name) }
        }
    }
    fun swipe(j: Joined, action: String) {
        val a = j.wiring
        a.receivers.single().onReceive(a, BroadcastIntent(action)); j.pump()
    }
    try {
        for (action in listOf(BACK, HOME)) scenario("native-${action.substringAfterLast('_')}-retires-original-anchor") { j ->
            j.hold(); swipe(j, action); j.canceled(); j.replayOldHeld()
            val submitted = j.fromGlasses.filter { it.channel == Wire.NAVIGATE }.map { Navigate.from(it.payload).action }
            check(submitted == listOf(if (action == BACK) Navigation.BACK else Navigation.CONTEXTUAL_HOME))
        }
        scenario("native-back-cancels-registration-without-app-navigation") { j ->
            j.register(); check(j.wiring.active()); swipe(j, BACK)
            check(!j.wiring.active() && j.events.isEmpty())
            check(j.fromGlasses.none { it.channel == Wire.NAVIGATE })
        }
        scenario("native-home-cancels-registration-before-original-contextual-home") { j ->
            j.register(); check(j.wiring.active()); swipe(j, HOME)
            check(!j.wiring.active() && j.events.isEmpty())
            check(j.fromGlasses.filter { it.channel == Wire.NAVIGATE }.map { Navigate.from(it.payload).action } == listOf(Navigation.CONTEXTUAL_HOME))
        }
        scenario("native-home-restores-settings-then-stops-receiver-at-launcher") { j ->
            j.wiring.showControlPanel(); j.wiring.settings(); j.pump(); swipe(j, HOME)
            check(j.wiring.launcherExits == 0)
            swipe(j, HOME); check(j.wiring.launcherExits == 1 && j.wiring.receivers.isEmpty())
        }
        scenario("native-back-from-panel-retains-session-and-function-remains-original") { j ->
            val original = j.glasses.session
            j.wiring.showControlPanel(); j.pump(); swipe(j, BACK)
            check(j.wiring.session === original && !j.wiring.presentation.panelVisible)
            j.hold(); check(j.wiring.produced.last() == FunctionIntentKind.ACTIVATE)
            j.press(); check(!j.input.anchor.holding)
        }
        scenario("ordinary-one-finger-scroll-after-native-navigation-uses-actual-session") { j ->
            val actions = mutableListOf<PadGestureRecognizer.Action>()
            val pad = PadGestureRecognizer(500, 800, actions::add)
            val point = PointerPosition(320f, 420f, true)
            val key = nativePadKey("ROKID,PSOC-TP-R", 0x101, 22)!!
            check(key == PadGestureRecognizer.Key.FORWARD)
            check(nativePadKey("ROKID,PSOC-TP-R", 0x101, 292) == PadGestureRecognizer.Key.FORWARD_ALIAS)
            check(nativePadKey("ROKID,PSOC-TP-R", 0x101, 293) == PadGestureRecognizer.Key.BACKWARD_ALIAS)
            check(nativePadKey("ROKID,PSOC-TP-R", 0x101, 4) == PadGestureRecognizer.Key.DOUBLE)
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, 1000, 1000, position = point), 1000)
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, 1010, 1000), 1010)
            pad.confirm(2000)
            val scroll = actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().single()
            check(scroll.pixels != 0 && j.wiring.scroll(point.x, point.y)); j.pump()
            check(j.ordinary == listOf("scroll") && j.fromGlasses.none { it.channel == Wire.NAVIGATE })
            swipe(j, BACK)
            check(j.wiring.scroll(point.x, point.y)); j.pump(); check(j.ordinary == listOf("scroll", "scroll"))
        }
        scenario("native-lifecycle-cleans-original-hold-and-old-receiver-never-replays") { j ->
            j.hold(); val a = j.wiring; val old = a.receivers.single()
            a.focused = false; a.onWindowFocusChanged(false); j.pump(); j.canceled()
            val before = j.fromGlasses.count { it.channel == Wire.NAVIGATE }
            old.onReceive(a, BroadcastIntent(BACK)); j.pump()
            a.focused = true; a.onWindowFocusChanged(true)
            old.onReceive(a, BroadcastIntent(HOME)); j.pump()
            check(j.fromGlasses.count { it.channel == Wire.NAVIGATE } == before)
            a.onPause(); check(a.receivers.isEmpty()); a.onResume(); check(a.receivers.size == 1)
            a.onDestroy(); check(a.receivers.isEmpty())
        }
        for (action in listOf(BACK, HOME)) scenario("native-${action.substringAfterLast('_')}-cancel-callback-does-not-mutate-actual-successor-session") { j ->
            HoldReceiverRig().use { successor ->
                successor.frames() // Drain the successor's own startup publications first.
                val a = j.wiring
                val events = mutableListOf<HeldPointerDispatcher.Event>()
                a.onHeldEvent = { e -> events.add(e); if (e.phase == Touch.Phase.CANCEL) a.session = successor.session; true }
                a.down(); check(a.heldOwner.holding)
                swipe(j, action)
                check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL) && !a.heldOwner.holding)
                check(j.fromGlasses.none { it.channel == Wire.NAVIGATE })
                val frames = successor.frames()
                check(frames.none { it.channel == Wire.NAVIGATE || it.channel == Wire.HEAD_POINTER }) {
                    "Original cancellation mutated actual successor: ${frames.map { it.channel }}"
                }
            }
        }
        for (action in listOf(BACK, HOME)) scenario("chooser-${action.substringAfterLast('_')}-callback-preserves-actual-successor-owner-contact-and-queued-work") { j ->
            j.hold(); j.register(); j.canceled(); check(j.wiring.active())
            HoldReceiverRig().use { successor ->
                successor.frames()
                val source = successor.session
                val controls = source.javaClass.getDeclaredField("controls").apply { isAccessible = true }.get(source) as OrderedControlDispatcher
                val entered = CountDownLatch(1); val release = CountDownLatch(1); val executed = AtomicInteger()
                val at = android.os.SystemClock.uptimeMillis()
                check(controls.submit(at, { true }, preserveTiming = false) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
                check(entered.await(2, TimeUnit.SECONDS))
                check(controls.submit(at, { true }, preserveTiming = false) { executed.incrementAndGet() })
                val pending = controls.snapshot()
                val original = j.glasses.session
                val oldControls = original.javaClass.getDeclaredField("controls").apply { isAccessible = true }.get(original) as OrderedControlDispatcher
                val oldEntered = CountDownLatch(1); val oldRelease = CountDownLatch(1); val oldExecuted = AtomicInteger()
                check(oldControls.submit(at, { true }, preserveTiming = false) { oldEntered.countDown(); check(oldRelease.await(5, TimeUnit.SECONDS)) })
                check(oldEntered.await(2, TimeUnit.SECONDS))
                check(oldControls.submit(at, { true }, preserveTiming = false) { oldExecuted.incrementAndGet() })
                val oldPending = oldControls.snapshot()
                val nextOwner = successor.owner
                repeat(5) { nextOwner.advanceInputRevision() }
                val ownerRevision = nextOwner.inputRevision
                val nextGeneration = ownerRevision + 100
                val a = j.wiring
                val events = mutableListOf<HeldPointerDispatcher.Event>()
                a.onHeldEvent = { events.add(it); true }
                a.anchorOverlay.onDismiss = {
                    a.owner = nextOwner; a.session = source; a.inputGeneration = nextGeneration
                    check(a.down() && a.heldOwner.holding)
                }
                try {
                    a.receivers.single().onReceive(a, BroadcastIntent(action))
                    check(a.inputGeneration == nextGeneration && nextOwner.inputRevision == ownerRevision) { "Successor revision was mutated" }
                    check(a.heldOwner.holding && events.map { it.phase } == listOf(Touch.Phase.DOWN)) { "Successor held contact was canceled" }
                    val after = controls.snapshot()
                    check(after.pending == pending.pending && after.canceled == pending.canceled && executed.get() == 0) { "Successor pending work was canceled" }
                    check(oldControls.snapshot().pending == 0L && oldControls.snapshot().canceled > oldPending.canceled && oldExecuted.get() == 0) {
                        "Original queued work was not retired"
                    }
                } finally {
                    release.countDown()
                    oldRelease.countDown()
                    a.heldOwner.cancel(android.os.SystemClock.uptimeMillis())
                    successor.drain()
                }
                j.pump(); check(oldExecuted.get() == 0)
                check(executed.get() == 1)
                check(successor.frames().none { it.channel == Wire.NAVIGATE || it.channel == Wire.HEAD_POINTER })
            }
        }
        for (action in listOf(BACK, HOME)) scenario("chooser-${action.substringAfterLast('_')}-nested-retirement-preserves-later-actual-same-session-work") { j ->
            j.register(); check(j.wiring.active())
            val a = j.wiring; val source = j.glasses.session
            val controls = source.javaClass.getDeclaredField("controls").apply { isAccessible = true }.get(source) as OrderedControlDispatcher
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val executed = AtomicInteger()
            val at = android.os.SystemClock.uptimeMillis()
            check(controls.submit(at, { true }, preserveTiming = false) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
            check(entered.await(2, TimeUnit.SECONDS))
            var revision = -1L; var canceled = -1L
            val events = mutableListOf<HeldPointerDispatcher.Event>(); a.onHeldEvent = { events.add(it); true }
            a.anchorOverlay.onDismiss = {
                a.retire(); revision = a.inputGeneration; canceled = controls.snapshot().canceled
                check(controls.submit(at, { true }, preserveTiming = false) { executed.incrementAndGet() })
                check(a.down())
            }
            try {
                a.receivers.single().onReceive(a, BroadcastIntent(action))
                check(a.inputGeneration == revision && a.owner!!.inputRevision == revision)
                check(a.heldOwner.holding && events.map { it.phase } == listOf(Touch.Phase.DOWN))
                check(controls.snapshot().pending == 1L && controls.snapshot().canceled == canceled && executed.get() == 0)
            } finally { release.countDown(); a.heldOwner.cancel(android.os.SystemClock.uptimeMillis()) }
            j.pump(); check(executed.get() == 1 && j.fromGlasses.none { it.channel == Wire.NAVIGATE })
        }
        println("RESULT $passed passed; ${failures.size} failed; original Session/Anchor source, modeled Android/Binder/UI boundaries")
        check(failures.isEmpty()) { failures.joinToString() }
    } finally { runtime.invalidateBinding(); runtime.holdDrain(); directory.deleteRecursively() }
}
