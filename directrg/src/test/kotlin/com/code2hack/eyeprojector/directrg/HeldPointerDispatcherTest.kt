package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Touch
import org.junit.Assert.*
import org.junit.Test

class HeldPointerDispatcherTest {
    @Test fun stationaryHoldHeadMoveAndFinalReleaseShareOneDownTimeAndRoute() {
        var point = PointerPosition(10f, 20f, true)
        val local = mutableListOf<HeldPointerDispatcher.Event>(); val remote = mutableListOf<HeldPointerDispatcher.Event>()
        var useLocal = true
        val dispatcher = HeldPointerDispatcher({ point }) { if (useLocal) ({ local.add(it); true }) else ({ remote.add(it); true }) }
        fun contact(phase: PadGestureRecognizer.Phase, time: Long) =
            PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, phase, time, 1000, position = point)
        assertTrue(dispatcher.accept(contact(PadGestureRecognizer.Phase.DOWN, 1000)))
        assertTrue(dispatcher.holding); assertEquals(1, local.size)
        useLocal = false
        point = point.copy(x = 100f); assertTrue(dispatcher.move(2500))
        point = point.copy(x = 200f); assertTrue(dispatcher.accept(contact(PadGestureRecognizer.Phase.UP, 3000)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.MOVE, Touch.Phase.UP), local.map { it.phase })
        assertTrue(local.all { it.contactId == 1000L }); assertTrue(remote.isEmpty())
        assertEquals(200f, local.last().position.x, 0f); assertFalse(dispatcher.holding)
    }
    @Test fun lossCancellationDoesNotProduceAnUpOrReplayOnReturn() {
        var point = PointerPosition(10f, 20f, true)
        val events = mutableListOf<HeldPointerDispatcher.Event>()
        val dispatcher = HeldPointerDispatcher({ point }) { { events.add(it); true } }
        val down = PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1000, 1000, position = point)
        dispatcher.accept(down); point = point.copy(available = false); dispatcher.move(1100)
        point = point.copy(available = true)
        assertFalse(dispatcher.accept(down.copy(phase = PadGestureRecognizer.Phase.UP, timeMs = 1200)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL), events.map { it.phase })
        assertFalse(dispatcher.holding)
    }
    @Test fun rejectedReleaseStillAttemptsCancellationThroughTheCapturedRoute() {
        val point = PointerPosition(10f, 20f, true)
        val attempts = mutableListOf<Touch.Phase>()
        val dispatcher = HeldPointerDispatcher({ point }) { { attempts.add(it.phase); it.phase != Touch.Phase.UP } }
        val down = PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1000, 1000, position = point)
        assertTrue(dispatcher.accept(down))
        assertFalse(dispatcher.accept(down.copy(phase = PadGestureRecognizer.Phase.UP, timeMs = 1200)))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP, Touch.Phase.CANCEL), attempts)
        assertFalse(dispatcher.holding)
    }

    @Test fun localCallbackCancellationCannotResurrectDownOrMove() {
        for (phase in listOf(Touch.Phase.DOWN, Touch.Phase.MOVE)) {
            var point = PointerPosition(10f, 20f, true)
            val events = mutableListOf<Touch.Phase>()
            lateinit var dispatcher: HeldPointerDispatcher
            dispatcher = HeldPointerDispatcher({ point }) { { event ->
                events.add(event.phase)
                if (event.phase == phase) dispatcher.cancel(event.timeMs)
                true
            } }
            dispatcher.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT,
                PadGestureRecognizer.Phase.DOWN, 1000, 1000, position = point))
            if (phase == Touch.Phase.MOVE) { point = point.copy(x = 100f); dispatcher.move(1100) }
            assertFalse(dispatcher.holding)
            assertEquals(Touch.Phase.CANCEL, events.last())
            assertEquals(1, events.count { it == Touch.Phase.CANCEL })
        }
    }

}
