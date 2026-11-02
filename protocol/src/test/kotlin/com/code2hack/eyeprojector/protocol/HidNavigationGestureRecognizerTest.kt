package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test

class HidNavigationGestureRecognizerTest {
    private class Clock {
        var at = 0L
        data class Timer(val at: Long, val callback: () -> Unit, var canceled: Boolean = false)
        val timers = mutableListOf<Timer>()
        fun schedule(at: Long, callback: () -> Unit): () -> Unit {
            val timer = Timer(at, callback).also(timers::add)
            return { timer.canceled = true }
        }
        fun advance(at: Long) {
            this.at = at
            while (true) {
                val timer = timers.firstOrNull { !it.canceled && it.at <= at } ?: return
                timer.canceled = true
                timer.callback()
            }
        }
    }
    private class Rig {
        val clock = Clock()
        var context: Any? = Any()
        val actions = mutableListOf<Pair<HidNavigationGestureRecognizer.Action, Any>>()
        var onAction: () -> Unit = {}
        val input = HidNavigationGestureRecognizer<Any>(clock::at, { it === context }, clock::schedule,
            { action, captured -> actions.add(action to captured); onAction() })
        fun down(press: Any, source: Any = "learned key") = input.down(press, source, checkNotNull(context))
        fun up(press: Any, at: Long) { clock.at = at; input.up(press, at) }
        fun click(press: Any, at: Long) { clock.at = at; down(press); up(press, at + 10) }
    }

    @Test fun singleBackWaitsOnlyForExistingDoublePressArbitration() {
        val r = Rig(); val original = r.context
        r.click(1, 0)
        r.clock.advance(509); assertTrue(r.actions.isEmpty())
        r.clock.advance(510)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK to original), r.actions)
    }

    @Test fun singleDoubleTripleCommitExactlyOneActionAfterCompletion() {
        for (count in 1..3) {
            val r = Rig()
            repeat(count) { i ->
                r.click(i + 1, i * 100L)
                if (i < 2) assertTrue(r.actions.isEmpty())
            }
            if (count < 3) { r.clock.advance((count - 1) * 100L + 509); assertTrue(r.actions.isEmpty()) }
            r.clock.advance(2000)
            assertEquals(listOf(when (count) { 1 -> HidNavigationGestureRecognizer.Action.BACK
                2 -> HidNavigationGestureRecognizer.Action.LOCK; else -> HidNavigationGestureRecognizer.Action.HOME }), r.actions.map { it.first })
        }
    }

    @Test fun repeatDownAndDuplicateOrUnmatchedUpNeverMultiplyPresses() {
        val r = Rig()
        r.down(1); repeat(4) { r.down(1) }; r.up(99, 10); r.up(1, 20)
        repeat(4) { r.down(1); r.up(1, 30) }
        r.clock.advance(520)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK), r.actions.map { it.first })
        r.up(1, 600); r.clock.advance(1200)
        assertEquals(1, r.actions.size)
    }

    @Test fun cancelHeldOrReleasedPressPreventsNavigationIncludingCanceledTimer() {
        for (released in listOf(false, true)) {
            val r = Rig(); r.down(1)
            if (released) r.up(1, 10)
            val timers = r.clock.timers.toList()
            r.input.cancel(1); r.up(1, 20); r.clock.at = 1000
            timers.forEach { it.callback() } // A dequeued callback may run after cancellation.
            assertTrue(r.actions.isEmpty())
            r.click(2, 1010); r.clock.advance(1520)
            assertEquals(1, r.actions.size)
        }
    }

    @Test fun staleContextNeverNavigatesOrJoinsSuccessorDouble() {
        val r = Rig(); val old = r.context
        r.click(1, 0); r.context = Any(); val next = r.context
        r.click(2, 100); r.clock.advance(610)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK to next), r.actions)
        assertNotSame(old, r.actions.single().second)
    }

    @Test fun contextLostWhileHeldOrAwaitingSingleRetiresWithoutOutput() {
        for (released in listOf(false, true)) {
            val r = Rig(); r.down(1)
            if (released) r.up(1, 10)
            r.context = null; r.up(1, 20); r.clock.advance(1000)
            assertTrue(r.actions.isEmpty())
        }
    }

    @Test fun differentPhysicalSourceDoesNotBecomeADouble() {
        val r = Rig(); r.click(1, 0)
        r.clock.at = 100; r.down(2, "reattached key"); r.up(2, 110); r.clock.advance(610)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK), r.actions.map { it.first })
    }

    @Test fun cancelOldRouteCannotCancelIndependentSuccessorSequence() {
        val r = Rig(); r.click(1, 0); r.input.cancel(1)
        r.click(2, 100); r.input.cancel(1); r.clock.advance(610)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK), r.actions.map { it.first })
    }

    @Test fun overlappingIncompletePressDoesNotBecomeHome() {
        val r = Rig(); r.down(1); r.clock.at = 100; r.down(2)
        r.up(1, 110); r.up(2, 120); r.clock.advance(620)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK), r.actions.map { it.first })
    }

    @Test fun intervalBoundarySeparatesPressesEvenWhenTimerHasNotRun() {
        val r = Rig(); r.click(1, 0)
        r.clock.at = 510; r.down(2); r.up(2, 520); r.clock.advance(1020)
        assertEquals(listOf(HidNavigationGestureRecognizer.Action.BACK, HidNavigationGestureRecognizer.Action.BACK), r.actions.map { it.first })
    }

    @Test fun dispatchRetiresBeforeSynchronousCancellationAndSuccessorInput() {
        val r = Rig(); val old = r.context
        r.onAction = {
            r.input.cancel(); r.context = Any(); r.onAction = {}; r.click(2, 600)
        }
        r.click(1, 0); r.clock.advance(510); r.clock.advance(1110)
        assertEquals(2, r.actions.size)
        assertSame(old, r.actions[0].second)
        assertNotSame(old, r.actions[1].second)
    }
}
