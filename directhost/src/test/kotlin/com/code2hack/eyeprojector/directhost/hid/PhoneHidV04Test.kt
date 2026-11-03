package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.IPhoneInputSession
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.input.*
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Actual controller -> route -> merger composition, with deterministic source time and scheduler. */
class PhoneHidV04Test {
    private class Fixture {
        var now = 1000L
        data class Job(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        val jobs = mutableListOf<Job>()
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { at, action ->
            val job = Job(at, action); jobs.add(job)
            val cancel: () -> Unit = { job.canceled = true }; cancel
        }
        fun advance(to: Long) {
            while (true) {
                val job = jobs.filter { !it.canceled && it.at <= to }.minByOrNull { it.at } ?: break
                now = maxOf(now, job.at); job.canceled = true; job.action()
            }
            now = to
        }
        val size = Geometry(480, 640)
        var pointerSequence = 0L
        val touches = mutableListOf<Touch>()
        val scrolls = mutableListOf<Scroll>()
        val navigation = mutableListOf<Navigation>()
        val receiver = mutableListOf<ReceiverInput>()
        var modes = 0
        var onMode: (() -> Unit)? = null
        var onCaptured: ((PhoneHidEdge) -> Unit)? = null
        var ownerEpoch = 1
        var sourceActive = true
        var intercept: (PhoneHidAction, HeadPointerState) -> Boolean = { _, _ -> false }
        var record: String? = null
        var writeAllowed = true
        val store = PhoneHidBindings({ record }) { if (writeAllowed) { record = it; true } else false }
        val lock = Any()
        val merger = PhoneInputMerger(lock, { now }, schedule, { size }, { touches.add(it); true },
            { scrolls.add(it); true }, { kind, _ -> navigation.add(kind) }, {}, receiver::add,
            active = { sourceActive },
            hidMode = { assertFalse(Thread.holdsLock(lock)); modes++; onMode?.invoke() },
            hidAction = { action, point -> intercept(action, point) })
        var routeSequence = 0L
        val captured = mutableListOf<PhoneHidEdge>()
        val controller = PhoneHidController(store, object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute {
                captured.add(down)
                val id = ++routeSequence
                val owner = ownerEpoch
                val original = PhoneHidOriginal(object : IPhoneInputSession {
                    override fun active() = sourceActive
                    override fun edge(routeId: Long, typedEdge: ByteArray, regionId: String, word: Long) {
                        assertEquals("original-fixture", regionId); assertEquals(1L, word)
                        merger.stampedEdge(routeId, PhoneHidEdge.from(typedEdge)) { _, _ -> ownerEpoch == owner }
                    }
                }, ProjectionContext()) { ownerEpoch == owner }
                return checkNotNull(original.route(down, "original-fixture", 1, 1, id))
                    .also { onCaptured?.invoke(down) }
            }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = route.cancel(eventTimeMs)
        }, { work, complete -> work(); complete() }, { now }, schedule = schedule)
        val device = PhoneHidDeviceIdentity("actual descriptor", 1, 2)
        fun binding(code: Int = 20) = PhoneHidBinding(PhoneHidButton(device, code, code + 10), "Device", "Key $code")
        fun assign(action: PhoneHidAction, gesture: PhoneHidGesture = PhoneHidGesture.SINGLE, key: Int = 20) {
            assertTrue(store.assign(action, binding(key).copy(gesture = gesture)))
        }
        fun pointer(available: Boolean = true, revision: Long = 1, projected: Boolean = true, x: Float = 120f) {
            merger.pointer(HeadPointerState(revision, ++pointerSequence, available, projected,
                x, 200f, Fit(0, 0, 480, 640), size))
        }
        init { pointer(); controller.connection(true) }
        fun down(key: Int = 20, attachment: Int = 7) = PhoneHidController.Event(attachment, binding(key),
            PhoneHidPhase.DOWN, now, now).also { assertTrue(controller.accept(it)) }
        fun up(down: PhoneHidController.Event, after: Long = 20) {
            advance(now + after)
            assertTrue(controller.accept(down.copy(phase = PhoneHidPhase.UP, eventTimeMs = now)))
        }
        fun clicks(count: Int, key: Int = 20) {
            repeat(count) { up(down(key)); if (it + 1 < count) advance(now + 100) }
        }
        fun outputs() = touches.size + scrolls.size + navigation.size + receiver.size + modes
    }

