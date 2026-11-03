package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test

class PhoneHidGestureRecognizerTest {
    private class Context(var saved: PhoneHidGesture, val owner: Int = 1)
    private class Fixture(discover: Boolean = false) {
        var now = 1000L
        var owner = 1
        var captures = 0
        data class Job(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        val jobs = mutableListOf<Job>()
        val delivered = mutableListOf<Pair<PhoneHidGesture, Context>>()
        var onDispatch: (() -> Unit)? = null
        val recognizer = PhoneHidGestureRecognizer<Context>({ now }, { it.owner == owner }, { at, action ->
            val job = Job(at, action); jobs.add(job)
            val cancel: () -> Unit = { job.canceled = true }; cancel
        }, { gesture, context -> delivered.add(gesture to context); onDispatch?.invoke() },
            match = { captures++; if (discover) null else it.saved })
        fun down(id: Int, context: Context, source: Int = 1) = recognizer.down(id, source, context, now)
        fun up(id: Int, duration: Long = 20) { now += duration; recognizer.up(id, now) }
    }

    @Test fun savedGestureIsReadOnceAtOriginalDownAndSequenceRetiresBeforeReentry() {
        val f = Fixture(); val first = Context(PhoneHidGesture.SINGLE)
        f.down(1, first); first.saved = PhoneHidGesture.clicks(3)
        val successor = Context(PhoneHidGesture.SINGLE)
        f.onDispatch = { if (f.delivered.size == 1) f.down(2, successor) }
        f.up(1)
        assertEquals(listOf(PhoneHidGesture.SINGLE), f.delivered.map { it.first })
        assertNull(f.recognizer.capturedSequence(1)); assertSame(successor, f.recognizer.capturedSequence(2))
        f.recognizer.cancel(1); f.up(2)
        assertEquals(2, f.delivered.size); assertEquals(2, f.captures)
        assertTrue(f.jobs.isEmpty())
    }

    @Test fun incompleteSequenceRetainsOriginalContextAndEarlyOrCanceledTimersCannotFinishIt() {
        val f = Fixture(); val original = Context(PhoneHidGesture.clicks(2))
        f.down(1, original); f.up(1)
        val timer = f.jobs.single()
        f.now = timer.at - 1; timer.action()
        assertTrue(f.delivered.isEmpty())
        f.down(2, Context(PhoneHidGesture.clicks(2)))
        assertSame(original, f.recognizer.capturedSequence(2)); assertEquals(1, f.captures)
        f.now += 100; f.jobs.toList().forEach { it.action() }
        assertTrue(f.delivered.isEmpty())
        f.up(2); assertSame(original, f.delivered.single().second)
        f.jobs.toList().forEach { it.action() }; assertEquals(1, f.delivered.size)
    }

    @Test fun cancelOfAnyOriginalMemberRetiresIncompleteSequenceWithoutAdoptingNewOwnerOrSource() {
        val f = Fixture(); val original = Context(PhoneHidGesture.clicks(3))
        f.down(1, original); f.up(1); f.down(2, original); f.up(2)
        val timers = f.jobs.map { it.action }
        f.recognizer.cancel(1)
        f.owner = 2
        val successor = Context(PhoneHidGesture.clicks(2), 2)
        f.down(3, successor, 2); f.up(3)
        timers.forEach { it() }; f.recognizer.up(2, f.now)
        assertTrue(f.delivered.isEmpty())
        f.down(4, successor, 2); f.up(4)
        assertSame(successor, f.delivered.single().second)
    }

    @Test fun sourceReplacementAndContextLossNeverCombineIncompleteClicks() {
        val f = Fixture(); val first = Context(PhoneHidGesture.clicks(2))
        f.down(1, first); f.up(1)
        f.down(2, first, 2); f.up(2); assertTrue(f.delivered.isEmpty())
        f.owner = 2; val next = Context(PhoneHidGesture.clicks(2), 2)
        f.down(3, next, 2); f.up(3); assertTrue(f.delivered.isEmpty())
        f.down(4, next, 2); f.up(4); assertSame(next, f.delivered.single().second)
    }

    @Test fun defaultDiscoveryWaitsForQuietAndKeepsGenericClickCount() {
        val f = Fixture(discover = true); val original = Context(PhoneHidGesture.SINGLE)
        repeat(4) { f.down(it, original); f.up(it); f.now += 50 }
        assertTrue(f.delivered.isEmpty()); assertEquals(1, f.captures)
        val last = f.jobs.last()
        f.now = last.at - 1; last.action(); assertTrue(f.delivered.isEmpty())
        f.now = last.at; f.jobs.last().action()
        assertEquals(PhoneHidGesture.clicks(4), f.delivered.single().first)
        f.jobs.toList().forEach { it.action() }; assertEquals(1, f.delivered.size)
    }
}
