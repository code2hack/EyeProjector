package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Actual controller/route/merger and wire codecs; stamped Session admission is a named boundary. */
class PhoneHidNavigationCompositionTest {
    private class Harness(val projected: Boolean = true, available: Boolean = false) {
        data class Task(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        val lock = Any()
        var now = 1000L
        var running = true
        var stamp = 1L
        var sequence = 0L
        val size = Geometry(100, 100)
        var fact = ReceiverUsability("original-owner", ReceiverIncarnation("receiver", 1), 1, 1,
            if (projected) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.LOCAL_PANEL)
        val tasks = mutableListOf<Task>()
        val frames = mutableListOf<Frame>()
        val touches = mutableListOf<Touch>()
        val functions = mutableListOf<FunctionIntent>()
        val events = mutableListOf<String>()
        val routes = mutableListOf<PhoneHidRoute>()
        val bindings = PhoneHidAction.entries.associateWith { action ->
            PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity("actual fixture peripheral", 1, 2), 100 + action.ordinal, 200 + action.ordinal),
                "Fixture", action.name)
        }
        val store = PhoneHidBindings({ null }) { true }
        val controls = PhoneTouchDispatcher(lock) {}
        private fun wire(frame: Frame) {
            val bytes = ByteArrayOutputStream()
            Wire.writeFrame(DataOutputStream(bytes), frame)
            frames.add(Wire.readFrame(DataInputStream(bytes.toByteArray().inputStream())))
        }
        val merger = PhoneInputMerger(lock, { now }, { at, action ->
            val task = Task(at, action).also(tasks::add)
            val cancel: () -> Unit = { task.canceled = true }; cancel
        }, { size }, { touches.add(it); controls.touch(it) }, controls::scroll,
            { action, geometry -> events.add("navigate"); wire(Frame(Wire.NAVIGATE, payload = Navigate(action, geometry).bytes())) },
            { controls.cancel(); events.add("cancel") },
            { assertFalse(Thread.holdsLock(lock)); events.add("receiver"); wire(Frame(Wire.RECEIVER_INPUT, payload = it.bytes())) },
            { running }, { functions.add(it) })
        private var routeId = 0L
        val controller = PhoneHidController(store, object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute {
                val id = ++routeId
                val originalStamp = stamp
                val originalFact = fact
                // Context-only admission boundary; final Session composition exercises the actual accessor.
                fun send(edge: PhoneHidEdge) = merger.stampedEdge(id, edge) { point, source ->
                    originalStamp == stamp && originalFact.ownerEpoch == fact.ownerEpoch && originalFact.incarnation == fact.incarnation &&
                        originalFact.inputRevision == fact.inputRevision && originalFact.mode == fact.mode &&
                        source?.ownerEpoch == fact.ownerEpoch && source.incarnation == fact.incarnation &&
                        point.revision == fact.inputRevision && point.projected == projected
                }
                val route = PhoneHidRoute(down, 1, ::send).also(routes::add)
                route.submit(down)
                return route
            }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = route.cancel(eventTimeMs)
        }, { operation, complete -> operation(); complete() }, { now })
        init {
            controls.geometry(size)
            bindings.forEach { (action, binding) -> store.assign(action, binding) }
            pointer(available)
            controller.connection(true)
            events.clear()
        }
        fun pointer(available: Boolean, revision: Long = fact.inputRevision) {
            merger.pointer(HeadPointerState(revision, ++sequence, available, projected,
                -500f, 10000f, Fit(0, 0, 100, 100), size), fact)
        }
        fun down(action: PhoneHidAction = PhoneHidAction.BACK_HOME): PhoneHidController.Event {
            val event = PhoneHidController.Event(9, bindings.getValue(action), PhoneHidPhase.DOWN, now, now)
            assertTrue(controller.accept(event)); return event
        }
        fun up(event: PhoneHidController.Event) {
            now += 10
            assertTrue(controller.accept(event.copy(phase = PhoneHidPhase.UP, eventTimeMs = now)))
        }
        fun press(action: PhoneHidAction = PhoneHidAction.BACK_HOME) = up(down(action))
        fun advance(at: Long, canceledToo: Boolean = false) {
            now = at
            while (true) {
                val task = tasks.firstOrNull { it.at <= at } ?: break
                tasks.remove(task)
                if (!task.canceled || canceledToo) task.action()
            }
        }
        fun navigation() = frames.filter { it.channel == Wire.NAVIGATE }.map { Navigate.from(it.payload).action }
        fun receiver() = frames.filter { it.channel == Wire.RECEIVER_INPUT }.map { ReceiverInput.from(it.payload).kind }
    }

    @Test fun nonspatialSingleAndDoubleUseDedicatedProjectedAndLocalDestinations() {
        for (projected in listOf(true, false)) for (count in 1..2) {
            val h = Harness(projected)
            repeat(count) { h.press(); h.now += 100 }
            h.advance(2000)
            assertTrue(h.touches.isEmpty()); assertTrue(h.functions.isEmpty())
            if (projected) {
                assertEquals(listOf(if (count == 1) Navigation.BACK else Navigation.CONTEXTUAL_HOME), h.navigation())
                assertTrue(h.receiver().isEmpty())
            } else {
                assertEquals(listOf(if (count == 1) ReceiverInputKind.BACK else ReceiverInputKind.HOME), h.receiver())
                assertTrue(h.navigation().isEmpty())
            }
            assertTrue(h.events.indexOf("cancel") < h.events.indexOf(if (projected) "navigate" else "receiver"))
        }
    }

    @Test fun lockWithoutAimRetiresInputThenUsesDedicatedReceiverLockInBothModes() {
        for (projected in listOf(true, false)) {
            val h = Harness(projected)
            h.press(PhoneHidAction.LOCK); h.advance(2000)
            assertEquals(listOf(ReceiverInputKind.LOCK), h.receiver())
            assertTrue(h.navigation().isEmpty() && h.touches.isEmpty() && h.functions.isEmpty())
            assertTrue(h.running)
            assertTrue(h.events.indexOf("cancel") < h.events.indexOf("receiver"))
        }
    }

    @Test fun spatialUnavailableUpdatesDoNotRetirePendingNavigationOrHeldSecondPress() {
        for (double in listOf(false, true)) {
            val h = Harness()
            h.press(); h.now = 1100
            val second = if (double) h.down() else null
            h.pointer(false); h.pointer(false)
            if (second != null) h.up(second)
            h.advance(2000)
            assertEquals(listOf(if (double) Navigation.CONTEXTUAL_HOME else Navigation.BACK), h.navigation())
        }
    }

    @Test fun originalStampRevisionIncarnationAndOwnerMustStillMatchAtDelayedDispatch() {
        val changes: List<(Harness) -> Unit> = listOf(
            { it.stamp++ }, { it.fact = it.fact.copy(ownerEpoch = "successor") },
            { it.fact = it.fact.copy(incarnation = ReceiverIncarnation("successor", 2)) },
            { it.fact = it.fact.copy(inputRevision = 2); it.pointer(false, 2) },
            { it.running = false }, { it.merger.invalidate() })
        for (change in changes) {
            val h = Harness(); h.press(); change(h); h.advance(2000, canceledToo = true)
            assertTrue(h.frames.isEmpty())
        }
    }

    @Test fun controllerRetirementAfterUpAndDuringDoublePreventsTransportOutput() {
        val retires: List<(Harness) -> Unit> = listOf(
            { it.controller.cancelInput() }, { it.controller.presentationLost() },
            { it.controller.openPage() }, { it.controller.deviceRemoved(9) }, { it.controller.connection(false) })
        for (retire in retires) for (doubleHeld in listOf(false, true)) {
            val h = Harness(); h.press(); h.now = 1100
            val second = if (doubleHeld) h.down() else null
            retire(h)
            if (second != null) h.up(second)
            h.advance(2000, canceledToo = true)
            assertTrue(h.frames.isEmpty())
        }
    }

    @Test fun repeatsDuplicateUpsAndCanceledEdgesCannotAddOrRetargetNavigation() {
        val h = Harness()
        val first = h.down()
        h.controller.accept(first.copy(eventTimeMs = 1005, repeatCount = 1))
        h.up(first); h.controller.accept(first.copy(phase = PhoneHidPhase.UP, eventTimeMs = h.now))
        h.advance(1600)
        val old = h.routes.single()
        h.press(); old.cancel(h.now); h.advance(2200)
        assertEquals(listOf(Navigation.BACK, Navigation.BACK), h.navigation())
        val canceled = h.down(PhoneHidAction.LOCK)
        h.controller.accept(canceled.copy(phase = PhoneHidPhase.CANCEL, eventTimeMs = h.now + 1))
        h.up(canceled); h.advance(3000)
        assertTrue(h.receiver().isEmpty())
    }

    @Test fun lockCancelsPendingBackAndOriginalHeldTouchWithoutProducingSuccessfulUp() {
        val h = Harness(available = true)
        h.merger.nativeTouch(Touch(Touch.Phase.DOWN, 500, Tap(10, 10, h.size)))
        h.press(); h.press(PhoneHidAction.LOCK); h.advance(2000, canceledToo = true)
        assertFalse(h.controls.holding)
        assertTrue(h.navigation().isEmpty())
        assertEquals(listOf(ReceiverInputKind.LOCK), h.receiver())
        assertEquals(listOf(Touch.Phase.DOWN), h.touches.map { it.phase })
    }

    @Test fun functionKeepsEveryAcceptedActivationAndNeverBecomesNavigation() {
        val h = Harness(available = true)
        h.press(PhoneHidAction.FUNCTION); h.now += 100; h.press(PhoneHidAction.FUNCTION)
        h.advance(2000)
        assertEquals(List(2) { FunctionIntentKind.ACTIVATE }, h.functions.map { it.kind })
        assertTrue(h.frames.isEmpty())
    }
}
