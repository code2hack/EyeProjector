package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Actual app controller -> captured route -> actual Session merger; no hardware/Binder claim. */
class PhoneHidFunctionCompositionTest {
    private class Harness {
        data class Task(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        var clock = 100L
        private val tasks = mutableListOf<Task>()
        private val lock = Any()
        val navigation = mutableListOf<Navigation>()
        val intentions = mutableListOf<FunctionIntent>()
        val receiver = mutableListOf<ReceiverInput>()
        val routes = mutableListOf<PhoneHidRoute>()
        val binding = PhoneHidBinding(
            PhoneHidButton(PhoneHidDeviceIdentity("composition-peripheral", 123, 456), 24, 55),
            "Test peripheral", "Test button")
        val store = PhoneHidBindings({ null }, { true })
        private val size = Geometry(100, 100)
        private val controls = PhoneTouchDispatcher(lock) {}
        private val merger = PhoneInputMerger(lock, { clock }, { at, action ->
            val task = Task(at, action)
            tasks.add(task)
            val cancel: () -> Unit = { task.canceled = true }
            cancel
        }, { size }, controls::touch, controls::scroll,
            { action, _ -> navigation.add(action) }, { controls.cancel() }, { receiver.add(it); Unit }, functionIntent = { intentions.add(it) })
        private var routeSequence = 0L
        val controller = PhoneHidController(store, object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute {
                val id = ++routeSequence
                val route = PhoneHidRoute(down, 1L) { merger.edge(id, it) }
                routes.add(route)
                merger.edge(id, down)
                return route
            }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = route.cancel(eventTimeMs)
        }, { operation, complete -> operation(); complete() }, { clock })

        init {
            controls.geometry(size)
            merger.pointer(HeadPointerState(1, 1, true, true, 50f, 50f, Fit(0, 0, 100, 100), size),
                ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 1, ReceiverUsabilityMode.PROJECTED))
            assertTrue(store.assign(PhoneHidAction.FUNCTION, binding))
            controller.connection(true)
        }

        fun press() {
            val down = clock
            assertTrue(controller.accept(PhoneHidController.Event(13, binding, PhoneHidPhase.DOWN, down, down)))
            clock += 10
            assertTrue(controller.accept(PhoneHidController.Event(13, binding, PhoneHidPhase.UP, down, clock)))
        }

        fun advance(ms: Long, runCanceled: Boolean = false) {
            val end = clock + ms
            while (true) {
                val task = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                clock = maxOf(clock, task.at)
                if (!task.canceled || runCanceled) task.action()
            }
            clock = end
        }
    }

    @Test fun phoneLocalRetirementPreservesCompletedIntentionAndFreshInputStillWorks() {
        val cases: List<Pair<String, (Harness) -> Unit>> = listOf(
            "reset" to { it.controller.resetAll() },
            "open page" to { it.controller.openPage() },
            "close page" to { it.controller.closePage() },
            "select row" to { it.controller.select(PhoneHidAction.TAP) },
            "interrupt" to { it.controller.cancelInput() },
            "service disconnect" to { it.controller.connection(false) },
            "device removal" to { it.controller.deviceRemoved(13) },
        )
        for ((name, retire) in cases) {
            val h = Harness()
            if (name == "close page" || name == "select row") h.controller.openPage()
            h.press()
            retire(h)
            h.advance(600, runCanceled = true)
            assertEquals(name, emptyList<Navigation>(), h.navigation)
            assertEquals(name, emptyList<ReceiverInput>(), h.receiver)
            assertEquals(name, listOf(FunctionIntentKind.ACTIVATE), h.intentions.map { it.kind })
            h.controller.closePage()
            h.controller.connection(true)
            assertTrue(h.store.assign(PhoneHidAction.FUNCTION, h.binding))
            h.press()
            h.advance(600)
            assertEquals("$name must allow a fresh gesture", List(2) { FunctionIntentKind.ACTIVATE }, h.intentions.map { it.kind })
            assertTrue(h.navigation.isEmpty())
        }
    }

    @Test fun retainingReleasedRouteDoesNotCoalesceTwoActivations() {
        val h = Harness()
        h.press()
        h.advance(100)
        h.press()
        h.advance(600)
        assertEquals(List(2) { FunctionIntentKind.ACTIVATE }, h.intentions.map { it.kind })
        assertTrue(h.navigation.isEmpty())
        assertTrue(h.receiver.isEmpty())
    }

    @Test fun threeFunctionPressesRemainThreeActivations() {
        val h = Harness()
        repeat(3) { h.press(); h.advance(100) }
        h.advance(600)
        assertTrue(h.navigation.isEmpty())
        assertEquals(List(3) { FunctionIntentKind.ACTIVATE }, h.intentions.map { it.kind })
        assertTrue(h.receiver.isEmpty())
    }

    @Test fun cancelingAnOlderCompletedRouteCannotCancelANewerPendingSequence() {
        val h = Harness()
        h.press()
        val old = h.routes.single()
        h.advance(600)
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), h.intentions.map { it.kind })
        h.press()
        old.cancel(h.clock)
        h.advance(600)
        assertEquals(List(2) { FunctionIntentKind.ACTIVATE }, h.intentions.map { it.kind })
    }
}
