package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneInputMergerTest {
    private class Fixture {
        var now = 1000L
        var sequence = 0L
        val lock = Any()
        val size = Geometry(480, 640)
        val records = mutableListOf<Touch>()
        val scrolls = mutableListOf<Scroll>()
        val ui = mutableListOf<ReceiverInput>()
        val nav = mutableListOf<Navigation>()
        val intents = mutableListOf<AnchorIntent>()
        var onIntent: ((AnchorIntent) -> Unit)? = null
        var onCancel: (() -> Unit)? = null
        var onUi: ((ReceiverInput) -> Unit)? = null
        data class Job(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        val jobs = mutableListOf<Job>()
        val dispatcher = PhoneTouchDispatcher(lock) { data ->
            if (data.size > 1 && data[0].toInt() == 2 && data[1].toInt() == 3) onCancel?.invoke()
        }
        val merger = PhoneInputMerger(lock, { now }, { at, action ->
            val j = Job(at, action); jobs.add(j); val cancel: () -> Unit = { j.canceled = true }; cancel
        }, { size }, { records.add(it); dispatcher.touch(it) }, { scrolls.add(it); dispatcher.scroll(it) },
            { action, _ -> nav.add(action) }, { dispatcher.cancel() }, { assertFalse(Thread.holdsLock(lock)); ui.add(it); onUi?.invoke(it) },
            anchorIntent = { assertFalse(Thread.holdsLock(lock)); intents.add(it); onIntent?.invoke(it) },
            anchorIdentity = PhoneInputMerger.AnchorIdentity())
        init { dispatcher.geometry(size); pointer() }
        fun pointer(revision: Long = 1, available: Boolean = true, projected: Boolean = true, x: Float = 200f, y: Float = 300f) =
            merger.pointer(HeadPointerState(revision, ++sequence, available, projected, x, y, Fit(100, 100, 480, 640), size),
                ReceiverUsability("original-owner", ReceiverIncarnation("original-receiver", 1), 1, revision,
                    if (projected) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.LOCAL_PANEL))
        fun down(action: PhoneHidAction = PhoneHidAction.TAP, key: Int = 66) =
            PhoneHidEdge(action, PhoneHidPhase.DOWN, 10, key, key, now, now)
        fun advance(time: Long) {
            now = time
            while (true) { val j = jobs.firstOrNull { !it.canceled && it.at <= time } ?: break
                j.canceled = true; j.action() }
        }
    }

    @Test fun actualPhoneEdgesMergeLatestGlassesPointAndClampHeldMovement() {
        val f = Fixture(); val down = f.down(); f.merger.edge(90, down)
        f.now += 30; f.pointer(x = -20f, y = 900f)
        f.merger.edge(90, down.copy(phase = PhoneHidPhase.UP, eventTimeMs = f.now))
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.MOVE, Touch.Phase.UP), f.records.map { it.phase })
        assertEquals(Tap(100, 200, f.size), f.records.first().target)
        assertEquals(Tap(0, 639, f.size), f.records.last().target)
        assertEquals(1, f.records.first().contactId); assertNotEquals(down.downTimeMs, f.records.first().contactId)
        assertFalse(f.dispatcher.holding)
    }

    @Test fun equalNumericIdsCannotReleaseNewNativeOrNewPhoneContact() {
        val f = Fixture(); val d = f.down(); f.merger.edge(1, d)
        val native = Touch(Touch.Phase.DOWN, 1, Tap(5, 6, f.size))
        assertTrue(f.merger.nativeTouch(native))
        f.merger.edge(1, d.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1020))
        assertTrue(f.dispatcher.holding)
        assertTrue(f.merger.nativeTouch(native.copy(phase = Touch.Phase.UP)))
        assertTrue(f.merger.nativeTouch(native.copy(contactId = 2)))
        f.now = 1040; val second = f.down(); f.merger.edge(2, second)
        assertFalse(f.merger.nativeTouch(native.copy(contactId = 2, phase = Touch.Phase.UP)))
        assertTrue(f.dispatcher.holding)
        f.merger.edge(2, second.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1050))
        assertFalse(f.dispatcher.holding)
    }

    @Test fun localCompletedEventsCarryCoordinatesAndPhoneDurationButNoRemoteClockArithmetic() {
        val f = Fixture(); f.pointer(projected = false)
        val d = f.down(); f.merger.edge(9, d); f.now = 1040; f.pointer(projected = false, x = 210f)
        f.merger.edge(9, d.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1050))
        assertEquals(listOf(ReceiverInputKind.DOWN, ReceiverInputKind.MOVE, ReceiverInputKind.UP), f.ui.map { it.kind })
        assertEquals(210f, f.ui.last().x); assertEquals(50, f.ui.last().heldMs)
        assertTrue(f.records.isEmpty())
    }

    @Test fun actualCancellationCallbackCannotResurrectTheOuterNativeDown() {
        val f = Fixture(); val first = f.down(); f.merger.edge(1, first)
        f.onCancel = {
            f.onCancel = null; f.now = 1020
            f.merger.edge(3, f.down(key = 68))
        }
        assertFalse(f.merger.nativeTouch(Touch(Touch.Phase.DOWN, 2, Tap(5, 6, f.size))))
        assertTrue(f.dispatcher.holding)
        assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.DOWN), f.records.map { it.phase })
        f.merger.edge(1, first.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1030))
        assertTrue(f.dispatcher.holding)
    }

    @Test(timeout = 7000) fun concurrentCallersEmitInCommittedOrderOutsideTheControlLock() {
        val f = Fixture(); f.pointer(projected = false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.onUi = { if (it.kind == ReceiverInputKind.DOWN) { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) } }
        val d = f.down()
        val down = Thread { f.merger.edge(1, d) }
        val move = Thread { f.pointer(projected = false, x = 250f) }
        val up = Thread { f.merger.edge(1, d.copy(phase = PhoneHidPhase.UP, eventTimeMs = 1050)) }
        try {
            down.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); move.start()
            val field = PhoneInputMerger::class.java.getDeclaredField("point").apply { isAccessible = true }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (synchronized(f.lock) { (field.get(f.merger) as HeadPointerState).x != 250f } && System.nanoTime() < until) Thread.yield()
            assertEquals(250f, synchronized(f.lock) { (field.get(f.merger) as HeadPointerState).x })
            up.start(); release.countDown()
            down.join(2000); move.join(2000); up.join(2000)
            assertFalse(down.isAlive || move.isAlive || up.isAlive)
            assertEquals(listOf(ReceiverInputKind.DOWN, ReceiverInputKind.MOVE, ReceiverInputKind.UP), f.ui.map { it.kind })
        } finally { release.countDown(); down.join(2000); move.join(2000); up.join(2000) }
    }

    @Test fun completedUiCallbackCanReenterWithoutSelfWaitingOrReordering() {
        val f = Fixture(); f.pointer(projected = false)
        f.onUi = { if (it.kind == ReceiverInputKind.DOWN) f.pointer(projected = false, x = 240f) }
        f.merger.edge(1, f.down())
        assertEquals(listOf(ReceiverInputKind.DOWN, ReceiverInputKind.MOVE), f.ui.map { it.kind })
    }
}
