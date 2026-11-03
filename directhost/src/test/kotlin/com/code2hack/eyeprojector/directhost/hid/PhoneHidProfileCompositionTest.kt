package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.directhost.input.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Real profile/controller/route/merger join. Identities, time, persistence and outputs are host fixtures. */
class PhoneHidProfileCompositionTest {
    private class Fixture {
        var now = 1000L
        var record: String? = null
        // Synthetic stable keys only; Android checks separately use the production native identity mapper.
        fun profile(device: String) = "recorded-fixture:$device"
        fun binding(device: String, gesture: PhoneHidGesture = PhoneHidGesture.SINGLE) = PhoneHidBinding(
            PhoneHidButton(PhoneHidDeviceIdentity(profile(device), 12, 34), 30, 0), "Fixture $device", "Key 30", gesture)
        fun reopen() = PhoneHidProfiles({ record }, { null }, { record = it; true }, { it.descriptor })
        val profiles = reopen()
        data class Job(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        val jobs = mutableListOf<Job>()
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { at, action ->
            val job = Job(at, action); jobs.add(job)
            val cancel: () -> Unit = { job.canceled = true }; cancel
        }
        val saves = mutableListOf<Pair<() -> Unit, () -> Unit>>()
        fun flush() { while (saves.isNotEmpty()) { val (work, done) = saves.removeAt(0); work(); done() } }
        fun advance(to: Long) {
            while (true) {
                val next = jobs.filter { !it.canceled && it.at <= to }.minByOrNull { it.at } ?: break
                now = maxOf(now, next.at); next.canceled = true; next.action()
            }
            now = to
        }
        val geometry = Geometry(480, 640)
        val touches = mutableListOf<Touch>()
        val scrolls = mutableListOf<Scroll>()
        var contactCancels = 0
        var modes = 0
        var duringUp = false
        val modeDeliveredDuringUp = mutableListOf<Boolean>()
        val merger = PhoneInputMerger(Any(), { now }, schedule, { geometry },
            { touches.add(it); true }, { scrolls.add(it); true }, { _, _ -> error("Unexpected navigation") },
            { contactCancels++ }, { error("Unexpected receiver output") },
            hidMode = { modes++; modeDeliveredDuringUp.add(duringUp) })
        var routeId = 0L
        val canceledProfiles = mutableListOf<String?>()
        val controller: PhoneHidController = PhoneHidController(profiles.bindings(null), object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute {
                val id = ++routeId
                return PhoneHidRoute(down, 1) { merger.stampedEdge(id, it) { _, _ -> true } }
                    .also { it.submit(down) }
            }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) {
                canceledProfiles.add(controller.profileKey)
                route.cancel(eventTimeMs)
            }
        }, { work, done -> saves.add(work to done) }, { now }, schedule = schedule,
            profileBindings = profiles::bindings)
        init {
            merger.pointer(HeadPointerState(1, 1, true, true, 120f, 200f, Fit(0, 0, 480, 640), geometry))
            controller.connection(true)
        }
        fun assign(device: String, action: PhoneHidAction, gesture: PhoneHidGesture = PhoneHidGesture.SINGLE) {
            assertTrue(profiles.bindings(profile(device)).assign(action, binding(device, gesture)))
        }
        fun select(device: String?) = controller.selectProfile(device?.let(::profile))
        // Both synthetic devices deliberately reuse Android attachment/key/scan values.
        fun down(device: String) = PhoneHidController.Event(7, binding(device), PhoneHidPhase.DOWN, now, now)
            .also { assertTrue(controller.accept(it)) }
        fun up(down: PhoneHidController.Event, after: Long = 20) {
            advance(now + after)
            duringUp = true
            try { assertTrue(controller.accept(down.copy(phase = PhoneHidPhase.UP, eventTimeMs = now))) }
            finally { duringUp = false }
        }
        fun click(device: String) = up(down(device))
        fun oldTail(down: PhoneHidController.Event) {
            controller.accept(down.copy(repeatCount = 1, eventTimeMs = now))
            controller.accept(down.copy(phase = PhoneHidPhase.UP, eventTimeMs = now))
        }
    }

    @Test fun selectedProfilesDeliverTheirOwnSavedSingleAndNthMultiDuringUp() {
        val f = Fixture()
        f.assign("A", PhoneHidAction.MODE)
        f.assign("B", PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.select("A"); f.click("A")
        assertEquals(1, f.modes) // No final timer advancement after UP.
        f.select("B"); f.advance(f.now + 50); f.click("B")
        assertEquals(1, f.modes)
        f.advance(f.now + 50); f.click("B"); assertEquals(2, f.modes)
        repeat(2) { f.advance(f.now + 50); f.click("B") }
        assertEquals(3, f.modes)
        f.select(null); f.select("A"); f.advance(f.now + 50); f.click("A")
        assertEquals(4, f.modes)
        assertEquals(listOf(true, true, true, true), f.modeDeliveredDuringUp)
        assertEquals(PhoneHidGesture.SINGLE, f.reopen().bindings(f.profile("A")).binding(PhoneHidAction.MODE)?.gesture)
        assertEquals(PhoneHidGesture.clicks(2), f.reopen().bindings(f.profile("B")).binding(PhoneHidAction.MODE)?.gesture)
    }

    @Test fun switchingCancelsOriginalIncompleteGestureBeforeTheReplacementAndRejectsItsTimer() {
        val f = Fixture()
        for (device in listOf("A", "B")) f.assign(device, PhoneHidAction.MODE, PhoneHidGesture.clicks(2))
        f.select("A"); val a = f.down("A"); f.up(a)
        val stale = f.jobs.map { it.action }
        f.select("B")
        assertEquals(listOf(f.profile("A")), f.canceledProfiles)
        f.advance(f.now + 50); f.click("B")
        stale.forEach { it() }; f.oldTail(a)
        assertEquals(0, f.modes)
        f.advance(f.now + 50); f.click("B")
        assertEquals(1, f.modes)
        stale.forEach { it() }; f.advance(f.now + 1000)
        assertEquals(1, f.modes)
    }

    @Test fun heldTapScrollAndLongRetireOnSwitchWithoutOldTailAffectingFreshInput() {
        for (action in listOf(PhoneHidAction.TAP, PhoneHidAction.SCROLL_DOWN, PhoneHidAction.MODE)) {
            val f = Fixture()
            val gesture = if (action == PhoneHidAction.MODE) PhoneHidGesture.LONG else PhoneHidGesture.SINGLE
            f.assign("A", action, gesture); f.assign("B", action, gesture)
            f.select("A"); val a = f.down("A")
            val stale = f.jobs.map { it.action }
            f.select("B")
            assertEquals(listOf(f.profile("A")), f.canceledProfiles)
            if (action == PhoneHidAction.TAP) assertEquals(1, f.contactCancels)
            f.advance(f.now + 100); val b = f.down("B")
            val before = Triple(f.touches.size, f.scrolls.size, f.contactCancels)
            stale.forEach { it() }; f.oldTail(a)
            assertEquals(before, Triple(f.touches.size, f.scrolls.size, f.contactCancels))
            assertEquals(0, f.modes)
            f.up(b, if (action == PhoneHidAction.MODE) 800 else 20)
            when (action) {
                PhoneHidAction.TAP -> assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.DOWN, Touch.Phase.UP), f.touches.map { it.phase })
                PhoneHidAction.SCROLL_DOWN -> {
                    assertEquals(2, f.scrolls.size)
                    f.advance(f.now + 1000); stale.forEach { it() }; assertEquals(2, f.scrolls.size)
                }
                else -> { assertEquals(1, f.modes); assertEquals(listOf(true), f.modeDeliveredDuringUp) }
            }
        }
    }

    @Test fun queuedLearningAndResetUseOriginalProfileAndDoNotEmitThroughTheMerger() {
        val f = Fixture(); f.assign("B", PhoneHidAction.MODE, PhoneHidGesture.LONG)
        f.select("A"); f.controller.openPage(); f.controller.select(PhoneHidAction.MODE)
        f.click("A"); f.advance(f.now + 500)
        assertEquals(1, f.controller.saving)
        f.select("B"); f.flush()
        assertEquals(PhoneHidGesture.SINGLE, f.profiles.bindings(f.profile("A")).binding(PhoneHidAction.MODE)?.gesture)
        assertEquals(PhoneHidGesture.LONG, f.controller.bindings.binding(PhoneHidAction.MODE)?.gesture)
        f.select("A"); f.controller.resetAll(); f.select("B"); f.flush()
        assertNull(f.reopen().bindings(f.profile("A")).binding(PhoneHidAction.MODE))
        assertEquals(PhoneHidGesture.LONG, f.reopen().bindings(f.profile("B")).binding(PhoneHidAction.MODE)?.gesture)
        assertEquals(0L, f.routeId); assertEquals(0, f.modes)
        assertTrue(f.touches.isEmpty()); assertTrue(f.scrolls.isEmpty())
    }
}
