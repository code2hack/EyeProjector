package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Touch
import org.junit.Assert.*
import org.junit.Test

class HeldInputOwnerTest {
    private class Rig {
        var point = PointerPosition(10f, 20f, true)
        val events = mutableListOf<HeldPointerDispatcher.Event>()
        var onEvent: (HeldPointerDispatcher.Event) -> Unit = {}
        val dispatcher = HeldPointerDispatcher({ point }) { { event ->
            events.add(event)
            onEvent(event)
            true
        } }
        val input = HeldInputOwner(dispatcher)
    }

    private fun event(phase: PadGestureRecognizer.Phase, down: Long = 1_000, at: Long = down) =
        PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, phase, at, down)

    @Test fun nativeAndHidSharingTimestampHaveIndependentOwnership() {
        val r = Rig()
        val hid = Any()
        assertTrue(r.input.acceptNative(event(PadGestureRecognizer.Phase.DOWN)))
        assertTrue(r.input.accept(hid, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.acceptNative(event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.acceptNative(event(PadGestureRecognizer.Phase.MOVE, at = 1_010)))
        assertFalse(r.input.acceptNative(event(PadGestureRecognizer.Phase.UP, at = 1_020)))
        assertTrue(r.input.holding)
        assertTrue(r.input.accept(hid, event(PadGestureRecognizer.Phase.UP, at = 1_030)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), r.events.map { it.phase })
        assertTrue(r.events.all { it.contactId == 1_000L })
        assertFalse(r.input.holding)
    }

    @Test fun distinctHidKeysCannotReleaseOrReacquireEachOthersContact() {
        val r = Rig()
        val first = Any()
        val second = Any()
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertTrue(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.UP, at = 1_010)))
        r.input.cancel(first, 1_020)
        assertTrue(r.input.holding)
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.CANCEL, at = 1_030)))
        assertTrue(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, at = 1_040)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), r.events.map { it.phase })
    }

    @Test fun referenceIdentityAndOriginalDownTimeBothMustMatch() {
        val r = Rig()
        val first = String(charArrayOf('k'))
        val equalButDifferent = String(charArrayOf('k'))
        assertTrue(first == equalButDifferent)
        assertFalse(first === equalButDifferent)
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(equalButDifferent, event(PadGestureRecognizer.Phase.UP, at = 1_010)))
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.UP, down = 999, at = 1_010)))
        assertTrue(r.input.holding)
        r.input.cancel(first, 1_020)
        assertFalse(r.input.holding)
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN, down = 1_030)))
    }

    @Test fun cancelDuringDownCannotResurrectOwnership() {
        val r = Rig()
        val source = Any()
        r.onEvent = { if (it.phase == Touch.Phase.DOWN) r.input.cancel(it.timeMs) }
        assertTrue(r.input.accept(source, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.holding)
        assertFalse(r.input.accept(source, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(source, event(PadGestureRecognizer.Phase.UP, at = 1_010)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), r.events.map { it.phase })
    }

    @Test fun cancelDuringHandoffCannotStartOldEventInNewContext() {
        val r = Rig()
        val first = Any()
        val second = Any()
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        r.onEvent = { if (it.phase == Touch.Phase.CANCEL) r.input.cancel(it.timeMs) }
        assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
        assertFalse(r.input.holding)
        assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
        assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, down = 1_010, at = 1_020)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), r.events.map { it.phase })
    }

    @Test fun reentrantReplacementDuringDownKeepsTheNewOwner() {
        val r = Rig()
        val first = Any()
        val second = Any()
        var replaced = false
        r.onEvent = {
            if (it.phase == Touch.Phase.DOWN && !replaced) {
                replaced = true
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN)))
            }
        }
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.UP, at = 1_010)))
        assertTrue(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, at = 1_020)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), r.events.map { it.phase })
    }

    @Test fun releaseCallbackCannotCancelTheNextOwnerUsingTheOldToken() {
        val r = Rig()
        val first = Any()
        val second = Any()
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        r.onEvent = {
            if (it.phase == Touch.Phase.UP && it.contactId == 1_000L) {
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
                r.input.cancel(first, 1_020)
            }
        }
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.UP, at = 1_010)))
        assertTrue(r.input.holding)
        assertTrue(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, down = 1_010, at = 1_030)))
        assertFalse(r.input.holding)
        assertEquals(2, r.events.count { it.phase == Touch.Phase.DOWN })
        assertEquals(2, r.events.count { it.phase == Touch.Phase.UP })
    }

    @Test fun nativeCancellationRetiresOldEdgesAndAllowsFreshDown() {
        val r = Rig()
        assertTrue(r.input.acceptNative(event(PadGestureRecognizer.Phase.DOWN)))
        r.input.cancel(1_010)
        assertFalse(r.input.acceptNative(event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.acceptNative(event(PadGestureRecognizer.Phase.UP, at = 1_020)))
        assertTrue(r.input.acceptNative(event(PadGestureRecognizer.Phase.DOWN, down = 1_030)))
        assertTrue(r.input.acceptNative(event(PadGestureRecognizer.Phase.UP, down = 1_030, at = 1_040)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), r.events.map { it.phase })
    }

    @Test fun cancelCallbackCanAcquireNewOwnerWithoutOldCancelClearingIt() {
        val r = Rig()
        val first = Any()
        val second = Any()
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        r.onEvent = {
            if (it.phase == Touch.Phase.CANCEL) {
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
            }
        }
        r.input.cancel(first, 1_010)
        assertTrue(r.input.holding)
        assertFalse(r.input.accept(first, event(PadGestureRecognizer.Phase.UP, at = 1_020)))
        assertTrue(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, down = 1_010, at = 1_030)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), r.events.map { it.phase })
    }

    @Test fun reentrantCanceledReplacementNeverAcquiresAnOutputContact() {
        val r = Rig()
        val first = Any()
        val second = Any()
        r.onEvent = {
            if (it.phase == Touch.Phase.DOWN) {
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
                r.input.cancel(second, 1_020)
            }
        }
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.holding)
        assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, down = 1_010, at = 1_030)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), r.events.map { it.phase })
    }

    @Test fun latestReentrantReplacementRetiresTheDisplacedQueuedPress() {
        val r = Rig()
        val first = Any()
        val second = Any()
        val third = Any()
        var queued = false
        r.onEvent = {
            if (it.phase == Touch.Phase.DOWN && !queued) {
                queued = true
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
                assertFalse(r.input.accept(third, event(PadGestureRecognizer.Phase.DOWN, down = 1_020)))
            }
        }
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
        assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, down = 1_010, at = 1_030)))
        assertTrue(r.input.accept(third, event(PadGestureRecognizer.Phase.UP, down = 1_020, at = 1_040)))
        assertEquals(listOf(1_000L, 1_020L), r.events.filter { it.phase == Touch.Phase.DOWN }.map { it.contactId })
    }

    @Test fun reentrantPressAndReleaseDrainTogetherAfterTheOuterCall() {
        val r = Rig()
        val first = Any()
        val second = Any()
        var queued = false
        r.onEvent = {
            if (it.phase == Touch.Phase.DOWN && !queued) {
                queued = true
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.DOWN, down = 1_010)))
                assertFalse(r.input.accept(second, event(PadGestureRecognizer.Phase.UP, down = 1_010, at = 1_020)))
                assertEquals(1, r.events.size)
            }
        }
        assertTrue(r.input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.holding)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL, Touch.Phase.DOWN, Touch.Phase.UP), r.events.map { it.phase })
    }

    @Test fun dispatcherSideCancellationCannotLeaveTheOwnerHolding() {
        val r = Rig()
        val owner = Any()
        r.onEvent = { if (it.phase == Touch.Phase.DOWN) r.dispatcher.cancel(it.timeMs) }
        assertTrue(r.input.accept(owner, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.holding)
        assertFalse(r.input.accept(owner, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(r.input.accept(owner, event(PadGestureRecognizer.Phase.UP, at = 1_010)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), r.events.map { it.phase })
    }

    @Test fun handoffCancelsCapturedRouteBeforeAcquiringAnotherRoute() {
        val local = mutableListOf<Touch.Phase>()
        val remote = mutableListOf<Touch.Phase>()
        var useLocal = true
        val dispatcher = HeldPointerDispatcher({ PointerPosition(10f, 20f, true) }) {
            val target = if (useLocal) local else remote
            { target.add(it.phase); true }
        }
        val input = HeldInputOwner(dispatcher)
        val first = Any()
        val second = Any()
        assertTrue(input.accept(first, event(PadGestureRecognizer.Phase.DOWN)))
        useLocal = false
        assertTrue(input.accept(second, event(PadGestureRecognizer.Phase.DOWN)))
        assertFalse(input.accept(first, event(PadGestureRecognizer.Phase.CANCEL, at = 1_010)))
        assertTrue(input.accept(second, event(PadGestureRecognizer.Phase.UP, at = 1_020)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), local)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP), remote)
    }
}
