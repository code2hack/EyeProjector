package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class ContinuousContactRecognizerTest {
    @Test fun realEdgesSurviveLongClassificationWhileAllTapSummariesStaySilent() {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        val pad = PadGestureRecognizer(300, 500) { actions.add(it) }
        val point = PointerPosition(10f, 20f, true)
        fun pair(key: PadGestureRecognizer.Key, at: Long) {
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at, position = point), at)
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + 22, at), at + 22)
        }
        pair(PadGestureRecognizer.Key.CONTACT, 900)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1000, 1000, position = point), 1000)
        for (key in listOf(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Key.TAP, PadGestureRecognizer.Key.DOUBLE)) pair(key, 1100)
        pad.classifyLongPress(1800); pad.confirm(6000)
        assertEquals(1, actions.filterIsInstance<PadGestureRecognizer.Action.Contact>().size)
        assertTrue(pad.hasWork)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 6100, 1000, position = point), 6100)
        pair(PadGestureRecognizer.Key.CONTACT, 6200); pair(PadGestureRecognizer.Key.DOUBLE, 6300); pad.confirm(8000)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.UP),
            actions.filterIsInstance<PadGestureRecognizer.Action.Contact>().map { it.event.phase })
        assertTrue(actions.none { it is PadGestureRecognizer.Action.Tap })
    }
    @Test fun independentSwipeCancelsHeldTouchBeforeOneScroll() {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        val pad = PadGestureRecognizer(300, 500) { actions.add(it) }
        fun event(key: PadGestureRecognizer.Key, phase: PadGestureRecognizer.Phase, at: Long, down: Long) =
            pad.accept(PadGestureRecognizer.Event(key, phase, at, down), at)
        event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1000, 1000)
        event(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Phase.DOWN, 1100, 1100)
        event(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Phase.UP, 1120, 1100)
        event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 1200, 1000)
        pad.confirm(3000)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.CANCEL),
            actions.filterIsInstance<PadGestureRecognizer.Action.Contact>().map { it.event.phase })
        assertEquals(1, actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().size)
        assertTrue(actions.none { it is PadGestureRecognizer.Action.Tap })
    }
    @Test fun inputLossAllowsFreshMarkerSourceWithoutReplayingTheOldContact() {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        val pad = PadGestureRecognizer(300, 500) { actions.add(it) }
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1000, 1000), 1000)
        pad.cancel(1100)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 1200, 1000), 1200)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, 1300, 1300), 1300)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, 1322, 1300), 1322)
        pad.confirm(3000)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.CANCEL),
            actions.filterIsInstance<PadGestureRecognizer.Action.Contact>().map { it.event.phase })
        assertEquals(1, actions.filterIsInstance<PadGestureRecognizer.Action.Tap>().size)
    }

}
