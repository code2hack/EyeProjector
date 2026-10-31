package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.FunctionIntentKind
import com.code2hack.eyeprojector.directrg.HidKeyInput.Phase
import com.code2hack.eyeprojector.protocol.Touch
import org.junit.Assert.*
import org.junit.Test

/** Actual HID admission, owner and dispatcher; callbacks stand in for synchronous target code. */
class HidAdmissionReentrancyTest {
    private class Rig {
        data class Timer(val at: Long, val run: () -> Unit, var canceled: Boolean = false)
        var now = 1_000L
        var context: Any? = "original context"
        var point = PointerPosition(12f, 34f, true)
        var onPosition: () -> Unit = {}
        var onOutput: (HeldPointerDispatcher.Event) -> Unit = {}
        var onContact: (Any, PadGestureRecognizer.Event) -> Unit = { _, _ -> }
        val output = mutableListOf<HeldPointerDispatcher.Event>()
        val offeredOwners = mutableListOf<Any>()
        val timers = mutableListOf<Timer>()
        val actions = mutableListOf<FunctionIntentKind>()
        val bindings = HidBindings({ null }, { true })
        val tap = HidBinding(HidButton(HidDeviceIdentity("actual descriptor", 17, 29), 30, 40), "Actual peripheral", "Tap")
        val function = tap.copy(button = tap.button.copy(keyCode = 31, scanCode = 41), keyLabel = "Function")
        val dispatcher = HeldPointerDispatcher({ point }) { { event ->
            output.add(event)
            onOutput(event)
            true
        } }
        val owner = HeldInputOwner(dispatcher)
        val input: HidKeyInput = HidKeyInput(bindings, { now }, { context }, { at, run ->
            val timer = Timer(at, run)
            timers.add(timer)
            val cancel: () -> Unit = { timer.canceled = true }
            cancel
        }, {
            val callback = onPosition
            onPosition = {}
            callback()
            point
        }, { token, event ->
            offeredOwners.add(token)
            onContact(token, event)
            // The Activity also admits a DOWN only while this exact HID owner remains current.
            if (event.phase != PadGestureRecognizer.Phase.DOWN || inputOwns(token)) owner.accept(token, event)
        }, { token, at -> owner.cancel(token, at) }, { _, _, _ -> error("unexpected scroll") }, { actions.add(it.kind) })

        init {
            assertTrue(bindings.assign(HidAction.TAP, tap))
            assertTrue(bindings.assign(HidAction.FUNCTION, function))
        }

        // Keep constructor callbacks independent of property initialization inference.
        private fun inputOwns(token: Any): Boolean = input.owns(token)

        fun edge(phase: Phase, down: Long, at: Long = down, binding: HidBinding = tap,
                 attachment: Int = 7): Boolean {
            now = at
            return input.accept(HidKeyInput.Event(attachment, binding, phase, down, at))
        }

        fun advance(to: Long) {
            while (true) {
                val next = timers.filter { !it.canceled && it.at <= to }.minByOrNull { it.at } ?: break
                next.canceled = true
                now = maxOf(now, next.at)
                next.run()
            }
            now = to
        }

        fun assertEdges(vararg expected: Pair<Touch.Phase, Long>) =
            assertEquals(expected.toList(), output.map { it.phase to it.contactId })
    }

