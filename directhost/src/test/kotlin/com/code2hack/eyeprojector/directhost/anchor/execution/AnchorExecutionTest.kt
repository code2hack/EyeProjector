package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AnchorExecutionTest {
    private val geometry = Geometry(480, 640)
    private val source = ManagedUiSource(ProjectionContext("owner", "original"), "display-generation", 47,
        "managed", "app", null, UiSourceGeometry(geometry, geometry, 160, 0, false, geometry))
    private val window = UiWindowMetadata(47, 3, 1, 0, true, true, UiBounds(0, 0, 480, 640))
    private val node = UiTargetObservation(0, 3, "app", "app:id/action", "Button", UiBounds(300, 400, 340, 440),
        listOf(16), true, false, true, true)
    private val target get() = ResolvedAnchorTarget(source, window, node)
    private val setting = AnchorSettingContext("store", "app", 1)
    private fun intent(sequence: Long = 0) = FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.GLASSES_NATIVE,
        sequence, sequence, FunctionIntentContext(ReceiverUsability("owner", ReceiverIncarnation("glasses", 1), 1,
            1, ReceiverUsabilityMode.PROJECTED), HeadPointerState(1, 1, true, true, 10f, 20f,
            Fit(0, 0, 480, 640), geometry)))

    @Test fun clickUsesCurrentBoundsAndOneOriginalContact() {
        val touches = mutableListOf<Touch>()
        val owner = AnchorContactOwner(Any(), { touches.add(it); true }, { fail("unexpected cancel") })
        owner.reconcile(setting)
        repeat(2) { assertEquals(AnchorExecutionStatus.CLICK_SUBMITTED, owner.click(owner.capture(intent(it.toLong()))!!,
            setting, target, source) { true }) }
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP, Touch.Phase.DOWN, Touch.Phase.UP), touches.map { it.phase })
        assertEquals(touches[0].contactId, touches[1].contactId)
        assertEquals(touches[2].contactId, touches[3].contactId)
        assertNotEquals(touches[0].contactId, touches[2].contactId)
        assertTrue(touches.all { it.target == Tap(320, 420, geometry) })
        assertFalse(owner.holding)
    }

    @Test fun retirementDuringDownCancelsOriginalAndNeverManufacturesUp() {
        val touches = mutableListOf<Touch>(); var canceled = 0
        lateinit var owner: AnchorContactOwner
        owner = AnchorContactOwner(Any(), { touches.add(it); owner.retire(); true }, { canceled++ })
        owner.reconcile(setting)
        assertEquals(AnchorExecutionStatus.RETIRED, owner.click(owner.capture(intent())!!, setting, target, source) { true })
        assertEquals(listOf(Touch.Phase.DOWN), touches.map { it.phase })
        assertEquals(1, canceled); assertFalse(owner.holding)
    }

    @Test fun changedInputSettingSourceAndClosedSessionRejectPendingAdmission() {
        for (change in listOf("input", "setting", "source", "closed")) {
            val touches = mutableListOf<Touch>()
            val owner = AnchorContactOwner(Any(), { touches.add(it); true }, {})
            owner.reconcile(setting)
            val pending = owner.capture(intent())!!
            when (change) {
                "input" -> owner.retire()
                "setting" -> { owner.reconcile(setting.copy(revision = 2)); assertFalse(owner.reconcile(setting)) }
                "closed" -> owner.close()
            }
            assertEquals(change, AnchorExecutionStatus.RETIRED, owner.click(pending, setting, target,
                if (change == "source") source.copy(displayGeneration = "replacement") else source) { true })
            assertTrue(touches.isEmpty())
        }
    }

    @Test fun rejectedUpCancelsWithoutReportingCompletedClick() {
        val touches = mutableListOf<Touch>(); var canceled = 0
        val owner = AnchorContactOwner(Any(), { touches.add(it); it.phase != Touch.Phase.UP }, { canceled++ })
        owner.reconcile(setting)
        assertEquals(AnchorExecutionStatus.INPUT_REJECTED, owner.click(owner.capture(intent())!!, setting, target, source) { true })
        assertEquals(1, canceled); assertFalse(owner.holding)
    }

    @Test fun typedBinderValuesRoundTripCurrentSourceAndSetting() {
        assertEquals(setting, AnchorExecutionCodec.setting(AnchorExecutionCodec.setting(setting)))
        val decoded = AnchorExecutionCodec.target(AnchorExecutionCodec.target(target))
        assertEquals(target.source, decoded.source); assertEquals(target.window, decoded.window)
        assertEquals(target.target.copy(actionIds = emptyList()), decoded.target)
    }

    private class Storage : AppAnchorStorage {
        var anchor: AppAnchor? = null
        override fun read(packageName: String) = anchor
        override fun write(packageName: String, anchor: AppAnchor?) { this.anchor = anchor }
    }
    private val anchor get() = AppAnchor(AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.CLICK)
    private inner class Access : UiAccess {
        var duringRead: () -> Unit = {}
        override val canRead = true
        override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
            duringRead()
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
    }
    private inner class Route(override val intent: FunctionIntent = intent()) : AnchorOriginalRoute {
        val owner = AnchorContactOwner(Any(), { true }, {})
        val admission = owner.capture(intent)!!
        val feedback = mutableListOf<AnchorExecutionStatus>(); val reconciled = mutableListOf<AnchorSettingContext>()
        var clicks = 0
        override fun source() = source
        override fun reconcile(setting: AnchorSettingContext): Boolean { reconciled.add(setting); return owner.reconcile(setting) }
        override fun click(setting: AnchorSettingContext, target: ResolvedAnchorTarget): AnchorExecutionStatus {
            clicks++; return owner.click(admission, setting, target, source) { true }
        }
        override fun retire() = owner.retire()
        override fun feedback(status: AnchorExecutionStatus) { feedback.add(status) }
    }

    @Test fun actualReaderStoreResolverAndOwnerComposeWithoutHeadAim() {
        val store = AppAnchorStore(Storage()); store.replace(anchor)
        val access = Access(); val queue = ArrayDeque<() -> Unit>()
        val app = AnchorAppCoordinator(store, { UiTargetReader(access) { 0 } }, { 0 }, { queue.add(it) })
        val route = Route(); app.accept(route); while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(AnchorExecutionStatus.CLICK_SUBMITTED), route.feedback); assertEquals(1, route.clicks)
    }

    @Test fun clearDuringTraversalSendsNoClickEvenBeforeObserverQueueRuns() {
        val store = AppAnchorStore(Storage()); store.replace(anchor)
        val access = Access().apply { duringRead = { store.clear("app") } }; val queue = ArrayDeque<() -> Unit>()
        val app = AnchorAppCoordinator(store, { UiTargetReader(access) { 0 } }, { 0 }, { queue.add(it) })
        val route = Route(); app.accept(route); while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(AnchorExecutionStatus.RETIRED), route.feedback); assertEquals(0, route.clicks)
        assertEquals(store.snapshot("app").context, route.reconciled.last())
    }

    @Test fun missingAndHoldSettingsDoNotCreateClicks() {
        val store = AppAnchorStore(Storage()); val queue = ArrayDeque<() -> Unit>(); val access = Access()
        val app = AnchorAppCoordinator(store, { UiTargetReader(access) { 0 } }, { 0 }, { queue.add(it) })
        val missing = Route(); app.accept(missing); while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(AnchorExecutionStatus.NO_ANCHOR), missing.feedback); assertEquals(0, missing.clicks)
        store.replace(anchor.copy(mode = AnchorMode.HOLD)); while (queue.isNotEmpty()) queue.removeFirst()()
        val hold = Route(intent(2)); app.accept(hold); while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(AnchorExecutionStatus.HOLD_MODE), hold.feedback); assertEquals(0, hold.clicks)
    }
    @Test fun windowChangeOrAccessLossDuringReadRetiresPendingUse() {
        for (display in listOf<Int?>(47, null)) {
            val store = AppAnchorStore(Storage()); store.replace(anchor)
            val queue = ArrayDeque<() -> Unit>(); val access = Access()
            val app = AnchorAppCoordinator(store, { UiTargetReader(access) { 0 } }, { 0 }, { queue.add(it) })
            access.duringRead = { app.contextRetired(display) }
            val route = Route(); app.accept(route); while (queue.isNotEmpty()) queue.removeFirst()()
            assertEquals(listOf(AnchorExecutionStatus.RETIRED), route.feedback); assertEquals(0, route.clicks)
        }
    }

    @Test fun consumedAdmissionCannotClickAgain() {
        val touches = mutableListOf<Touch>()
        val owner = AnchorContactOwner(Any(), { touches.add(it); true }, {})
        owner.reconcile(setting); val ticket = owner.capture(intent())!!
        assertEquals(AnchorExecutionStatus.CLICK_SUBMITTED, owner.click(ticket, setting, target, source) { true })
        assertEquals(AnchorExecutionStatus.RETIRED, owner.click(ticket, setting, target, source) { true })
        assertEquals(2, touches.size)
    }

    @Test fun delayedPreferenceDeliveryReconcilesLatestBeforeNewUse() {
        val store = AppAnchorStore(Storage()); store.replace(anchor)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        var block = true
        val subscription = store.observe("app") {
            if (block) { entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        val queue = ArrayDeque<() -> Unit>(); val access = Access()
        val app = AnchorAppCoordinator(store, { UiTargetReader(access) { 0 } }, { 0 }, { synchronized(queue) { queue.add(it) }; Unit })
        fun drain() { while (true) { val next = synchronized(queue) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return; next() } }
        val first = Route(); app.accept(first); drain()
        val mutator = Thread { store.replace(anchor) }.apply { start() }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            store.replace(anchor) // Commits and returns while the previous event is still blocked.
            val latest = store.snapshot("app").context
            val next = Route(intent(2)); app.accept(next); drain()
            assertEquals(latest, next.reconciled.last())
            assertEquals(listOf(AnchorExecutionStatus.CLICK_SUBMITTED), next.feedback)
            block = false; release.countDown(); mutator.join(2000); assertFalse(mutator.isAlive); drain()
            assertEquals(latest, first.reconciled.last()) // Older immutable event never rolls owner back.
        } finally { release.countDown(); mutator.join(2000); subscription.close() }
    }

    @Test fun actualMergerAndDispatcherHandOffOneOriginalOutputRoute() {
        val lock = Any(); val events = mutableListOf<Touch>()
        val input = AnchorSessionInput(lock) { error("managed input must use original MotionTarget") }
        input.bindMotionTarget(object : com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher.MotionTarget {
            override fun geometry(next: Geometry) = Unit
            override fun touch(event: Touch): Boolean { events.add(event); return true }
            override fun tap(event: Tap) = true
            override fun scroll(event: Scroll) = true
        })
        input.geometry(geometry)
        val merger = com.code2hack.eyeprojector.directhost.input.PhoneInputMerger(lock, { 0 }, { _, _ -> {} },
            { geometry }, input::touch, input::scroll, { _, _ -> }, { input.cancel() }, {})
        val oldDown = Touch(Touch.Phase.DOWN, 99, Tap(10, 20, geometry))
        assertTrue(merger.nativeTouch(oldDown))
        val ticket = input.anchor.capture(intent())!!; input.anchor.reconcile(setting)
        assertEquals(AnchorExecutionStatus.CLICK_SUBMITTED, input.click(ticket, setting, target, source,
            { true }, merger::nativeAction))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), events.map { it.phase })
        assertEquals(99, events[1].contactId)
        assertEquals(events[2].contactId, events[3].contactId)
        assertEquals(Tap(320, 420, geometry), events[2].target)
        assertFalse(merger.nativeTouch(oldDown.copy(phase = Touch.Phase.UP)))
        assertEquals(4, events.size); assertFalse(input.holding)
    }

    @Test fun ordinaryInputAndGeometryRetirePendingAnchorBeforeHandoff() {
        val lock = Any(); val events = mutableListOf<Touch>()
        val input = AnchorSessionInput(lock) {}
        input.bindMotionTarget(object : com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher.MotionTarget {
            override fun geometry(next: Geometry) = Unit
            override fun touch(event: Touch): Boolean { events.add(event); return true }
            override fun tap(event: Tap) = true
            override fun scroll(event: Scroll) = true
        })
        input.geometry(geometry); input.anchor.reconcile(setting)
        val ticket = input.anchor.capture(intent())!!
        input.scroll(Scroll(Tap(10, 20, geometry), 160))
        var handoffs = 0
        assertEquals(AnchorExecutionStatus.RETIRED, input.click(ticket, setting, target, source, { true },
            { action -> handoffs++; action() }))
        assertEquals(0, handoffs); assertTrue(events.isEmpty())
        val second = input.anchor.capture(intent(2))!!
        input.geometry(Geometry(640, 480))
        assertEquals(AnchorExecutionStatus.RETIRED, input.click(second, setting, target, source, { true },
            { action -> handoffs++; action() }))
        assertEquals(0, handoffs)
    }

    @Test fun geometryRetirementDuringActualDispatcherDownCannotLeaveAnOrphan() {
        val lock = Any(); val events = mutableListOf<Touch>()
        lateinit var input: AnchorSessionInput
        input = AnchorSessionInput(lock) {}
        input.bindMotionTarget(object : com.code2hack.eyeprojector.directhost.PhoneTouchDispatcher.MotionTarget {
            override fun geometry(next: Geometry) = Unit
            override fun touch(event: Touch): Boolean {
                events.add(event)
                if (event.phase == Touch.Phase.DOWN) input.geometry(Geometry(640, 480))
                return true
            }
            override fun tap(event: Tap) = true
            override fun scroll(event: Scroll) = true
        })
        input.geometry(geometry); input.anchor.reconcile(setting)
        val ticket = input.anchor.capture(intent())!!
        assertEquals(AnchorExecutionStatus.RETIRED, input.click(ticket, setting, target, source,
            { true }, { action -> action() }))
        assertFalse("Retired original DOWN must leave no dispatcher contact", input.holding)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), events.map { it.phase })
    }

    @Test fun registrationHandlerFailureRetainsCauseAndAllowsFollowingFifoWork() {
        val store = AppAnchorStore(Storage()); store.replace(anchor)
        val queue = ArrayDeque<() -> Unit>(); val access = Access()
        val app = AnchorAppCoordinator(store, { UiTargetReader(access) { 0 } }, { 0 }, { queue.add(it) })
        val failure = java.io.IOException("original registration Binder retired")
        app.registration = {}
        app.registrationMessage = { _, _ -> throw failure }
        val register = Route(intent().copy(kind = FunctionIntentKind.REGISTER))
        app.accept(register)
        while (queue.isNotEmpty()) queue.removeFirst()()
        app.acceptRegistration(AnchorMessage.Choice(0, register.intent, AnchorChoice.CANCEL), register)
        val next = Route(intent(2)); app.accept(next)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertSame(failure, app.lastFailure)
        assertEquals(listOf(AnchorExecutionStatus.UNAVAILABLE), register.feedback)
        assertEquals(listOf(AnchorExecutionStatus.CLICK_SUBMITTED), next.feedback)
    }

}
