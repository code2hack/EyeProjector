package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class PhoneFusionTest {
    private fun point(sequence: Long, revision: Long = 1) = HeadPointerState(revision, sequence, true, false, 20f, 30f, null, null)

    @Test fun latestPointerHasOneQueuedOrInFlightSerializationAndRetainsCoalescedRevision() {
        val jobs = mutableListOf<() -> Unit>(); val sent = mutableListOf<HeadPointerState>()
        lateinit var pump: LatestHeadPointer
        pump = LatestHeadPointer({ jobs.add(it); true }, { state ->
            sent.add(state)
            if (state.sequence == 1000L) { pump.publish(point(1001, 2)); pump.publish(point(1002, 3)) }
        })
        for (n in 1L..1000L) pump.publish(point(n))
        assertEquals(1, jobs.size)
        jobs.removeAt(0)(); assertEquals(1, jobs.size)
        jobs.removeAt(0)(); assertTrue(jobs.isEmpty())
        assertEquals(listOf(1000L, 1002L), sent.map { it.sequence })
        assertEquals(3, sent.last().revision)
        pump.close(); assertFalse(pump.publish(point(1003)))
    }

    @Test fun rejectedScheduleAndClosedPendingPumpDoNotRemainPermanentlyScheduled() {
        var accepted = false; val jobs = mutableListOf<() -> Unit>(); val sent = mutableListOf<HeadPointerState>()
        val pump = LatestHeadPointer({ if (accepted) { jobs.add(it); true } else false }, sent::add)
        pump.publish(point(1)); accepted = true; pump.publish(point(2))
        assertEquals(1, jobs.size); pump.close(); jobs.single()(); assertTrue(sent.isEmpty())
    }

    @Test fun completedUiUsesReceiverClockAndOpaquePhoneContactIdentity() {
        var now = 900_000L; var revision = 4L
        val sent = mutableListOf<HeldPointerDispatcher.Event>(); var releases = 0
        val input = CompletedReceiverInput({ now }, { revision }, { true }, {}, { sent.add(it); true }, { _, _ -> true }, { releases++ }, {})
        input.accept(ReceiverInput(ReceiverInputKind.DOWN, 4, 77, 100f, 200f, heldMs = 0))
        now += 50
        input.accept(ReceiverInput(ReceiverInputKind.UP, 4, 77, 110f, 210f, heldMs = 1234567))
        assertEquals(listOf(900_000L, 900_000L), sent.map { it.contactId })
        assertEquals(900_050L, sent.last().timeMs); assertEquals(110f, sent.last().position.x)
        input.accept(ReceiverInput(ReceiverInputKind.DOWN, 4, 78))
        input.cancel(); revision = 5
        input.accept(ReceiverInput(ReceiverInputKind.UP, 4, 78))
        assertEquals(1, releases); assertEquals(3, sent.size)
    }

    @Test fun localCallbackChangingContextCannotAcquireOldCompletedDown() {
        var revision = 4L; var sent = 0
        val input = CompletedReceiverInput({ 1000 }, { revision }, { true }, { revision++ }, { sent++; true }, { _, _ -> true }, {}, {})
        input.accept(ReceiverInput(ReceiverInputKind.DOWN, 4, 1))
        assertEquals(0, sent)
    }
}