    @Test fun cancelCallbackNewerAdmissionAtSameContextSurvivesReplacementAndItsRealUp() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        val oldOwner = r.offeredOwners.single()
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertFalse(r.input.owns(oldOwner))
                assertTrue(r.edge(Phase.DOWN, 1_200)) // No global cancel or context change.
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L, Touch.Phase.DOWN to 1_200L)
        assertTrue(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_200, 1_300))
        assertFalse(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_100, 1_310))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertEquals(listOf(1_000L, 1_100L, 1_200L, 1_300L), r.output.map { it.timeMs })
        assertFalse(r.input.owns(oldOwner))
    }

    @Test fun completedNestedPressStillSupersedesTheOuterPendingAdmission() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertTrue(r.edge(Phase.DOWN, 1_200))
                assertTrue(r.edge(Phase.UP, 1_200, 1_230))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertFalse(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_100, 1_240))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun exactPendingReleaseRetiresAdmissionButUnrelatedDownTimeDoesNot() {
        for (matchingRelease in listOf(true, false)) {
            val r = Rig()
            assertTrue(r.edge(Phase.DOWN, 1_000))
            r.onOutput = {
                if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                    // Even a changed saved mapping cannot turn this pending TAP release into
                    // the Function recognizer's native orphan-UP fallback.
                    assertTrue(r.bindings.assign(HidAction.FUNCTION, r.tap))
                    assertTrue(r.edge(Phase.UP, if (matchingRelease) 1_100 else 1_099, 1_150))
                }
            }
            assertTrue(r.edge(Phase.DOWN, 1_100))
            assertEquals(!matchingRelease, r.dispatcher.holding)
            assertTrue(r.edge(Phase.UP, 1_100, 1_200))
            if (matchingRelease) r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L)
            else r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
                Touch.Phase.DOWN to 1_100L, Touch.Phase.UP to 1_100L)
            assertFalse(r.dispatcher.holding)
            assertTrue(r.offeredOwners.none { r.input.owns(it) })
            assertTrue(r.timers.isEmpty())
            r.advance(3_000)
            assertTrue(r.actions.isEmpty())
        }
    }

    @Test fun missingDeviceReleaseRetiresPendingReplacementBeforeItsDown() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertTrue(r.input.consumeDetached(7, r.tap.button.keyCode, r.tap.button.scanCode,
                    Phase.UP, 1_100))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_200))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun cancelCallbackWithoutNewerAdmissionCannotReviveTheCanceledReplacement() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = { if (it.phase == Touch.Phase.CANCEL) r.input.cancel() }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_150))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
        r.onOutput = {}
        assertTrue(r.edge(Phase.DOWN, 1_200))
        assertTrue(r.edge(Phase.UP, 1_200, 1_230))
        assertFalse(r.dispatcher.holding)
        assertEquals(1_200L, r.output.last().contactId)
        assertEquals(Touch.Phase.UP, r.output.last().phase)
    }

    @Test fun cancelCallbackContextChangeCannotRetargetAnOuterReplacement() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = { if (it.phase == Touch.Phase.CANCEL) r.context = "new context" }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_150))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun cancelCallbackNewerContextAdmissionWinsWithoutGlobalCancellation() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                r.context = "new context"
                assertTrue(r.edge(Phase.DOWN, 1_200))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_200, 1_230))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertFalse(r.dispatcher.holding)
    }

    @Test fun newerAdmissionDuringInitialPointerPublicationOwnsItsRelease() {
        val r = Rig()
        r.onPosition = { assertTrue(r.edge(Phase.DOWN, 1_200)) }
        assertTrue(r.edge(Phase.DOWN, 1_000))
        assertTrue(r.edge(Phase.UP, 1_200, 1_230))
        assertTrue(r.edge(Phase.UP, 1_000, 1_240))
        r.assertEdges(Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertFalse(r.dispatcher.holding)
    }

    @Test fun newerAdmissionDuringReplacementPointerPublicationOwnsItsRelease() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onPosition = { assertTrue(r.edge(Phase.DOWN, 1_200)) }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_200, 1_230))
        assertTrue(r.edge(Phase.UP, 1_100, 1_240))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertFalse(r.dispatcher.holding)
    }

    @Test fun nestedAdmissionThenCancellationCannotRestoreTheOuterOrNestedOwner() {
        for (publication in listOf(false, true)) {
            val r = Rig()
            val nested: () -> Unit = {
                assertTrue(r.edge(Phase.DOWN, 1_200))
                r.input.cancel()
            }
            if (publication) r.onPosition = nested
            else {
                assertTrue(r.edge(Phase.DOWN, 1_000))
                r.onOutput = { if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) nested() }
            }
            assertTrue(r.edge(Phase.DOWN, 1_100))
            assertTrue(r.edge(Phase.UP, 1_200, 1_230))
            assertTrue(r.edge(Phase.UP, 1_100, 1_240))
            assertFalse(r.dispatcher.holding)
            assertFalse(r.output.any { it.phase == Touch.Phase.DOWN && it.contactId == 1_100L })
            assertTrue(r.offeredOwners.none { r.input.owns(it) })
            assertEquals(Touch.Phase.CANCEL, r.output.last().phase)
        }
    }

    @Test fun unrelatedFunctionAdmissionDoesNotDisplaceTheTapReplacement() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL) assertTrue(r.edge(Phase.DOWN, 1_120, binding = r.function))
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_150))
        assertTrue(r.edge(Phase.UP, 1_120, 1_160, binding = r.function))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_100L, Touch.Phase.UP to 1_100L)
        assertFalse(r.dispatcher.holding)
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
    }

    @Test fun otherAttachmentCancelCallbackAdmissionOwnsSharedContactAndItsActualUp() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        val oldOwner = r.offeredOwners.single()
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertFalse(r.input.owns(oldOwner))
                assertTrue(r.edge(Phase.DOWN, 1_200, attachment = 8))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        val newerOwner = r.offeredOwners.last()
        assertTrue(r.input.owns(newerOwner))
        assertTrue(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_100, 1_220))
        assertTrue(r.input.owns(newerOwner))
        assertTrue(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_200, 1_230, attachment = 8))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertEquals(listOf(1_000L, 1_100L, 1_200L, 1_230L), r.output.map { it.timeMs })
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun completedOtherAttachmentPressStillSupersedesOlderPendingReplacement() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertTrue(r.edge(Phase.DOWN, 1_200, attachment = 8))
                assertTrue(r.edge(Phase.UP, 1_200, 1_230, attachment = 8))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_240))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun explicitlyCanceledOtherAttachmentPressCannotRestoreOlderPendingReplacement() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertTrue(r.edge(Phase.DOWN, 1_200, attachment = 8))
                // This is the actual press's CANCEL edge, not input.cancel()/a context change.
                assertTrue(r.edge(Phase.CANCEL, 1_200, 1_230, attachment = 8))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_200, 1_240, attachment = 8))
        assertTrue(r.edge(Phase.UP, 1_100, 1_250))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun otherAttachmentPendingExactTerminalRetiresOnlyItsAdmission() {
        for (terminal in listOf(Phase.UP, Phase.CANCEL)) {
            for (matchingTerminal in listOf(true, false)) {
                val r = Rig()
                assertTrue(r.edge(Phase.DOWN, 1_000))
                r.onOutput = {
                    if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                        r.onPosition = {
                            assertTrue(r.edge(terminal, if (matchingTerminal) 1_200 else 1_199,
                                1_210, attachment = 8))
                        }
                        assertTrue(r.edge(Phase.DOWN, 1_200, attachment = 8))
                    }
                }
                assertTrue(r.edge(Phase.DOWN, 1_100))
                assertEquals(!matchingTerminal, r.dispatcher.holding)
                assertTrue(r.edge(Phase.UP, 1_100, 1_220))
                assertEquals(!matchingTerminal, r.dispatcher.holding)
                assertTrue(r.edge(Phase.UP, 1_200, 1_230, attachment = 8))
                if (matchingTerminal) {
                    r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L)
                } else {
                    r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
                        Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
                }
                assertFalse(r.dispatcher.holding)
                assertTrue(r.offeredOwners.none { r.input.owns(it) })
                assertTrue(r.timers.isEmpty())
            }
        }
    }

    @Test fun otherAttachmentPointerPublicationAdmissionSupersedesInitialAndReplacementDowns() {
        for (replacement in listOf(false, true)) {
            val r = Rig()
            if (replacement) assertTrue(r.edge(Phase.DOWN, 1_000))
            r.onPosition = { assertTrue(r.edge(Phase.DOWN, 1_200, attachment = 8)) }
            assertTrue(r.edge(Phase.DOWN, 1_100))
            assertTrue(r.edge(Phase.UP, 1_100, 1_220))
            assertTrue(r.dispatcher.holding)
            assertTrue(r.edge(Phase.UP, 1_200, 1_230, attachment = 8))
            if (replacement) {
                r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
                    Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
            } else r.assertEdges(Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
            assertFalse(r.dispatcher.holding)
            assertTrue(r.offeredOwners.none { r.input.owns(it) })
        }
    }

    @Test fun callbackBeforeOwnerCheckCannotDeliverAnOlderAttachmentDown() {
        val r = Rig()
        r.onContact = { oldOwner, event ->
            if (event.phase == PadGestureRecognizer.Phase.DOWN && event.downTimeMs == 1_100L) {
                r.onContact = { _, _ -> }
                // Like Receiver's synchronous native cancellation before hidInput.owns(owner).
                assertTrue(r.edge(Phase.DOWN, 1_200, attachment = 8))
                assertFalse(r.input.owns(oldOwner))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_220))
        assertTrue(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_200, 1_230, attachment = 8))
        r.assertEdges(Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertFalse(r.dispatcher.holding)
    }

    @Test fun staleOtherAttachmentOwnerReleaseCannotRetireNewerSharedAdmission() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        val oldOwner = r.offeredOwners.single()
        assertTrue(r.edge(Phase.DOWN, 1_100, attachment = 8))
        val newerOwner = r.offeredOwners.last()
        assertFalse(r.input.owns(oldOwner))
        assertTrue(r.input.owns(newerOwner))
        assertTrue(r.edge(Phase.UP, 1_000, 1_120))
        assertTrue(r.dispatcher.holding)
        assertTrue(r.input.owns(newerOwner))
        assertTrue(r.edge(Phase.UP, 1_100, 1_130, attachment = 8))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_100L, Touch.Phase.UP to 1_100L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.offeredOwners.none { r.input.owns(it) })
    }

    @Test fun otherAttachmentFunctionAdmissionRemainsIndependentOfTapReplacement() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertTrue(r.edge(Phase.DOWN, 1_120, binding = r.function, attachment = 8))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_100, 1_150))
        assertTrue(r.edge(Phase.UP, 1_120, 1_160, binding = r.function, attachment = 8))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_100L, Touch.Phase.UP to 1_100L)
        assertEquals(listOf(FunctionIntentKind.ACTIVATE), r.actions)
        assertFalse(r.dispatcher.holding)
    }

    @Test fun reassignedDifferentKeyTapSupersedesOldKeysRetainedAdmittedAction() {
        val r = Rig()
        assertTrue(r.edge(Phase.DOWN, 1_000))
        // A real mapping move leaves the held old key's already admitted action intact.
        assertTrue(r.bindings.assign(HidAction.TAP, r.function))
        assertNull(r.bindings.action(r.tap.button))
        assertEquals(HidAction.TAP, r.bindings.action(r.function.button))
        r.onOutput = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                assertTrue(r.edge(Phase.DOWN, 1_200, binding = r.function, attachment = 8))
            }
        }
        assertTrue(r.edge(Phase.DOWN, 1_100))
        assertTrue(r.edge(Phase.UP, 1_100, 1_220))
        assertTrue(r.dispatcher.holding)
        assertTrue(r.edge(Phase.UP, 1_200, 1_230, binding = r.function, attachment = 8))
        r.assertEdges(Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L)
        assertFalse(r.dispatcher.holding)
        assertTrue(r.timers.isEmpty())
        assertTrue(r.actions.isEmpty())
    }
}
