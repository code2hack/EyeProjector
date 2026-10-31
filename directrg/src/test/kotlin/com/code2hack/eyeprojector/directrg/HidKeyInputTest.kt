package com.code2hack.eyeprojector.directrg

import java.util.PriorityQueue
import org.junit.Assert.*
import org.junit.Test
import com.code2hack.eyeprojector.protocol.FunctionIntentKind
import com.code2hack.eyeprojector.directrg.HidKeyInput.Phase

class HidKeyInputTest {
    private class Rig {
        data class Timer(val at: Long, val order: Int, val run: () -> Unit, var canceled: Boolean = false)
        data class Contact(val owner: Any, val event: PadGestureRecognizer.Event)
        data class Cancellation(val owner: Any, val time: Long)
        data class Scroll(val amount: Int, val position: PointerPosition, val time: Long)
        var now = 1000L
        var context: Any? = "projection-1"
        var point = PointerPosition(12f, 34f, true)
        var positionReads = 0
        var onPosition: () -> Unit = {}
        var changes = 0
        var target: HidAction? = null
        var captureReads = 0
        var onCaptureTarget: () -> Unit = {}
        var onCancelContact: () -> Unit = {}
        var record: String? = null
        val bindings = HidBindings({ record }) { record = it; true }
        val buttons = HidAction.entries.associateWith {
            HidBinding(HidButton(HidDeviceIdentity("descriptor-1", 17, 29), 100 + it.ordinal, 200 + it.ordinal),
                "Actual peripheral", "Button ${it.name}")
        }
        val contacts = mutableListOf<Contact>()
        val cancellations = mutableListOf<Cancellation>()
        val scrolls = mutableListOf<Scroll>()
        val actions = mutableListOf<FunctionIntentKind>()
        val timers = mutableListOf<Timer>()
        private val queue = PriorityQueue<Timer>(compareBy<Timer> { it.at }.thenBy { it.order })
        val input = HidKeyInput(bindings, { now }, { context }, { at, run ->
            val timer = Timer(at, timers.size, run)
            timers.add(timer)
            queue.add(timer)
            val cancel: () -> Unit = { timer.canceled = true }
            cancel
        }, { positionReads++; onPosition(); point }, { owner, event -> contacts.add(Contact(owner, event)) },
            { owner, at -> cancellations.add(Cancellation(owner, at)); onCancelContact() },
            { amount, position, at -> scrolls.add(Scroll(amount, position, at)) }, { actions.add(it.kind) }, { changes++ },
            { captureReads++; val found = target; onCaptureTarget(); found })

        init { buttons.forEach { (action, binding) -> assertTrue(bindings.assign(action, binding)) } }

        fun edge(action: HidAction, phase: Phase, at: Long = now, down: Long = at,
                 repeat: Int = 0, attachment: Int = 7): Boolean =
            edge(buttons.getValue(action), phase, at, down, repeat, attachment)

        fun edge(binding: HidBinding, phase: Phase, at: Long = now, down: Long = at,
                 repeat: Int = 0, attachment: Int = 7): Boolean {
            now = at
            return input.accept(HidKeyInput.Event(attachment, binding, phase, down, at, repeat))
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (queue.peek()?.at?.let { it <= end } == true) {
                val timer = queue.remove()
                if (!timer.canceled) {
                    timer.canceled = true
                    now = maxOf(now, timer.at)
                    timer.run()
                }
            }
            now = end
        }

        fun clickFunction(attachment: Int = 7) {
            assertTrue(edge(HidAction.FUNCTION, Phase.DOWN, attachment = attachment))
            val down = now
            advance(20)
            assertTrue(edge(HidAction.FUNCTION, Phase.UP, down = down, attachment = attachment))
        }

        fun assertNoOutput() {
            assertTrue(contacts.isEmpty())
            assertTrue(cancellations.isEmpty())
            assertTrue(scrolls.isEmpty())
            assertTrue(actions.isEmpty())
        }

        fun unbound() = buttons.getValue(HidAction.TAP).copy(
            button = buttons.getValue(HidAction.TAP).button.copy(keyCode = 4, scanCode = 158),
            keyLabel = "Actual Back key",
        )
    }

