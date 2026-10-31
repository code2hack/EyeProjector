package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Actual controller -> actual captured route -> actual pure grammar; no Binder/Session claim. */
class PhoneHidBackHomeOwnershipTest {
    private class Harness {
        data class Task(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        data class Capture(val context: Any)
        data class Edge(val route: PhoneHidRoute, val value: PhoneHidEdge)
        var now = 1000L
        var context = Any()
        val actions = mutableListOf<Pair<BackHomeGestureRecognizer.Action, Capture>>()
        val completedFunctions = mutableListOf<PhoneHidEdge>()
        val deliveries = mutableListOf<Edge>()
        val routes = mutableListOf<PhoneHidRoute>()
        val tasks = mutableListOf<Task>()
        var onCapture: (PhoneHidEdge) -> Unit = {}
        val binding = PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity("original device", 1, 2), 42, 84), "Remote", "Learned button")
        val functionBinding = binding.copy(button = binding.button.copy(keyCode = 43, scanCode = 85))
        val store = PhoneHidBindings({ null }) { true }
        val grammar = BackHomeGestureRecognizer<Capture>({ now }, { it.context === context }, { at, action ->
            val task = Task(at, action).also(tasks::add)
            val cancel: () -> Unit = { task.canceled = true }; cancel
        }, { action, captured -> actions.add(action to captured) })
        val controller = PhoneHidController(store, object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute {
                val token = Any()
                val captured = Capture(context)
                lateinit var route: PhoneHidRoute
                fun send(edge: PhoneHidEdge) {
                    deliveries.add(Edge(route, edge))
                    when (edge.action) {
                        PhoneHidAction.BACK_HOME -> when (edge.phase) {
                            PhoneHidPhase.DOWN -> grammar.down(token, Triple(edge.attachmentId, edge.keyCode, edge.scanCode), captured)
                            PhoneHidPhase.UP -> grammar.up(token, edge.eventTimeMs)
                            PhoneHidPhase.CANCEL -> grammar.cancel(token)
                        }
                        // Completed Function consumer boundary only. No Function grammar is replaced or tested here.
                        PhoneHidAction.FUNCTION -> if (edge.phase == PhoneHidPhase.UP) completedFunctions.add(edge)
                        else -> Unit
                    }
                }
                route = PhoneHidRoute(down, 1, ::send)
                routes.add(route)
                route.submit(down)
                onCapture(down)
                return route
            }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = route.cancel(eventTimeMs)
        }, { operation, complete -> operation(); complete() }, { now })
        init {
            store.assign(PhoneHidAction.BACK_HOME, binding)
            store.assign(PhoneHidAction.FUNCTION, functionBinding)
            controller.connection(true)
        }
        fun down(value: PhoneHidBinding = binding): PhoneHidController.Event {
            val edge = PhoneHidController.Event(7, value, PhoneHidPhase.DOWN, now, now)
            assertTrue(controller.accept(edge))
            return edge
        }
        fun up(edge: PhoneHidController.Event) {
            now += 10
            assertTrue(controller.accept(edge.copy(phase = PhoneHidPhase.UP, eventTimeMs = now)))
        }
        fun press(value: PhoneHidBinding = binding) { up(down(value)) }
        fun advance(at: Long, runCanceled: Boolean = false) {
            now = at
            while (true) {
                val task = tasks.firstOrNull { it.at <= at } ?: return
                tasks.remove(task)
                if (!task.canceled || runCanceled) task.action()
            }
        }
    }

    private fun retirementCases(): List<Pair<String, (Harness) -> Unit>> = listOf(
        "reset" to { it.controller.resetAll() },
        "open page" to { it.controller.openPage() },
        "close page" to { it.controller.closePage() },
        "select row" to { it.controller.select(PhoneHidAction.TAP) },
        "interrupt" to { it.controller.cancelInput() },
        "presentation loss" to { it.controller.presentationLost() },
        "service disconnect" to { it.controller.connection(false) },
        "device removal" to { it.controller.deviceRemoved(7) },
    )

    @Test fun cancelAfterUpRetiresOriginalPendingBackAcrossEveryControllerBoundary() {
        for ((name, retire) in retirementCases()) {
            val h = Harness()
            if (name == "close page" || name == "select row") h.controller.openPage()
            h.press()
            val original = h.routes.single()
            retire(h)
            assertEquals(name, original, h.deliveries.last().route)
            assertEquals(name, PhoneHidPhase.CANCEL, h.deliveries.last().value.phase)
            h.advance(2000, runCanceled = true)
            assertTrue(name, h.actions.isEmpty())
            h.controller.closePage(); h.controller.connection(true)
            h.store.assign(PhoneHidAction.BACK_HOME, h.binding)
            h.press(); h.advance(2600)
            assertEquals(name, listOf(BackHomeGestureRecognizer.Action.BACK), h.actions.map { it.first })
        }
    }

    @Test fun retirementBetweenSecondDownAndUpCancelsDoubleAndConsumesLateRelease() {
        for ((name, retire) in retirementCases()) {
            val h = Harness()
            if (name == "close page" || name == "select row") h.controller.openPage()
            h.press(); h.now = 1100; val second = h.down()
            val original = h.routes.toList()
            retire(h); h.up(second); h.advance(2000, runCanceled = true)
            assertTrue(name, h.actions.isEmpty())
            original.forEach { route ->
                assertTrue(name, h.deliveries.any { it.route === route && it.value.phase == PhoneHidPhase.CANCEL })
            }
            assertEquals(name, 2, h.routes.size)
        }
    }

    @Test fun completedDoubleHasOnlyOneHomeAndRetirementNeverProducesAnExtraBack() {
        val h = Harness()
        val original = h.context
        h.press(); h.now = 1100; h.press()
        h.controller.cancelInput(); h.advance(2000, runCanceled = true)
        assertEquals(listOf(BackHomeGestureRecognizer.Action.HOME), h.actions.map { it.first })
        assertSame(original, h.actions.single().second.context)
    }

    @Test fun successorContextCannotInheritPendingSingleOrDouble() {
        for (secondHeld in listOf(false, true)) {
            val h = Harness()
            h.press(); h.now = 1100
            val second = if (secondHeld) h.down() else null
            val old = h.context
            h.context = Any()
            if (second != null) h.up(second)
            h.advance(2000, runCanceled = true)
            assertTrue(h.actions.isEmpty())
            h.controller.cancelInput(); h.press(); h.advance(2600)
            assertEquals(1, h.actions.size)
            assertNotSame(old, h.actions.single().second.context)
        }
    }

    @Test fun repeatDuplicateReleaseAndOldRouteCancelCannotMultiplyOrRetireFreshBack() {
        val h = Harness()
        val first = h.down()
        assertTrue(h.controller.accept(first.copy(eventTimeMs = 1005, repeatCount = 1)))
        h.up(first)
        assertTrue(h.controller.accept(first.copy(phase = PhoneHidPhase.UP, eventTimeMs = h.now)))
        val old = h.routes.single()
        h.advance(1600)
        h.press(); old.cancel(h.now); h.advance(2200)
        assertEquals(listOf(BackHomeGestureRecognizer.Action.BACK, BackHomeGestureRecognizer.Action.BACK), h.actions.map { it.first })
        assertEquals(2, h.routes.size)
    }

    @Test fun separateCompletedFunctionDoesNotDisplaceReleasedBackHomeOwnership() {
        val h = Harness()
        h.press(); val back = h.routes.single()
        h.now = 1100; h.press(h.functionBinding)
        assertEquals(1, h.completedFunctions.size)
        h.controller.presentationLost(); h.advance(2000, runCanceled = true)
        assertTrue(h.actions.isEmpty())
        assertEquals(1, h.completedFunctions.size)
        assertTrue(h.deliveries.any { it.route === back && it.value.phase == PhoneHidPhase.CANCEL })
    }

    @Test fun reentrantUpBeforeRouteReturnStillRetainsOriginalPendingBackForCancellation() {
        val h = Harness()
        h.onCapture = { down ->
            h.now = down.eventTimeMs + 10
            h.controller.accept(PhoneHidController.Event(down.attachmentId, h.binding,
                PhoneHidPhase.UP, down.downTimeMs, h.now))
        }
        h.down()
        assertEquals(listOf(PhoneHidPhase.DOWN, PhoneHidPhase.UP), h.deliveries.map { it.value.phase })
        h.controller.presentationLost(); h.advance(2000, runCanceled = true)
        assertEquals(PhoneHidPhase.CANCEL, h.deliveries.last().value.phase)
        assertSame(h.routes.single(), h.deliveries.last().route)
        assertTrue(h.actions.isEmpty())
    }

    @Test fun bindingPublicationDuringPressCannotChangeItsActionOrReleasedRouteOwner() {
        val h = Harness()
        val first = h.down()
        h.store.assign(PhoneHidAction.FUNCTION, h.binding)
        h.up(first)
        assertEquals(listOf(PhoneHidAction.BACK_HOME, PhoneHidAction.BACK_HOME), h.deliveries.map { it.value.action })
        h.controller.cancelInput(); h.advance(2000, runCanceled = true)
        assertTrue(h.completedFunctions.isEmpty())
        assertTrue(h.actions.isEmpty())
        assertEquals(PhoneHidAction.BACK_HOME, h.deliveries.last().value.action)
        assertEquals(PhoneHidPhase.CANCEL, h.deliveries.last().value.phase)
    }
}
