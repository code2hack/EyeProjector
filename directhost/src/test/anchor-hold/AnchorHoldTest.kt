package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** These tests compose the production owner/merger/dispatcher; only the external MotionTarget is a fixture. */
class AnchorHoldTest {
    private val workers = mutableListOf<FunctionWork>()
    @After fun stopWorkers() { workers.forEach { it.close() } }
    private val geometry = Geometry(480, 640)
    private val source = ManagedUiSource(ProjectionContext("owner", "original"), "display-generation", 47,
        "managed", "app", null, UiSourceGeometry(geometry, geometry, 160, 0, false, geometry))
    private val window = UiWindowMetadata(47, 3, 1, 0, true, true, UiBounds(0, 0, 480, 640))
    private val node = UiTargetObservation(0, 3, "app", "app:id/action", "Button", UiBounds(300, 400, 340, 440),
        listOf(16), true, false, true, true)
    private val target get() = ResolvedAnchorTarget(source, window, node)
    private val setting = AnchorSettingContext("store", "app", 1)
    private fun intent(n: Long = 1) = FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.GLASSES_NATIVE,
        n, n, FunctionIntentContext(ReceiverUsability("owner", ReceiverIncarnation("glasses", 1), 1,
            1, ReceiverUsabilityMode.PROJECTED), HeadPointerState(1, n, true, true, 10f, 20f,
            Fit(0, 0, 480, 640), geometry)))

    private inner class Fixture {
        val lock = Any()
        val events = mutableListOf<Touch>()
        private val stateLog = mutableListOf<Pair<Long, AnchorHoldState>>()
        private val work = FunctionWork().also { workers.add(it) }
        val stateOutput = AnchorHoldOutput(work) { revision, state ->
            assertFalse("state delivery must not hold original control lock", Thread.holdsLock(lock))
            stateLog.add(revision to state)
        }
        val states: List<Pair<Long, AnchorHoldState>> get() {
            val done = CountDownLatch(1); work.submit { done.countDown() }
            check(done.await(3, TimeUnit.SECONDS)); return stateLog.toList()
        }
        var onTouch: (Touch) -> Boolean = { true }
        val input = AnchorSessionInput(lock) {}
        val merger = PhoneInputMerger(lock, { 0 }, { _, _ -> {} }, { geometry }, input::touch,
            input::scroll, { action, size -> input.navigate(Navigate(action, size)); Unit }, { input.cancel() }, {})
        init {
            input.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
                override fun geometry(next: Geometry) = Unit
                override fun touch(event: Touch): Boolean { events.add(event); return onTouch(event) }
                override fun tap(event: Tap) = true
                override fun scroll(event: Scroll) = true
            })
            input.geometry(geometry)
            input.anchor.reconcile(setting)
        }
        fun hold(n: Long = 1) = input.hold(input.anchor.capture(intent(n))!!, setting, target, source,
            { true }, merger::nativeAction, stateOutput)
        fun release(n: Long = 2, current: ManagedUiSource? = source) =
            input.anchor.releaseHeld(input.anchor.capture(intent(n))!!, input.anchor.heldSetting(), true, current) { true }
    }

    @Test fun secondActivationReleasesSameStationaryOriginalExactlyOnce() {
        val f = Fixture()
        assertEquals(AnchorExecutionStatus.HOLD_SUBMITTED, f.hold())
        assertTrue(f.input.holding)
        // Physical Function UP produced ACTIVATE already; only another ACTIVATE enters this owner.
        assertEquals(listOf(Touch.Phase.DOWN), f.events.map { it.phase })
        assertEquals(AnchorReleaseResult.RELEASE_SUBMITTED, f.release())
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP), f.events.map { it.phase })
        assertEquals(f.events[0].contactId, f.events[1].contactId)
        assertTrue(f.events.all { it.target == Tap(320, 420, geometry) })
        assertEquals(listOf(AnchorHoldState.HELD, AnchorHoldState.RELEASED), f.states.map { it.second })
        assertEquals(AnchorReleaseResult.NOT_HELD, f.release(3))
        assertFalse(f.input.holding)
        assertEquals(2, f.events.size)
    }

    @Test fun ordinaryInputAndSettingsRetireActualOriginalBeforeSuccessor() {
        for (cause in listOf("touch", "tap", "scroll", "back", "home", "lock", "geometry", "replace", "clear", "close")) {
            val f = Fixture()
            assertEquals(AnchorExecutionStatus.HOLD_SUBMITTED, f.hold())
            when (cause) {
                "touch" -> f.merger.nativeTouch(Touch(Touch.Phase.DOWN, 88, Tap(10, 20, geometry)))
                "tap" -> f.merger.nativeAction { f.input.tap(Tap(10, 20, geometry)) }
                "scroll" -> f.merger.nativeAction { f.input.scroll(Scroll(Tap(10, 20, geometry), 160)) }
                "back" -> f.input.navigate(Navigate(Navigation.BACK, geometry))
                "home", "lock" -> f.merger.invalidate()
                "geometry" -> f.input.geometry(Geometry(640, 480))
                "replace", "clear" -> assertTrue(f.input.anchor.reconcile(setting.copy(revision = 2)))
                "close" -> f.input.close()
            }
            assertEquals(cause, listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), f.events.take(2).map { it.phase })
            assertEquals(cause, f.events[0].contactId, f.events[1].contactId)
            assertFalse(cause, f.input.anchor.holding)
            assertEquals(cause, listOf(AnchorHoldState.HELD, AnchorHoldState.CANCELED), f.states.map { it.second })
            assertFalse(cause, f.events.any { it.phase == Touch.Phase.UP })
        }
    }

    @Test fun changedOriginalSourceCancelsAndCannotBecomeSuccessfulRelease() {
        for (current in listOf(null, source.copy(displayGeneration = "replacement"), source.copy(foregroundPackage = "other"))) {
            val f = Fixture(); f.hold()
            assertEquals(AnchorReleaseResult.RETIRED, f.release(current = current))
            assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), f.events.map { it.phase })
            assertEquals(AnchorHoldState.CANCELED, f.states.last().second)
        }
    }

    @Test fun retirementAfterCaptureNeverFallsThroughToFreshContact() {
        val f = Fixture(); f.hold()
        val pending = f.input.anchor.capture(intent(2))!!
        f.input.anchor.retire()
        assertEquals(AnchorReleaseResult.RETIRED, f.input.anchor.releaseHeld(pending, setting, true, source) { true })
        assertEquals(2, f.events.size)
        assertEquals(AnchorReleaseResult.NOT_HELD, f.release(3))
    }

    @Test fun rejectedOrReentrantReleaseCancelsWithoutSuccessAndPreservesSuccessor() {
        for (cause in listOf("reject", "throw", "retire")) {
            val f = Fixture(); f.hold()
            f.onTouch = { event ->
                if (event.phase == Touch.Phase.UP) when (cause) {
                    "reject" -> false
                    "throw" -> throw java.io.IOException("original output failed")
                    else -> { f.input.anchor.retire(); true }
                } else true
            }
            val result = runCatching { f.release() }
            if (cause == "throw") assertTrue(result.exceptionOrNull() is java.io.IOException)
            else assertNotEquals(AnchorReleaseResult.RELEASE_SUBMITTED, result.getOrThrow())
            assertFalse(f.input.holding)
            assertEquals(AnchorHoldState.CANCELED, f.states.last().second)
            assertEquals(1, f.events.count { it.phase == Touch.Phase.CANCEL })
        }
    }

    @Test fun reentrantDownRetirementHasNoHeldStateOrOrphanContact() {
        val f = Fixture()
        f.onTouch = { event -> if (event.phase == Touch.Phase.DOWN) f.input.geometry(Geometry(640, 480)); true }
        assertEquals(AnchorExecutionStatus.RETIRED, f.hold())
        assertFalse(f.input.holding)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), f.events.map { it.phase })
        assertFalse(f.states.any { it.second == AnchorHoldState.HELD })
    }

    @Test fun originalUpCannotCancelReentrantlyAdmittedSuccessorHold() {
        val f = Fixture(); f.hold()
        f.onTouch = { event ->
            if (event.phase == Touch.Phase.UP) {
                f.onTouch = { true }
                f.input.anchor.retire()
                assertEquals(AnchorExecutionStatus.HOLD_SUBMITTED, f.hold(3))
            }
            true
        }
        assertEquals(AnchorReleaseResult.RETIRED, f.release())
        assertTrue(f.input.anchor.holding)
        assertTrue(f.input.holding)
        val successor = f.events.last()
        assertEquals(Touch.Phase.DOWN, successor.phase)
        assertEquals(AnchorHoldState.HELD, f.states.last().second)
        assertEquals(AnchorReleaseResult.RELEASE_SUBMITTED, f.release(4))
        assertEquals(successor.contactId, f.events.last().contactId)
    }

    @Test fun duplicateReleaseAdmissionCannotTouchSuccessorContact() {
        val f = Fixture(); f.hold()
        val ticket = f.input.anchor.capture(intent(2))!!
        val metadata = f.input.anchor.heldSetting()
        assertEquals(AnchorReleaseResult.RELEASE_SUBMITTED,
            f.input.anchor.releaseHeld(ticket, metadata, true, source) { true })
        assertEquals(AnchorReleaseResult.RETIRED,
            f.input.anchor.releaseHeld(ticket, metadata, true, source) { true })
        assertEquals(AnchorExecutionStatus.HOLD_SUBMITTED, f.hold(3))
        val count = f.events.size
        assertEquals(AnchorReleaseResult.RETIRED,
            f.input.anchor.releaseHeld(ticket, metadata, true, source) { true })
        assertEquals(count, f.events.size)
        assertTrue(f.input.anchor.holding)
    }

    @Test fun rejectedOrThrownDownNeverPublishesHeldOrLeavesAnOutputContact() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            f.onTouch = { event -> if (event.phase == Touch.Phase.DOWN) {
                if (throwing) throw java.io.IOException("DOWN failed") else false
            } else true }
            val result = runCatching { f.hold() }
            if (throwing) assertTrue(result.exceptionOrNull() is java.io.IOException)
            else assertEquals(AnchorExecutionStatus.INPUT_REJECTED, result.getOrThrow())
            assertFalse(f.input.holding); assertFalse(f.input.anchor.holding)
            assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), f.events.map { it.phase })
            assertEquals(listOf(AnchorHoldState.CANCELED), f.states.map { it.second })
        }
    }

    private inner class AppFixture {
        val input = Fixture()
        var reads = 0
        var traversals = 0
        var rejectLookups = false
        val store = AppAnchorStore(object : AppAnchorStorage {
            var saved: AppAnchor? = null
            override fun read(packageName: String): AppAnchor? {
                check(!rejectLookups) { "release must not read storage" }; reads++; return saved
            }
            override fun write(packageName: String, anchor: AppAnchor?) { saved = anchor }
        })
        val queue = ArrayDeque<() -> Unit>()
        val app = AnchorAppCoordinator(store, {
            check(!rejectLookups) { "release must not obtain UI reader" }
            UiTargetReader(object : UiAccess {
                override val canRead = true
                override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
                    traversals++
                    return mapOf(47 to listOf(object : UiAccessWindow {
                        override val metadata = window
                        override fun root() = object : UiAccessNode {
                            override val packageName = "app"; override val windowId = 3; override val childCount = 0
                            override fun child(index: Int): UiAccessNode? = null
                            override fun observation(ordinal: Int) = node
                            override fun close() = Unit
                        }
                        override fun close() = Unit
                    }))
                }
            }, { 0 })
        }, { 0 }, { queue.add(it) })
        val feedback = mutableListOf<AnchorExecutionStatus>()
        init { store.replace(AppAnchor(AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.HOLD)) }
        fun press(n: Long) {
            val request = intent(n)
            val admission = input.input.anchor.capture(request)!!
            app.accept(object : AnchorOriginalRoute {
                override val intent = request
                override fun source(): ManagedUiSource {
                    check(!rejectLookups) { "coordinator must release before querying a new source" }; return this@AnchorHoldTest.source
                }
                override fun reconcile(setting: AnchorSettingContext) = input.input.anchor.reconcile(setting)
                override fun heldSetting() = input.input.anchor.heldSetting()
                override fun releaseHeld(expected: AnchorSettingContext?, settingCurrent: Boolean) =
                    input.input.anchor.releaseHeld(admission, expected, settingCurrent, this@AnchorHoldTest.source) { true }
                override fun click(setting: AnchorSettingContext, target: ResolvedAnchorTarget) =
                    input.input.click(admission, setting, target, this@AnchorHoldTest.source, { true }, input.merger::nativeAction)
                override fun hold(setting: AnchorSettingContext, target: ResolvedAnchorTarget) =
                    input.input.hold(admission, setting, target, this@AnchorHoldTest.source, { true }, input.merger::nativeAction,
                        input.stateOutput)
                override fun retire() = input.input.anchor.retire()
                override fun feedback(status: AnchorExecutionStatus) { feedback.add(status) }
            })
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    @Test fun originalReleasePrecedesEveryNewStorageReaderAndSourceLookup() {
        val f = AppFixture()
        f.press(1); f.drain()
        assertEquals(listOf(AnchorExecutionStatus.HOLD_SUBMITTED), f.feedback)
        val reads = f.reads; val traversals = f.traversals
        f.rejectLookups = true
        f.press(2); f.drain()
        assertNull(f.app.lastFailure)
        assertEquals(listOf(AnchorExecutionStatus.HOLD_SUBMITTED, AnchorExecutionStatus.RELEASE_SUBMITTED), f.feedback)
        assertEquals(reads, f.reads); assertEquals(traversals, f.traversals)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP), f.input.events.map { it.phase })
    }

    @Test fun everyShortPressRetainsFifoToggleAndHeadMovementCannotDrag() {
        val f = AppFixture()
        f.press(1); f.press(2); f.press(3); f.drain()
        assertEquals(listOf(AnchorExecutionStatus.HOLD_SUBMITTED, AnchorExecutionStatus.RELEASE_SUBMITTED,
            AnchorExecutionStatus.HOLD_SUBMITTED), f.feedback)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP, Touch.Phase.DOWN), f.input.events.map { it.phase })
        repeat(5) { n -> f.input.merger.pointer(intent().context.pointer.copy(sequence = 10L + n, x = 400f + n)) }
        assertEquals(3, f.input.events.size)
        assertTrue(f.input.input.anchor.holding)
    }

    @Test fun realStoreReplaceAndClearCancelOriginalThenNextPressUsesCurrentPreference() {
        for (clear in listOf(false, true)) {
            val f = AppFixture(); f.press(1); f.drain()
            if (clear) f.store.clear("app") else f.store.replace(AppAnchor(
                AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.HOLD))
            f.drain()
            assertFalse(f.input.input.anchor.holding)
            assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), f.input.events.map { it.phase })
            f.press(2); f.drain()
            assertEquals(if (clear) AnchorExecutionStatus.NO_ANCHOR else AnchorExecutionStatus.HOLD_SUBMITTED, f.feedback.last())
            assertFalse(f.input.events.any { it.phase == Touch.Phase.UP })
        }
    }

    @Test fun committedClearOrReplacementWithBlockedObserverCancelsWithoutLookingUpValues() {
        for (clear in listOf(false, true)) {
            val f = AppFixture()
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            var block = false
            // Registered before the coordinator subscribes, so the committed event cannot reach it.
            val observer = f.store.observe("app") {
                if (block) { entered.countDown(); check(resume.await(3, TimeUnit.SECONDS)) }
            }
            f.press(1); f.drain()
            val originalSetting = f.input.input.anchor.heldSetting()!!
            block = true
            val mutator = Thread {
                if (clear) f.store.clear("app") else f.store.replace(AppAnchor(
                    AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.HOLD))
            }.apply { start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertFalse(f.store.isCurrent(originalSetting))
                assertTrue(f.input.input.anchor.holding) // Event delivery has not retired it.
                val reads = f.reads; val traversals = f.traversals
                f.rejectLookups = true
                f.press(2); f.drain()
                assertNull(f.app.lastFailure)
                assertEquals(AnchorExecutionStatus.RETIRED, f.feedback.last())
                assertFalse(f.input.input.anchor.holding) // Exact owner cancellation ACK already returned.
                assertEquals(reads, f.reads); assertEquals(traversals, f.traversals)
                assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), f.input.events.map { it.phase })
                assertEquals(AnchorHoldState.CANCELED, f.input.states.last().second)
                f.rejectLookups = false
            } finally {
                f.rejectLookups = false; resume.countDown(); mutator.join(2000); observer.close()
            }
            assertFalse(mutator.isAlive); f.drain()
            f.press(3); f.drain()
            assertEquals(if (clear) AnchorExecutionStatus.NO_ANCHOR else AnchorExecutionStatus.HOLD_SUBMITTED, f.feedback.last())
            assertFalse(f.input.events.any { it.phase == Touch.Phase.UP })
        }
    }
}