    @Test fun captureOwnsItsEdgesAndMovesTheBindingOnlyAfterACompleteRelease() {
        val r = Rig()
        val captured = r.buttons.getValue(HidAction.FUNCTION)
        val oldTap = r.bindings.binding(HidAction.TAP)
        r.target = HidAction.TAP
        assertNull(r.input.selected)
        assertTrue(r.edge(captured, Phase.DOWN))
        assertEquals(HidAction.TAP, r.input.selected)
        assertTrue(r.edge(captured, Phase.DOWN, at = 1010, down = 1000, repeat = 1))
        r.assertNoOutput()
        assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
        assertEquals(captured, r.bindings.binding(HidAction.FUNCTION))
        assertTrue(r.edge(captured.copy(deviceName = "Release metadata"), Phase.UP, at = 1030, down = 1000))
        assertEquals(captured, r.bindings.binding(HidAction.TAP))
        assertNull(r.bindings.binding(HidAction.FUNCTION))
        assertNull(r.input.selected)
        assertTrue(r.timers.isEmpty())
        r.advance(5000)
        r.assertNoOutput()
    }

    @Test fun keysOutsideTheOwnedCaptureKeepTheirOrdinaryDispatch() {
        val r = Rig()
        val captured = r.unbound()
        r.target = HidAction.FUNCTION
        assertTrue(r.edge(captured, Phase.DOWN))
        r.target = null
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 1010))
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1020, down = 1010))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP), r.contacts.map { it.event.phase })
        assertEquals(HidAction.FUNCTION, r.input.selected)
        assertTrue(r.edge(captured, Phase.UP, at = 1030, down = 1000))
        assertEquals(captured, r.bindings.binding(HidAction.FUNCTION))
        assertTrue(r.actions.isEmpty())
    }

    @Test fun canceledCaptureStillConsumesItsRepeatAndReleaseEvenForAnUnboundBackKey() {
        val r = Rig()
        val captured = r.unbound()
        val oldTap = r.bindings.binding(HidAction.TAP)
        r.target = HidAction.TAP
        assertTrue(r.edge(captured, Phase.DOWN))
        r.input.cancel()
        assertNull(r.input.selected)
        assertTrue(r.edge(captured, Phase.DOWN, at = 1010, down = 1000, repeat = 1))
        assertTrue(r.edge(captured, Phase.UP, at = 1020, down = 1000))
        assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
        assertFalse(r.edge(captured, Phase.UP, at = 1030, down = 1000))
        r.assertNoOutput()
    }

    @Test fun movingToAnotherTargetCannotRetargetTheAlreadyOwnedCaptureRelease() {
        val r = Rig()
        val captured = r.unbound()
        r.target = HidAction.TAP
        r.edge(captured, Phase.DOWN)
        r.target = HidAction.SCROLL_UP
        assertEquals(HidAction.TAP, r.input.selected)
        assertTrue(r.edge(captured, Phase.UP, at = 1020, down = 1000))
        assertEquals(captured, r.bindings.binding(HidAction.TAP))
        assertNull(r.input.selected)
        assertEquals(1, r.captureReads)
        assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertTrue(r.edge(captured, Phase.UP, at = 1120, down = 1100))
        assertEquals(captured, r.bindings.binding(HidAction.SCROLL_UP))
        assertNull(r.bindings.binding(HidAction.TAP))
        assertNull(r.input.selected)
        assertEquals(2, r.captureReads)
        r.assertNoOutput()
    }

    @Test fun canceledOrBackwardCaptureReleaseRetiresOwnedCaptureAndNotifiesTheView() {
        for (phase in listOf(Phase.CANCEL, Phase.UP)) {
            val r = Rig()
            val captured = r.unbound()
            val old = r.bindings.binding(HidAction.TAP)
            r.target = HidAction.TAP
            r.edge(captured, Phase.DOWN)
            val before = r.changes
            assertTrue(r.edge(captured, phase, at = if (phase == Phase.UP) 999 else 1020, down = 1000))
            assertEquals(old, r.bindings.binding(HidAction.TAP))
            assertNull(r.input.selected)
            assertEquals(before + 1, r.changes)
            r.assertNoOutput()
        }
    }

    @Test fun canceledOrBackwardCaptureConsumesItsLateReleaseThenAcceptsAFreshCompletePress() {
        for (terminal in listOf(Phase.CANCEL, Phase.UP)) {
            val r = Rig()
            val captured = r.unbound()
            val old = r.bindings.binding(HidAction.TAP)
            r.target = HidAction.TAP
            assertTrue(r.edge(captured, Phase.DOWN))
            assertTrue(r.edge(captured, terminal, at = if (terminal == Phase.UP) 999 else 1020, down = 1000))
            assertTrue(r.edge(captured, Phase.UP, at = 1050, down = 1000))
            assertEquals(old, r.bindings.binding(HidAction.TAP))
            assertNull(r.input.selected)
            r.assertNoOutput()
            assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
            assertTrue(r.edge(captured, Phase.UP, at = 1120, down = 1100))
            assertEquals(captured, r.bindings.binding(HidAction.TAP))
            assertNull(r.input.selected)
            r.assertNoOutput()
        }
    }

    @Test fun captureRejectsMalformedPressesAndNeverInventsAReleaseForAnIncompletePress() {
        val r = Rig()
        val captured = r.unbound()
        val old = r.bindings.binding(HidAction.TAP)
        r.target = HidAction.TAP
        assertTrue(r.edge(captured, Phase.DOWN, at = 1000, down = 1100))
        assertTrue(r.edge(captured, Phase.UP, at = 1120, down = 1100))
        assertEquals(old, r.bindings.binding(HidAction.TAP))
        assertNull(r.input.selected)
        assertTrue(r.edge(captured, Phase.DOWN, at = 1200))
        assertFalse(r.edge(captured, Phase.UP, at = 1210, down = 1199))
        r.advance(5000)
        assertEquals(old, r.bindings.binding(HidAction.TAP))
        assertEquals(HidAction.TAP, r.input.selected)
        assertTrue(r.timers.isEmpty())
        assertTrue(r.edge(captured, Phase.UP, at = 6220, down = 1200))
        assertEquals(captured, r.bindings.binding(HidAction.TAP))
        r.assertNoOutput()
    }

    @Test fun freshFirstDownCanRecoverAnIncompleteCaptureOfTheSamePhysicalKey() {
        val r = Rig()
        val captured = r.unbound()
        r.target = HidAction.TAP
        assertTrue(r.edge(captured, Phase.DOWN))
        assertTrue(r.edge(captured, Phase.DOWN, at = 1200))
        assertTrue(r.edge(captured, Phase.UP, at = 1220, down = 1200))
        assertNull(r.input.selected)
        assertEquals(captured, r.bindings.binding(HidAction.TAP))
        // An eventual release for the superseded physical press cannot produce a contact.
        r.edge(captured, Phase.UP, at = 1230, down = 1000)
        r.assertNoOutput()
    }

    @Test fun targetProviderIsOnlyReadForAFreshDownAndMatchingCaptureEdgesKeepTheDownTarget() {
        val r = Rig()
        val captured = r.unbound()
        r.target = HidAction.FUNCTION
        assertNull(r.input.selected)
        assertFalse(r.edge(captured, Phase.UP, down = 900))
        assertFalse(r.edge(captured, Phase.DOWN, down = 900, repeat = 1))
        assertEquals(0, r.captureReads)
        assertTrue(r.edge(captured, Phase.DOWN))
        assertEquals(1, r.captureReads)
        assertEquals(HidAction.FUNCTION, r.input.selected)
        r.target = HidAction.SCROLL_DOWN
        assertTrue(r.edge(captured, Phase.DOWN, at = 1010, down = 1000, repeat = 1))
        assertTrue(r.edge(captured, Phase.DOWN, at = 1020, down = 1000))
        assertTrue(r.edge(captured, Phase.UP, at = 1030, down = 1000))
        assertEquals(1, r.captureReads)
        assertEquals(captured, r.bindings.binding(HidAction.FUNCTION))
        assertNull(r.input.selected)
        r.assertNoOutput()
    }

    @Test fun freshTargetedDownOnAnotherKeyReplacesCaptureAndRetainsTheExactOldRelease() {
        val r = Rig()
        val first = r.unbound()
        val second = first.copy(button = first.button.copy(keyCode = 24, scanCode = 42))
        val oldTap = r.bindings.binding(HidAction.TAP)
        r.target = HidAction.TAP
        assertTrue(r.edge(first, Phase.DOWN))
        r.target = HidAction.SCROLL_UP
        assertTrue(r.edge(second, Phase.DOWN, at = 1100, attachment = 8))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertTrue(r.edge(first, Phase.UP, at = 1110, down = 1000))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
        assertTrue(r.edge(second, Phase.UP, at = 1120, down = 1100, attachment = 8))
        assertEquals(second, r.bindings.binding(HidAction.SCROLL_UP))
        assertNull(r.input.selected)
        r.assertNoOutput()
    }

    @Test fun repeatedIncompleteCapturesOfOneKeyRetainEveryExactSupersededRelease() {
        val r = Rig()
        val captured = r.unbound()
        r.target = HidAction.TAP
        assertTrue(r.edge(captured, Phase.DOWN))
        r.target = HidAction.SCROLL_DOWN
        assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
        r.target = HidAction.FUNCTION
        assertTrue(r.edge(captured, Phase.DOWN, at = 1200))
        assertTrue(r.edge(captured, Phase.UP, at = 1210, down = 1000))
        assertTrue(r.edge(captured, Phase.UP, at = 1220, down = 1100))
        assertEquals(HidAction.FUNCTION, r.input.selected)
        assertEquals(3, r.captureReads)
        assertTrue(r.edge(captured, Phase.UP, at = 1230, down = 1200))
        assertEquals(captured, r.bindings.binding(HidAction.FUNCTION))
        assertNull(r.input.selected)
        r.assertNoOutput()
    }

    @Test fun targetProviderCancellationCannotInstallTheOlderCaptureAfterCallbackReturn() {
        val r = Rig()
        val captured = r.unbound()
        val oldTap = r.bindings.binding(HidAction.TAP)
        r.target = HidAction.TAP
        r.onCaptureTarget = { r.input.cancel() }
        assertTrue(r.edge(captured, Phase.DOWN))
        assertNull(r.input.selected)
        r.onCaptureTarget = {}
        assertTrue(r.edge(captured, Phase.UP, at = 1020, down = 1000))
        assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
        r.assertNoOutput()
    }

    @Test fun targetProviderContextChangeCannotBindAnOldContextPress() {
        for (next in listOf("projection-2", null)) {
            val r = Rig()
            val captured = r.unbound()
            val oldTap = r.bindings.binding(HidAction.TAP)
            r.target = HidAction.TAP
            r.onCaptureTarget = { r.context = next }
            assertTrue(r.edge(captured, Phase.DOWN))
            assertNull(r.input.selected)
            r.onCaptureTarget = {}
            r.context = "projection-1"
            assertTrue(r.edge(captured, Phase.UP, at = 1020, down = 1000))
            assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
            r.assertNoOutput()
        }
    }

    @Test fun newerCaptureAdmittedInsideTargetProviderSupersedesTheOuterDown() {
        val r = Rig()
        val outer = r.unbound()
        val newer = outer.copy(button = outer.button.copy(keyCode = 24, scanCode = 42))
        val oldTap = r.bindings.binding(HidAction.TAP)
        r.target = HidAction.TAP
        r.onCaptureTarget = {
            r.onCaptureTarget = {}
            r.target = HidAction.SCROLL_UP
            assertTrue(r.edge(newer, Phase.DOWN, at = 1100, attachment = 8))
        }
        assertTrue(r.edge(outer, Phase.DOWN))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertTrue(r.edge(outer, Phase.UP, at = 1110, down = 1000))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertTrue(r.edge(newer, Phase.UP, at = 1120, down = 1100, attachment = 8))
        assertEquals(newer, r.bindings.binding(HidAction.SCROLL_UP))
        assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
        assertNull(r.input.selected)
        r.assertNoOutput()
    }

    @Test fun newerCaptureCompletedInsideTargetProviderCannotBeOverwrittenByTheOuterDown() {
        for (terminal in listOf(Phase.UP, Phase.CANCEL)) {
            val r = Rig()
            val outer = r.unbound()
            val newer = outer.copy(button = outer.button.copy(keyCode = 24, scanCode = 42))
            val oldTap = r.bindings.binding(HidAction.TAP)
            val oldScroll = r.bindings.binding(HidAction.SCROLL_UP)
            r.target = HidAction.TAP
            r.onCaptureTarget = {
                r.onCaptureTarget = {}
                r.target = HidAction.SCROLL_UP
                assertTrue(r.edge(newer, Phase.DOWN, at = 1100, attachment = 8))
                assertTrue(r.edge(newer, terminal, at = 1120, down = 1100, attachment = 8))
            }
            assertTrue(r.edge(outer, Phase.DOWN))
            assertNull(r.input.selected)
            assertTrue(r.edge(outer, Phase.UP, at = 1130, down = 1000))
            assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
            assertEquals(if (terminal == Phase.UP) newer else oldScroll,
                r.bindings.binding(HidAction.SCROLL_UP))
            r.assertNoOutput()
        }
    }

    @Test fun ordinaryTapAndIndependentFunctionAdmittedInsideItsTargetProviderBothKeepTheirOwners() {
        val r = Rig()
        r.onCaptureTarget = {
            r.onCaptureTarget = {}
            assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, at = 1010))
        }
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN), r.contacts.map { it.event.phase })
        assertEquals(1000L, r.contacts.single().event.downTimeMs)
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1030, down = 1010))
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1040, down = 1000))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
            r.contacts.map { it.event.phase })
        assertSame(r.contacts[0].owner, r.contacts[1].owner)
        assertTrue(r.cancellations.isEmpty())
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
        assertNull(r.input.selected)
    }

    @Test fun unboundFreshDownInsideANullTargetProviderCannotStealTheOrdinaryOuterTapAdmission() {
        val r = Rig()
        r.onCaptureTarget = {
            r.onCaptureTarget = {}
            assertFalse(r.edge(r.unbound(), Phase.DOWN, at = 1010))
        }
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1030, down = 1000))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
            r.contacts.map { it.event.phase })
        assertSame(r.contacts[0].owner, r.contacts[1].owner)
        assertTrue(r.cancellations.isEmpty())
        assertTrue(r.scrolls.isEmpty())
        assertTrue(r.actions.isEmpty())
        assertTrue(r.timers.isEmpty())
        assertNull(r.input.selected)
    }

    @Test fun newerOrdinaryTapInsideANullTargetProviderStillSupersedesTheOlderSharedContactAdmission() {
        val r = Rig()
        r.onCaptureTarget = {
            r.onCaptureTarget = {}
            assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 1010, attachment = 8))
        }
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        assertEquals(1, r.contacts.size)
        assertEquals(1010L, r.contacts.single().event.downTimeMs)
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1020, down = 1000))
        assertEquals(1, r.contacts.size)
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1030, down = 1010, attachment = 8))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
            r.contacts.map { it.event.phase })
        assertSame(r.contacts[0].owner, r.contacts[1].owner)
        assertTrue(r.contacts.all { it.event.downTimeMs == 1010L })
        assertTrue(r.cancellations.isEmpty())
        assertTrue(r.actions.isEmpty())
        assertTrue(r.timers.isEmpty())
    }

    @Test fun capturedReleaseInANewContextIsConsumedWithoutAssigningAndDoesNotReadATargetAgain() {
        for (next in listOf("projection-2", null)) {
            val r = Rig()
            val captured = r.unbound()
            val oldTap = r.bindings.binding(HidAction.TAP)
            r.target = HidAction.TAP
            assertTrue(r.edge(captured, Phase.DOWN))
            r.context = next
            assertTrue(r.edge(captured, Phase.UP, at = 1020, down = 1000))
            assertNull(r.input.selected)
            assertEquals(oldTap, r.bindings.binding(HidAction.TAP))
            assertEquals(1, r.captureReads)
            r.assertNoOutput()
        }
    }

    @Test fun freshCaptureRetiresHeldAndPendingFunctionWorkWithoutProducingConstituentActions() {
        val r = Rig()
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN))
        val stale = r.timers.toList()
        val captured = r.unbound()
        r.target = HidAction.SCROLL_UP
        assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertEquals(1, r.contacts.size)
        assertEquals(1, r.cancellations.size)
        assertSame(r.contacts.single().owner, r.cancellations.single().owner)
        assertEquals(1100L, r.cancellations.single().time)
        stale.forEach { it.run() }
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1110, down = 1000))
        assertTrue(r.edge(captured, Phase.UP, at = 1120, down = 1100))
        r.advance(1000)
        assertEquals(captured, r.bindings.binding(HidAction.SCROLL_UP))
        assertEquals(1, r.contacts.size)
        assertTrue(r.scrolls.isEmpty())
        assertTrue(r.actions.isEmpty())
    }

    @Test fun captureIsPublishedBeforeHeldCancellationSoAReentrantMatchedReleaseCompletesOnlyOnce() {
        val r = Rig()
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        val captured = r.unbound()
        r.target = HidAction.SCROLL_UP
        r.onCancelContact = {
            r.onCancelContact = {}
            assertEquals(HidAction.SCROLL_UP, r.input.selected)
            assertTrue(r.edge(captured, Phase.UP, at = 1120, down = 1100))
        }
        assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
        assertEquals(captured, r.bindings.binding(HidAction.SCROLL_UP))
        assertNull(r.input.selected)
        assertEquals(1, r.contacts.size)
        assertEquals(1, r.cancellations.size)
        assertTrue(r.scrolls.isEmpty())
        assertTrue(r.actions.isEmpty())
    }

    @Test fun nestedNewerCaptureCompletedDuringHeldCancellationSupersedesTheOuterCapture() {
        for (terminal in listOf(Phase.UP, Phase.CANCEL)) {
            val r = Rig()
            assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
            val outer = r.unbound()
            val newer = outer.copy(button = outer.button.copy(keyCode = 24, scanCode = 42))
            val oldScroll = r.bindings.binding(HidAction.SCROLL_UP)
            val oldFunction = r.bindings.binding(HidAction.FUNCTION)
            r.target = HidAction.SCROLL_UP
            r.onCancelContact = {
                r.onCancelContact = {}
                assertEquals(HidAction.SCROLL_UP, r.input.selected)
                r.target = HidAction.FUNCTION
                assertTrue(r.edge(newer, Phase.DOWN, at = 1200, attachment = 8))
                assertTrue(r.edge(newer, terminal, at = 1220, down = 1200, attachment = 8))
            }
            assertTrue(r.edge(outer, Phase.DOWN, at = 1100))
            assertNull(r.input.selected)
            assertTrue(r.edge(outer, Phase.UP, at = 1230, down = 1100))
            assertEquals(oldScroll, r.bindings.binding(HidAction.SCROLL_UP))
            assertEquals(if (terminal == Phase.UP) newer else oldFunction,
                r.bindings.binding(HidAction.FUNCTION))
            assertEquals(1, r.contacts.size)
            assertEquals(1, r.cancellations.size)
            assertTrue(r.scrolls.isEmpty())
            assertTrue(r.actions.isEmpty())
        }
    }

    @Test fun oldFunctionRetirementCannotCancelANewerFunctionAdmittedByTheHeldCancellationCallback() {
        val r = Rig()
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, at = 1010))
        val captured = r.unbound()
        r.target = HidAction.SCROLL_UP
        r.onCancelContact = {
            r.onCancelContact = {}
            r.target = null
            assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, at = 1110))
        }
        assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
        assertEquals(HidAction.SCROLL_UP, r.input.selected)
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1130, down = 1110))
        assertTrue(r.edge(captured, Phase.UP, at = 1140, down = 1100))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1150, down = 1010))
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
        assertEquals(captured, r.bindings.binding(HidAction.SCROLL_UP))
        assertNull(r.input.selected)
        assertEquals(1, r.contacts.size)
        assertEquals(1, r.cancellations.size)
        assertSame(r.contacts.single().owner, r.cancellations.single().owner)
        assertTrue(r.scrolls.isEmpty())
    }

    @Test fun contextChangedByHeldCancellationRetiresThePendingCaptureWithoutReplayingItsRelease() {
        val r = Rig()
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        val captured = r.unbound()
        val oldScroll = r.bindings.binding(HidAction.SCROLL_UP)
        r.target = HidAction.SCROLL_UP
        r.onCancelContact = { r.context = "projection-2" }
        assertTrue(r.edge(captured, Phase.DOWN, at = 1100))
        assertNull(r.input.selected)
        r.context = "projection-1"
        assertTrue(r.edge(captured, Phase.UP, at = 1120, down = 1100))
        assertEquals(oldScroll, r.bindings.binding(HidAction.SCROLL_UP))
        assertEquals(1, r.contacts.size)
        assertEquals(1, r.cancellations.size)
        assertTrue(r.scrolls.isEmpty())
        assertTrue(r.actions.isEmpty())
    }

    @Test fun heldTapHasOneActualDownAndUpWithoutRepeatsOrSyntheticTapTimers() {
        val r = Rig()
        val downPosition = r.point
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 1010, down = 1000, repeat = 1))
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 1020, down = 1000))
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 1030, down = 1030, repeat = 2))
        r.advance(30000)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN), r.contacts.map { it.event.phase })
        assertTrue(r.timers.isEmpty())
        r.point = PointerPosition(78f, 90f, true)
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 31040, down = 1000))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP), r.contacts.map { it.event.phase })
        assertSame(r.contacts[0].owner, r.contacts[1].owner)
        assertEquals(listOf(1000L, 31040L), r.contacts.map { it.event.timeMs })
        assertTrue(r.contacts.all { it.event.key == PadGestureRecognizer.Key.HELD_CONTACT && it.event.downTimeMs == 1000L })
        assertEquals(downPosition, r.contacts[0].event.position)
        assertEquals(r.point, r.contacts[1].event.position)
        assertEquals(2, r.positionReads)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun orphanAndLateUpsCannotCreateContactsScrollsOrFunctionActions() {
        val r = Rig()
        HidAction.entries.forEach { assertTrue(r.edge(it, Phase.UP, down = 900)) }
        r.advance(1000)
        r.assertNoOutput()
        assertFalse(r.edge(r.unbound(), Phase.UP, down = 900))
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 2100))
        r.now = 2120
        r.input.cancel()
        assertEquals(1, r.cancellations.size)
        assertSame(r.contacts.single().owner, r.cancellations.single().owner)
        assertEquals(2120L, r.cancellations.single().time)
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 2130, down = 2100))
        assertEquals(1, r.contacts.size)
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, at = 2140))
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 2160, down = 2140))
        assertNotSame(r.contacts[0].owner, r.contacts[1].owner)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP), r.contacts.map { it.event.phase })
    }

    @Test fun functionCompletesEveryShortIndividuallyWithoutNavigationClassification() {
        for (count in 1..3) {
            val r = Rig()
            repeat(count) { index ->
                if (index != 0) r.advance(100)
                r.clickFunction()
                assertEquals(List(index + 1) { FunctionIntentKind.ACTIVATE }, r.actions)
            }
            r.advance(1000)
            assertEquals(List(count) { FunctionIntentKind.ACTIVATE }, r.actions)
            assertTrue(r.contacts.isEmpty())
            assertTrue(r.scrolls.isEmpty())
        }
    }

    @Test fun functionDurationUsesOriginalEdgeTimesWhenReceiptIsDelayed() {
        val r = Rig()
        r.now = 3000
        val binding = r.buttons.getValue(HidAction.FUNCTION)
        assertTrue(r.input.accept(HidKeyInput.Event(7, binding, Phase.DOWN, 1000, 1000)))
        assertTrue(r.input.accept(HidKeyInput.Event(7, binding, Phase.UP, 1000, 1800)))
        assertEquals(listOf(FunctionIntentKind.REGISTER), r.actions)
        r.advance(1000)
        assertEquals(listOf(FunctionIntentKind.REGISTER), r.actions)
    }

    @Test fun functionHeldLongUsesTheAbsoluteDeadlineAndReleaseCannotBecomeASingle() {
        val r = Rig()
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN))
        assertEquals(1800L, r.timers.single().at)
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, at = 1250, down = 1000, repeat = 1))
        r.advance(549)
        assertTrue(r.actions.isEmpty())
        r.advance(1)
        assertEquals(listOf(FunctionIntentKind.REGISTER), r.actions)
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1900, down = 1000))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1910, down = 1000))
        r.advance(1000)
        assertEquals(listOf(FunctionIntentKind.REGISTER), r.actions)
        r.clickFunction()
        r.advance(500)
        assertEquals(listOf(FunctionIntentKind.REGISTER, FunctionIntentKind.ACTIVATE), r.actions)
    }

    @Test fun canceledDeviceOrFocusCannotDispatchEvenWhenCanceledSchedulerCallbacksArriveLate() {
        for (deviceLoss in listOf(false, true)) {
            val r = Rig()
            assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN))
            val stale = r.timers.toList()
            if (deviceLoss) r.input.cancelDevice(7) else r.input.cancel()
            stale.forEach { it.run() }
            r.advance(1000)
            assertTrue(r.actions.isEmpty())
            assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, down = 1000))
            r.clickFunction()
            r.advance(500)
            assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
        }
    }

    @Test fun newRuntimeAttachmentCannotCompleteThePreviousAttachmentsPendingFunctionSequence() {
        val r = Rig()
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, attachment = 7))
        val stale = r.timers.toList()
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, attachment = 8))
        stale.forEach { it.run() }
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1040, down = 1000, attachment = 7))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1040, down = 1000, attachment = 8))
        r.advance(500)
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
    }

    @Test fun canceledOldAttachmentCannotCancelTheNewAttachmentsHeldFunctionSequence() {
        val r = Rig()
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, attachment = 7))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN, at = 1010, attachment = 8))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.CANCEL, at = 1020, down = 1000, attachment = 7))
        assertTrue(r.edge(HidAction.FUNCTION, Phase.UP, at = 1030, down = 1010, attachment = 8))
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
        r.advance(1000)
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
    }

    @Test fun contextChangesCancelPendingFunctionAndHeldTapInsteadOfReleasingIntoTheNewContext() {
        for (nextContext in listOf("projection-2", "settings", null)) {
            val r = Rig()
            assertTrue(r.edge(HidAction.FUNCTION, Phase.DOWN))
            r.context = nextContext
            r.advance(500)
            assertTrue(r.actions.isEmpty())
            r.context = "projection-1"
            r.edge(HidAction.TAP, Phase.DOWN)
            val down = r.now
            r.context = nextContext
            assertTrue(r.edge(HidAction.TAP, Phase.UP, at = down + 20, down = down))
            assertEquals(1, r.contacts.size)
            assertEquals(1, r.cancellations.size)
            assertSame(r.contacts.single().owner, r.cancellations.single().owner)
            r.advance(1000)
            assertTrue(r.actions.isEmpty())
        }
    }

    @Test fun runtimeAttachmentsWithIdenticalDeviceAndTimestampHaveIndependentHeldOwners() {
        val r = Rig()
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, attachment = 7))
        assertTrue(r.edge(HidAction.TAP, Phase.DOWN, attachment = 8))
        assertEquals(2, r.contacts.size)
        assertNotSame(r.contacts[0].owner, r.contacts[1].owner)
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1020, down = 1000, attachment = 8))
        assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1020, down = 1000, attachment = 7))
        assertSame(r.contacts[1].owner, r.contacts[2].owner)
        assertSame(r.contacts[0].owner, r.contacts[3].owner)
        assertTrue(r.cancellations.isEmpty())
        assertEquals(4, r.contacts.size)
    }

    @Test fun differentBoundKeysAtTheSameTimestampCannotReleaseOneAnothersPress() {
        val r = Rig()
        r.edge(HidAction.TAP, Phase.DOWN)
        r.edge(HidAction.SCROLL_DOWN, Phase.DOWN)
        r.edge(HidAction.SCROLL_DOWN, Phase.UP, at = 1020, down = 1000)
        assertEquals(1, r.contacts.size)
        assertEquals(listOf(Rig.Scroll(160, r.point, 1020)), r.scrolls)
        r.edge(HidAction.TAP, Phase.UP, at = 1020, down = 1000)
        assertEquals(2, r.contacts.size)
        assertSame(r.contacts[0].owner, r.contacts[1].owner)
    }

    @Test fun heldPressRetainsItsAdmittedActionIfTheSavedMappingChangesBeforeRelease() {
        val r = Rig()
        val held = r.buttons.getValue(HidAction.TAP)
        assertTrue(r.edge(held, Phase.DOWN))
        assertTrue(r.bindings.assign(HidAction.FUNCTION, held))
        assertTrue(r.edge(held, Phase.UP, at = 1020, down = 1000))
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP), r.contacts.map { it.event.phase })
        assertSame(r.contacts[0].owner, r.contacts[1].owner)
        assertTrue(r.actions.isEmpty())
        assertTrue(r.timers.isEmpty())
    }

    @Test fun scrollUsesItsActualReleaseTimestampDirectionAndFreshPointerWithoutArmingTimers() {
        val r = Rig()
        val downPosition = r.point
        r.edge(HidAction.SCROLL_DOWN, Phase.DOWN)
        r.edge(HidAction.SCROLL_DOWN, Phase.DOWN, at = 1050, down = 1000, repeat = 1)
        assertTrue(r.scrolls.isEmpty())
        r.edge(HidAction.SCROLL_DOWN, Phase.UP, at = 1100, down = 1000)
        r.edge(HidAction.SCROLL_UP, Phase.DOWN, at = 1200)
        r.point = PointerPosition(200f, 300f, true)
        r.edge(HidAction.SCROLL_UP, Phase.UP, at = 1250, down = 1200)
        assertEquals(listOf(Rig.Scroll(160, downPosition, 1100), Rig.Scroll(-160, r.point, 1250)), r.scrolls)
        assertEquals(2, r.positionReads)
        assertTrue(r.contacts.isEmpty())
        assertTrue(r.timers.isEmpty())
        assertTrue(r.actions.isEmpty())
    }

    @Test fun pointerRefreshCancellationCannotResurrectAnInitialContactOrItsFinalUp() {
        for (cancelOnRelease in listOf(false, true)) {
            val r = Rig()
            if (cancelOnRelease) assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
            r.onPosition = { r.input.cancel() }
            if (cancelOnRelease) assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1020, down = 1000))
            else assertTrue(r.edge(HidAction.TAP, Phase.DOWN))
            assertEquals(if (cancelOnRelease) 1 else 0, r.contacts.size)
            assertEquals(if (cancelOnRelease) 1 else 0, r.cancellations.size)
            if (cancelOnRelease) {
                assertEquals(PadGestureRecognizer.Phase.DOWN, r.contacts.single().event.phase)
                assertSame(r.contacts.single().owner, r.cancellations.single().owner)
                assertEquals(1020L, r.cancellations.single().time)
            }
            r.onPosition = {}
            assertTrue(r.edge(HidAction.TAP, Phase.UP, at = 1030, down = 1000))
            assertEquals(if (cancelOnRelease) 1 else 0, r.contacts.size)
            assertTrue(r.scrolls.isEmpty())
            assertTrue(r.actions.isEmpty())
        }
    }

    @Test fun staleOrCancelingPointerRefreshConsumesScrollReleaseWithoutDispatch() {
        for (cancelOnPosition in listOf(false, true)) {
            val r = Rig()
            assertTrue(r.edge(HidAction.SCROLL_DOWN, Phase.DOWN))
            if (cancelOnPosition) r.onPosition = { r.input.cancel() }
            else r.point = r.point.copy(available = false)
            assertTrue(r.edge(HidAction.SCROLL_DOWN, Phase.UP, at = 1020, down = 1000))
            assertTrue(r.scrolls.isEmpty())
            r.onPosition = {}
            r.point = r.point.copy(available = true)
            assertTrue(r.edge(HidAction.SCROLL_DOWN, Phase.UP, at = 1030, down = 1000))
            assertTrue(r.scrolls.isEmpty())
        }
    }
}