    @Test fun sharedShortLongAndDoubleResolveTogetherAndSingleOnlyOtherKeyStaysImmediate() {
        val f = Fixture(); f.assign(PhoneHidAction.BACK); f.assign(PhoneHidAction.HOME, PhoneHidGesture.LONG)
        f.clicks(1); assertEquals(listOf(Navigation.BACK), f.navigation)
        val held = f.down(); f.advance(f.now + 800); assertEquals(1, f.outputs())
        f.up(held, 0); assertEquals(listOf(Navigation.BACK, Navigation.CONTEXTUAL_HOME), f.navigation)
        f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.assign(PhoneHidAction.LOCK, key = 21)
        f.clicks(1); assertEquals(2, f.outputs())
        f.advance(f.now + 100); f.clicks(1, 21)
        assertEquals(listOf(ReceiverInputKind.LOCK), f.receiver.map { it.kind })
        f.advance(f.now + 100); f.clicks(1); assertEquals(1, f.modes)
        assertEquals(2, f.navigation.size)
        f.advance(f.now + 50); f.clicks(1); val at = f.now
        f.advance(at + 499); assertEquals(2, f.navigation.size)
        f.advance(at + 500); assertEquals(listOf(Navigation.BACK, Navigation.CONTEXTUAL_HOME, Navigation.BACK), f.navigation)
        assertTrue(f.captured.filter { it.keyCode == 20 }.takeLast(3).all { it.bindings.size == 3 })
    }

    @Test fun sharedDoubleTripleWaitsForLargerCountAndUnboundCountExpiresWithoutAction() {
        val f = Fixture(); f.assign(PhoneHidAction.BACK, PhoneHidGesture.clicks(2))
        f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(3))
        f.clicks(2); assertEquals(0, f.outputs()); val two = f.now
        f.advance(two + 499); assertEquals(0, f.outputs())
        f.clicks(1); assertEquals(1, f.modes); assertTrue(f.navigation.isEmpty())
        f.advance(f.now + 50); f.clicks(2); assertEquals(1, f.outputs())
        f.advance(f.now + 500); assertEquals(listOf(Navigation.BACK), f.navigation)
        f.clicks(1); f.advance(f.now + 500); assertEquals(2, f.outputs())
        f.clicks(3); assertEquals(2, f.modes)
    }

    @Test fun sharedSingleDoubleKeepsBoundaryAndCancelsMixedClickThenHold() {
        for (gap in listOf(499L, 500L, 501L)) {
            val f = Fixture(); f.assign(PhoneHidAction.BACK); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
            f.assign(PhoneHidAction.HOME, PhoneHidGesture.LONG)
            f.clicks(1); val firstUp = f.now
            f.now = firstUp + gap; f.up(f.down(), 0)
            assertEquals(if (gap < 500) 1 else 0, f.modes)
            assertEquals(if (gap < 500) 0 else 1, f.navigation.size)
            f.advance(f.now + 500)
            assertEquals(if (gap < 500) 0 else 2, f.navigation.size)
        }
        val f = Fixture(); f.assign(PhoneHidAction.BACK); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.assign(PhoneHidAction.HOME, PhoneHidGesture.LONG)
        f.clicks(1); f.advance(f.now + 100); f.up(f.down(), 800)
        f.advance(f.now + 500); assertEquals(0, f.outputs())
        f.up(f.down(), 800); assertEquals(listOf(Navigation.CONTEXTUAL_HOME), f.navigation)
    }

    @Test fun originalCandidateSetAndOwnerSurviveDeferredCompletionWithoutRecapture() {
        val f = Fixture(); f.assign(PhoneHidAction.BACK); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.clicks(1); val stale = f.jobs.last().action
        f.assign(PhoneHidAction.HOME, PhoneHidGesture.SINGLE)
        f.advance(f.now + 500)
        assertEquals(listOf(Navigation.BACK), f.navigation); assertEquals(1, f.captured.size)
        stale(); assertEquals(1, f.outputs())
        f.clicks(1); val oldTimer = f.jobs.last().action
        f.ownerEpoch++; f.advance(f.now + 500); oldTimer()
        assertEquals(1, f.outputs()); assertEquals(2, f.captured.size)
        f.clicks(1); f.advance(f.now + 500)
        assertEquals(listOf(Navigation.BACK, Navigation.CONTEXTUAL_HOME), f.navigation)
    }

    @Test fun sharedPendingGestureRetiresForEveryOriginalCancellationRoute() {
        val stops: List<(Fixture) -> Unit> = listOf({ it.controller.cancelInput() },
            { it.controller.deviceRemoved(7) }, { it.controller.connection(false) }, { it.controller.resetAll() },
            { it.pointer(revision = 2) }, { it.merger.sourceLost() }, { it.merger.invalidate() }, { it.ownerEpoch++ })
        for (stop in stops) {
            val f = Fixture(); f.assign(PhoneHidAction.BACK); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
            val down = f.down(); f.up(down); val stale = f.jobs.map { it.action }
            stop(f); f.advance(f.now + 1000); stale.forEach { it() }
            f.controller.accept(down.copy(phase = PhoneHidPhase.UP, eventTimeMs = f.now))
            assertEquals(0, f.outputs())
        }
    }

    @Test fun learningDistinctRowsPersistsTogetherAndRawLearningMovesEveryVariant() {
        val f = Fixture(); f.controller.openPage()
        f.controller.select(PhoneHidAction.BACK); f.clicks(1); f.advance(f.now + 500)
        f.controller.select(PhoneHidAction.HOME); f.up(f.down(), 800)
        f.controller.select(PhoneHidAction.MODE); f.clicks(2); f.advance(f.now + 500)
        val key = f.binding().button
        val expected = mapOf(PhoneHidAction.BACK to PhoneHidGesture.SINGLE,
            PhoneHidAction.HOME to PhoneHidGesture.LONG, PhoneHidAction.MODE to PhoneHidGesture.clicks(2))
        assertEquals(expected, f.store.assignments(key).mapValues { it.value.gesture })
        val reload = PhoneHidBindings({ f.record }) { error("Reload must not write") }
        assertEquals(expected, reload.assignments(key).mapValues { it.value.gesture })
        f.controller.select(PhoneHidAction.LOCK); f.up(f.down(), 800)
        assertNull(f.store.binding(PhoneHidAction.HOME)); assertEquals(3, f.store.assignments(key).size)
        assertEquals(0, f.outputs()); assertTrue(f.captured.isEmpty())
        f.controller.closePage(); f.clicks(2); assertEquals(1, f.modes)
        f.controller.openPage(); f.controller.select(PhoneHidAction.TAP); f.clicks(1); f.advance(f.now + 500)
        assertEquals(setOf(PhoneHidAction.TAP), f.store.assignments(key).keys)
        f.controller.select(PhoneHidAction.BACK); f.clicks(1); f.advance(f.now + 500)
        assertNull(f.store.binding(PhoneHidAction.TAP))
        f.controller.select(PhoneHidAction.HOME); f.up(f.down(), 800)
        assertEquals(setOf(PhoneHidAction.BACK, PhoneHidAction.HOME), f.store.assignments(key).keys)
        f.controller.closePage(); f.clicks(1); assertEquals(listOf(Navigation.BACK), f.navigation)
    }

    @Test fun allSevenRolesAndGestureIdentitiesRoundTripThroughActualLocalCodec() {
        assertEquals(listOf("TAP", "SCROLL_DOWN", "SCROLL_UP", "BACK", "HOME", "LOCK", "MODE"),
            PhoneHidAction.entries.map { it.name })
        for (action in PhoneHidAction.entries) for (gesture in listOf(PhoneHidGesture.SINGLE,
            PhoneHidGesture.LONG, PhoneHidGesture.clicks(4))) {
            val edge = PhoneHidEdge(action, PhoneHidPhase.DOWN, 7, 20, 30, 1000, 1001, gesture = gesture)
            assertEquals(39, edge.bytes().size)
            assertEquals(edge, PhoneHidEdge.from(edge.bytes()))
            assertTrue(edge.samePress(edge.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1050)))
            assertFalse(edge.samePress(edge.copy(gesture = if (gesture == PhoneHidGesture.LONG)
                PhoneHidGesture.SINGLE else PhoneHidGesture.LONG)))
        }
    }

    @Test fun allRolesLearnTheAllowedCompleteGesturesWithoutAnyRouteOrAppEffects() {
        for (action in PhoneHidAction.entries) {
            val allowed = if (action.learnsSingleOnly) listOf(PhoneHidGesture.SINGLE)
                else listOf(PhoneHidGesture.SINGLE, PhoneHidGesture.LONG, PhoneHidGesture.clicks(2), PhoneHidGesture.clicks(4))
            for (gesture in allowed) {
                val f = Fixture(); f.controller.openPage(); f.controller.select(action)
                when (gesture.kind) {
                    PhoneHidGesture.Kind.LONG -> f.up(f.down(), 800)
                    else -> f.clicks(gesture.clicks)
                }
                f.advance(f.now + 500)
                assertEquals("$action $gesture", gesture, f.store.binding(action)?.gesture)
                assertTrue(f.captured.isEmpty()); assertEquals(0, f.outputs())
                assertEquals(gesture, PhoneHidBindings({ f.record }) { error("Reload must not write") }.binding(action)?.gesture)
            }
        }
    }

    @Test fun tapAndScrollDoNotLearnLongOrMultiAsSingle() {
        for (action in PhoneHidAction.entries.filter { it.learnsSingleOnly }) for (long in listOf(false, true)) {
            val f = Fixture(); f.controller.openPage(); f.controller.select(action)
            if (long) f.up(f.down(), 900) else f.clicks(2)
            f.advance(f.now + 500)
            assertNull(f.store.binding(action)); assertEquals(0, f.outputs()); assertTrue(f.captured.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                f.store.assign(action, f.binding().copy(gesture = PhoneHidGesture.LONG))
            }
        }
    }

    @Test fun shortLongAndMultiEachPerformExactlyOneAssignedDiscreteAction() {
        for (action in listOf(PhoneHidAction.BACK, PhoneHidAction.HOME, PhoneHidAction.LOCK, PhoneHidAction.MODE)) {
            for (gesture in listOf(PhoneHidGesture.SINGLE, PhoneHidGesture.LONG, PhoneHidGesture.clicks(3))) {
                val f = Fixture(); f.assign(action, gesture)
                if (gesture == PhoneHidGesture.LONG) f.up(f.down(), 850) else f.clicks(gesture.clicks)
                assertEquals("$action $gesture completes on its final UP", 1, f.outputs())
                f.advance(f.now + 500)
                assertEquals("$action $gesture", 1, f.outputs())
                assertEquals(if (action == PhoneHidAction.MODE) 1 else 0, f.modes)
                if (action == PhoneHidAction.BACK) assertEquals(listOf(Navigation.BACK), f.navigation)
                if (action == PhoneHidAction.HOME) assertEquals(listOf(Navigation.CONTEXTUAL_HOME), f.navigation)
                if (action == PhoneHidAction.LOCK) assertEquals(listOf(ReceiverInputKind.LOCK), f.receiver.map { it.kind })
                f.advance(f.now + 2000); assertEquals(1, f.outputs())
            }
        }
    }

    @Test fun eachShortReleaseCompletesASavedSingleWithoutWaitingForQuiet() {
        for (count in 2..4) {
            val f = Fixture(); f.assign(PhoneHidAction.MODE); f.clicks(count)
            assertEquals(count, f.outputs()); f.advance(f.now + 500); assertEquals(count, f.outputs())
        }
        val f = Fixture(); f.assign(PhoneHidAction.MODE); f.up(f.down(), 900)
        f.advance(3000); assertEquals(0, f.outputs())
    }

    @Test fun multiModeCompletesOnNthReleaseAndDuplicateEdgesNeverDispatchAgain() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        val first = f.down(); f.up(first)
        val stale = f.jobs.single().action
        f.advance(1100); val second = f.down()
        assertTrue(f.controller.accept(second.copy(repeatCount = 1, eventTimeMs = 1110)))
        f.up(second); assertEquals(1, f.modes)
        stale(); assertEquals(1, f.modes)
        f.advance(1620); assertEquals(1, f.modes)
        f.controller.accept(second.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1630))
        stale(); f.advance(3000); assertEquals(1, f.modes)
    }

    @Test fun learnedLongOnlyDispatchesOnTheOriginalRelease() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.LONG)
        val press = f.down(); f.advance(5000); assertEquals(0, f.modes)
        f.up(press, 0); assertEquals(1, f.modes)
        f.controller.accept(press.copy(phase = PhoneHidPhase.UP, eventTimeMs = 5010))
        f.advance(6000); assertEquals(1, f.modes)
    }

    @Test fun completedDoubleBurstsRetireBeforeTheNextRapidClick() {
        for (action in listOf(PhoneHidAction.BACK, PhoneHidAction.HOME, PhoneHidAction.LOCK, PhoneHidAction.MODE)) {
            val f = Fixture(); f.assign(action, PhoneHidGesture.clicks(2))
            repeat(4) { index ->
                f.up(f.down())
                assertEquals("$action click ${index + 1}", (index + 1) / 2, f.outputs())
                f.advance(f.now + 30)
            }
            f.jobs.toList().forEach { it.action() }
            f.advance(4000); assertEquals(2, f.outputs())
        }
    }

    @Test fun incompleteMultiExpiresWithoutActionAndBoundaryDownBeginsFresh() {
        for (gap in listOf(499L, 500L, 501L)) {
            val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
            f.clicks(1); val firstUp = f.now
            // Deliver the next DOWN before running due callbacks: source event time defines the gap.
            f.now = firstUp + gap
            val second = f.down(); f.up(second, 0)
            assertEquals(if (gap < 500) 1 else 0, f.modes)
            f.advance(f.now + 500)
            assertEquals(if (gap < 500) 1 else 0, f.modes)
            f.clicks(2); assertEquals(if (gap < 500) 2 else 1, f.modes)
        }
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(3))
        f.clicks(2); f.advance(f.now + 500); assertEquals(0, f.modes)
        f.clicks(3); assertEquals(1, f.modes)
    }

    @Test fun multiGapOnlyBoundsTimeUntilNextDownAndNeverCutsOffAHeldPress() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.clicks(1); f.advance(f.now + 499)
        val next = f.down(); val stale = f.jobs.first().action
        f.advance(f.now + 799); stale(); assertEquals(0, f.modes)
        f.up(next, 0); assertEquals(1, f.modes)
    }

    @Test fun longThresholdAndShortPrefixBehaviorStayOnOriginalRelease() {
        for (duration in listOf(799L, 800L, 5000L)) {
            val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.LONG)
            val press = f.down(); f.advance(f.now + duration); assertEquals(0, f.modes)
            f.up(press, 0); assertEquals(if (duration >= 800) 1 else 0, f.modes)
            f.advance(f.now + 500); assertEquals(if (duration >= 800) 1 else 0, f.modes)
        }
        for (gesture in listOf(PhoneHidGesture.LONG, PhoneHidGesture.clicks(2))) {
            val f = Fixture(); f.assign(PhoneHidAction.MODE, gesture)
            f.clicks(1); f.advance(f.now + 100); f.up(f.down(), 800)
            f.advance(f.now + 500); assertEquals(0, f.modes)
            f.up(f.down(), 800); assertEquals(if (gesture == PhoneHidGesture.LONG) 1 else 0, f.modes)
        }
    }

    @Test fun learningDiscoversMultiAfterQuietEvenWhenASingleBindingAlreadyExists() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE)
        val saved = f.record
        f.controller.openPage(); f.controller.select(PhoneHidAction.MODE)
        f.clicks(4)
        assertEquals(saved, f.record); assertEquals(0, f.outputs()); assertTrue(f.captured.isEmpty())
        f.advance(f.now + 499); assertEquals(saved, f.record)
        f.advance(f.now + 1)
        assertEquals(PhoneHidGesture.clicks(4), f.store.binding(PhoneHidAction.MODE)?.gesture)
        assertEquals(0, f.outputs()); assertTrue(f.captured.isEmpty())
    }

    @Test fun originalDownCapturesSavedGestureDespitePublicationBeforeItsUp() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE)
        val press = f.down()
        f.assign(PhoneHidAction.BACK, PhoneHidGesture.clicks(2))
        f.up(press); assertEquals(1, f.modes); assertTrue(f.navigation.isEmpty())
        f.clicks(1); assertTrue(f.navigation.isEmpty())
        f.advance(f.now + 100); f.clicks(1); assertEquals(listOf(Navigation.BACK), f.navigation)
        f.advance(4000); assertEquals(1, f.modes); assertEquals(1, f.navigation.size)
    }

    @Test fun oldIncompleteTimerAndTerminalsCannotFinishAReplacementSequence() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        val old = f.down(); f.up(old); val stale = f.jobs.last().action
        f.controller.cancelInput(); f.advance(f.now + 100)
        f.clicks(1)
        f.controller.accept(old.copy(phase = PhoneHidPhase.UP, eventTimeMs = f.now))
        stale(); assertEquals(0, f.modes)
        f.advance(f.now + 100); f.clicks(1); assertEquals(1, f.modes)
        stale(); f.advance(4000); assertEquals(1, f.modes)
    }

    @Test fun heldSingleAndIncompleteMultiAreCanceledByLaunchAndLossWithoutReplay() {
        val stops: List<(Fixture) -> Unit> = listOf(
            { it.merger.invalidateForLaunch() }, { it.merger.invalidate() },
            { it.controller.connection(false) }, { it.controller.cancelInput() },
            { it.controller.deviceRemoved(7) }, { it.merger.sourceLost() },
            { it.pointer(revision = 2) }, { it.ownerEpoch++ })
        for (stop in stops) for (multi in listOf(false, true)) {
            val f = Fixture(); f.assign(PhoneHidAction.MODE,
                if (multi) PhoneHidGesture.clicks(2) else PhoneHidGesture.SINGLE)
            if (multi) { f.clicks(1); f.advance(f.now + 100) }
            val held = f.down(); val callbacks = f.jobs.map { it.action }
            stop(f); f.up(held); callbacks.forEach { it() }
            f.advance(4000); assertEquals(0, f.modes)
        }
    }

    @Test fun scrollActsOnDownAndRepeatsUntilOriginalUpWithoutReleaseAction() {
        for (action in listOf(PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP)) {
            val f = Fixture(); f.assign(action); val press = f.down()
            assertEquals(1, f.scrolls.size)
            f.advance(1399); assertEquals(1, f.scrolls.size)
            f.advance(1600); assertEquals(4, f.scrolls.size)
            assertTrue(f.scrolls.all { it.pixels == if (action == PhoneHidAction.SCROLL_DOWN) 160 else -160 })
            f.up(press, 0); f.advance(3000); assertEquals(4, f.scrolls.size)
        }
    }

    @Test fun originalScrollRepeatAndReleaseTailsDoNotBecomeANewPressAfterStop() {
        val f = Fixture(); f.assign(PhoneHidAction.SCROLL_DOWN)
        val first = f.down(); val oldTimer = f.jobs.single().action
        f.controller.cancelInput(); f.advance(2000)
        oldTimer(); f.controller.accept(first.copy(repeatCount = 3, eventTimeMs = f.now))
        f.controller.accept(first.copy(phase = PhoneHidPhase.UP, eventTimeMs = f.now))
        assertEquals(1, f.scrolls.size)
        val next = f.down(); oldTimer(); assertEquals(2, f.scrolls.size)
        f.advance(2400); assertEquals(3, f.scrolls.size); f.up(next)
    }

    @Test fun scrollDeviceLossConnectionLossResetAndPointerLossRetireTheOriginalTimer() {
        val stops: List<(Fixture) -> Unit> = listOf(
            { it.controller.deviceRemoved(7) }, { it.controller.connection(false) },
            { it.controller.resetAll() }, { it.pointer(available = false) },
            { it.merger.invalidate() }, { it.merger.sourceLost() })
        for (stop in stops) {
            val f = Fixture(); f.assign(PhoneHidAction.SCROLL_DOWN); val press = f.down()
            val callbacks = f.jobs.map { it.action }
            stop(f); f.advance(2000); callbacks.forEach { it() }
            f.controller.accept(press.copy(phase = PhoneHidPhase.UP, eventTimeMs = 2000))
            assertEquals(1, f.scrolls.size)
        }
    }

    @Test fun tapKeepsRawMultiTapAndRealHoldDragUntilOriginalRelease() {
        val f = Fixture(); f.assign(PhoneHidAction.TAP)
        repeat(3) { f.up(f.down()); f.advance(f.now + 50) }
        assertEquals(List(3) { listOf(Touch.Phase.DOWN, Touch.Phase.UP) }.flatten(), f.touches.map { it.phase })
        val press = f.down(); f.advance(6000); f.pointer(x = 300f)
        assertTrue(f.controller.accept(press.copy(repeatCount = 4, eventTimeMs = f.now)))
        f.up(press)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.MOVE, Touch.Phase.UP), f.touches.takeLast(3).map { it.phase })
        assertEquals(300, f.touches.last().target.x); assertEquals(0, f.modes)
    }

    @Test fun modeSemanticPortConsumesNoOldAnchorAndSettingsInterceptionOwnsWholeTap() {
        val f = Fixture(); f.assign(PhoneHidAction.TAP)
        val semantic = mutableListOf<PhoneHidAction>()
        f.intercept = { action, _ -> semantic.add(action); true }
        val press = f.down(); f.pointer(x = 150f); f.up(press)
        assertEquals(listOf(PhoneHidAction.TAP), semantic); assertTrue(f.touches.isEmpty())
        f.assign(PhoneHidAction.SCROLL_DOWN); val scroll = f.down(); f.advance(f.now + 600); f.up(scroll)
        assertEquals(4, semantic.count { it == PhoneHidAction.SCROLL_DOWN }); assertTrue(f.scrolls.isEmpty())
        f.assign(PhoneHidAction.BACK); f.clicks(1); f.advance(f.now + 500)
        assertEquals(1, semantic.count { it == PhoneHidAction.BACK }); assertTrue(f.navigation.isEmpty())
    }

    @Test fun pendingModeIsCanceledOnDeviceLossStopResetAndContextChange() {
        val stops: List<(Fixture) -> Unit> = listOf({ it.controller.cancelInput() },
            { it.controller.deviceRemoved(7) }, { it.controller.resetAll() },
            { it.pointer(revision = 2) }, { it.merger.sourceLost() })
        for (stop in stops) {
            val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2)); f.clicks(1)
            val oldTimer = f.jobs.last().action
            stop(f); f.advance(3000); oldTimer(); assertEquals(0, f.modes)
        }
    }

    @Test fun learningCancellationAndRejectedWriteRetainTheOldBinding() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE)
        val saved = f.record
        f.controller.openPage(); f.controller.select(PhoneHidAction.MODE)
        f.clicks(2); f.controller.closePage(); f.advance(3000)
        assertEquals(saved, f.record); assertEquals(0, f.outputs())
        f.controller.openPage(); f.controller.select(PhoneHidAction.MODE); f.writeAllowed = false
        f.up(f.down(), 900)
        assertEquals(saved, f.record); assertEquals(PhoneHidGesture.SINGLE, f.store.binding(PhoneHidAction.MODE)?.gesture)
        assertNotNull(f.store.error); assertEquals(0, f.outputs())
    }

    @Test fun wakeConsumedScrollHasNoRepeatsOrReleaseReplayAfterReadiness() {
        val f = Fixture(); f.assign(PhoneHidAction.SCROLL_DOWN)
        var ready = false; var requests = 0
        f.controller.activation = object : PhoneHidActivation {
            override fun needsWake() = !ready
            override fun request(cause: ActivationCause) { requests++ }
        }
        val wake = f.down(); ready = true
        f.controller.accept(wake.copy(repeatCount = 5, eventTimeMs = 1200))
        f.up(wake); f.advance(2000)
        assertEquals(1, requests); assertEquals(0, f.outputs())
        f.up(f.down()); assertEquals(1, f.scrolls.size)
    }


    @Test fun separateDiscreteKeysRetainBothPendingGestures() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, key = 20); f.assign(PhoneHidAction.BACK, key = 21)
        f.clicks(1, 20); f.advance(1100); f.clicks(1, 21)
        f.advance(2000)
        assertEquals(1, f.modes); assertEquals(listOf(Navigation.BACK), f.navigation)
    }

    @Test fun modeDispatchDoesNotTerminateAnIndependentHeldScroll() {
        val f = Fixture(); f.assign(PhoneHidAction.SCROLL_DOWN, key = 20)
        f.assign(PhoneHidAction.MODE, key = 21)
        val scroll = f.down(20); f.advance(1100); f.clicks(1, 21)
        f.advance(1720); assertEquals(1, f.modes); assertEquals(5, f.scrolls.size)
        f.up(scroll, 0); f.advance(3000); assertEquals(5, f.scrolls.size)
    }

    @Test fun delayedDiscreteGestureKeepsOriginalStampedOwnerAndCannotAdoptSuccessor() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2)); f.clicks(1)
        val stale = f.jobs.last().action
        f.ownerEpoch++; f.advance(3000); stale(); assertEquals(0, f.modes)
        f.clicks(2); assertEquals(1, f.modes)
    }

    @Test fun navigationAndModeRemainNonspatialThroughTrackingLossAndLocalPanel() {
        for (action in listOf(PhoneHidAction.MODE, PhoneHidAction.BACK, PhoneHidAction.HOME, PhoneHidAction.LOCK)) {
            val f = Fixture(); f.pointer(projected = false); f.assign(action); f.clicks(1)
            f.pointer(projected = false, available = false); f.advance(2000)
            assertEquals(1, f.outputs())
            if (action != PhoneHidAction.MODE) assertEquals(action.name, f.receiver.single().kind.name)
        }
    }

    @Test fun upDuringCaptureStillOwnsPendingGrammarAndStopCancelsIt() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.onCaptured = { edge ->
            f.onCaptured = null
            f.controller.accept(PhoneHidController.Event(edge.attachmentId, f.binding(), PhoneHidPhase.UP,
                edge.downTimeMs, edge.eventTimeMs + 20))
        }
        f.down(); f.controller.cancelInput(); f.advance(3000)
        assertEquals(0, f.modes)
    }

    @Test fun modeCallbackCanReenterAndDispatchANewOriginalWithoutDeadlock() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE)
        f.onMode = { if (f.modes == 1) f.clicks(1) }
        f.clicks(1); f.advance(3000)
        assertEquals(2, f.modes)
    }

    @Test fun mappingPublicationDoesNotChangeAnAlreadyAdmittedGesture() {
        val f = Fixture(); f.assign(PhoneHidAction.MODE, PhoneHidGesture.LONG)
        val down = f.down()
        f.assign(PhoneHidAction.BACK, PhoneHidGesture.SINGLE)
        f.up(down, 900); f.advance(3000)
        assertEquals(1, f.modes); assertTrue(f.navigation.isEmpty())
    }

    @Test fun legacyMigrationKeepsOnlyCompatibleSingleRolesAndLeavesPinUntouched() {
        val f = Fixture()
        val entries = listOf("TAP", "SCROLL_DOWN", "SCROLL_UP", "ANCHOR", "NAVIGATION")
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            fun string(s: String) { val b = s.toByteArray(); out.writeInt(b.size); out.write(b) }
            out.writeInt(entries.size)
            entries.forEachIndexed { index, name ->
                val b = f.binding(20 + index)
                string(name); string(b.button.device.descriptor)
                out.writeInt(1); out.writeInt(2); out.writeInt(b.button.keyCode); out.writeInt(b.button.scanCode)
                string(b.deviceName); string(b.keyLabel)
            }
        }
        val settings = mutableMapOf("pin" to "same physical group", "unrelated" to "keep",
            "bindings" to "eyeprojector.hid-bindings.1:${Base64.getEncoder().encodeToString(buffer.toByteArray())}")
        var writes = 0
        val store = PhoneHidBindings({ settings["bindings"] }) { settings["bindings"] = it; writes++; true }
        assertEquals(1, writes); assertTrue(settings["bindings"]!!.startsWith("eyeprojector.hid-bindings.2:"))
        assertEquals("same physical group", settings["pin"]); assertEquals("keep", settings["unrelated"])
        assertEquals(f.binding(23), store.binding(PhoneHidAction.MODE))
        assertEquals(f.binding(24), store.binding(PhoneHidAction.BACK))
        assertNull(store.binding(PhoneHidAction.HOME)); assertNull(store.binding(PhoneHidAction.LOCK))
        for ((index, action) in listOf(PhoneHidAction.TAP, PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP).withIndex())
            assertEquals(f.binding(20 + index), store.binding(action))
        val reloaded = PhoneHidBindings({ settings["bindings"] }) { error("Current format must not rewrite") }
        assertEquals(store.binding(PhoneHidAction.MODE), reloaded.binding(PhoneHidAction.MODE))
    }
}
