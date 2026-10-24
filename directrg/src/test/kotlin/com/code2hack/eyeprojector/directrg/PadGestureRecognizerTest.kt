package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

/** Replays are host evidence; they do not establish current physical input delivery. */
class PadGestureRecognizerTest {
    private class Replay {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        val pad = PadGestureRecognizer(300, 500, actions::add)
        fun key(key: PadGestureRecognizer.Key, down: Long, up: Long = down + 22, position: PointerPosition? = null) {
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, down, down, position = position), down)
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, up, down), up)
        }
        fun marker(down: Long, up: Long = down + 22, position: PointerPosition? = null) = key(PadGestureRecognizer.Key.CONTACT, down, up, position)
        fun taps() = actions.filterIsInstance<PadGestureRecognizer.Action.Tap>()
    }
    @Test fun capturedThreeMarkersBecomeThreeTouchesWithOriginalIntervals() {
        val r = Replay()
        // Actual captured pulses; no ENTER/BACK summary followed this triple.
        r.marker(17048181, 17048204); r.marker(17048371, 17048392); r.marker(17048524, 17048548)
        r.pad.confirm(17049203); assertTrue(r.actions.isEmpty())
        r.pad.confirm(17049204); assertEquals(1, r.taps().size)
        r.pad.confirm(17049548)
        assertEquals(listOf(17048181L, 17048371L, 17048524L), r.taps().map { it.downTimeMs })
        assertEquals(listOf(17049204L, 17049392L, 17049548L), r.taps().map { it.timeMs })
        assertEquals(listOf(23L, 21L, 24L), r.taps().map { it.upTimeMs - it.downTimeMs })
        assertEquals(3, r.actions.size) // No Back or Disconnect action exists for pad taps.
    }
    @Test fun oneTwoFourAndSixQuickTapsHaveMatchingCounts() {
        for (count in listOf(1, 2, 4, 6)) {
            val r = Replay()
            repeat(count) { r.marker(1000L + it * 170) }
            r.pad.confirm(4000)
            assertEquals(count, r.taps().size)
        }
    }
    @Test fun lateAndRepeatedSummariesDoNotAddTouchesAcrossCancellation() {
        val r = Replay(); r.marker(1000)
        r.key(PadGestureRecognizer.Key.TAP, 1500)
        r.pad.confirm(2100); assertEquals(1, r.taps().size)
        r.marker(2500); r.marker(2670)
        r.key(PadGestureRecognizer.Key.DOUBLE, 2950)
        r.key(PadGestureRecognizer.Key.DOUBLE, 2950)
        r.pad.confirm(3800); assertEquals(3, r.taps().size)
        r.pad.cancel(4000)
        r.key(PadGestureRecognizer.Key.TAP, 4400); r.key(PadGestureRecognizer.Key.DOUBLE, 4600)
        r.pad.confirm(5000); assertEquals(3, r.taps().size)
        r.marker(5100); r.pad.confirm(6200); assertEquals(4, r.taps().size)
    }
    @Test fun eachMeasuredLongTouchRetiresItsMarkerBeforeAnyTap() {
        for ((down, up, classification) in listOf(Triple(150893566L, 150893588L, 150894382L),
            Triple(150900125L, 150900148L, 150900940L))) {
            val r = Replay(); r.marker(down, up)
            r.pad.confirm(down + 500); assertTrue(r.actions.isEmpty())
            r.pad.classifyLongPress(classification)
            r.pad.confirm(classification + 4000)
            assertTrue(r.taps().isEmpty())
            assertEquals(listOf(PadGestureRecognizer.Action.LongPress(down, classification)), r.actions)
            // No fabricated held UP: real raw KEY_PROG1 release was seconds later.
        }
    }
    @Test fun swipeReplacesOnlyItsMarkerAndConsumesDocumentedCompanion() {
        val r = Replay(); val point = PointerPosition(123f, 234f, true)
        r.marker(1000, position = point)
        r.marker(1170, position = point)
        r.key(PadGestureRecognizer.Key.FORWARD, 1280)
        r.key(PadGestureRecognizer.Key.FORWARD_TAIL, 1350)
        r.key(PadGestureRecognizer.Key.FORWARD_ALIAS, 1400)
        r.marker(1500, position = point); r.key(PadGestureRecognizer.Key.BACKWARD, 1630)
        r.key(PadGestureRecognizer.Key.BACKWARD_TAIL, 1700)
        r.pad.confirm(3000)
        assertEquals(1, r.taps().size)
        assertEquals(listOf(160, -160), r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().map { it.pixels })
        assertEquals(listOf(point, point), r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().map { it.position })
        assertTrue(r.actions[0] is PadGestureRecognizer.Action.Tap)
        assertTrue(r.actions[1] is PadGestureRecognizer.Action.Scroll)
    }
    @Test fun rapidSameDirectionSwipesWithFreshMarkersRemainDistinct() {
        val r = Replay()
        r.marker(1000); r.key(PadGestureRecognizer.Key.FORWARD, 1100)
        r.marker(1200); r.key(PadGestureRecognizer.Key.FORWARD, 1300)
        r.pad.confirm(2500)
        assertEquals(2, r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().size)
        assertTrue(r.taps().isEmpty())
    }
    @Test fun canceledPendingMissingUpAndStaleEventsCannotTouchNewContext() {
        val r = Replay(); r.marker(1000); r.pad.cancel(1100)
        r.key(PadGestureRecognizer.Key.TAP, 1500); r.pad.confirm(2200); assertTrue(r.actions.isEmpty())
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, 2300, 2300), 2300)
        r.pad.confirm(3300)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, 3400, 2300), 3400)
        r.marker(3500); r.pad.confirm(4600); assertEquals(1, r.taps().size)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, 5000, 5000), 5300)
        r.pad.confirm(6500); assertEquals(1, r.taps().size)
    }
    @Test fun callbackCancellationStopsRemainingReplay() {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        lateinit var pad: PadGestureRecognizer
        pad = PadGestureRecognizer(300, 500) { actions.add(it); pad.cancel(4000) }
        for (at in listOf(1000L, 1170L)) {
            pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, at, at), at)
            pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, at + 22, at), at + 22)
        }
        pad.confirm(3000); assertEquals(1, actions.size)
    }
    @Test fun trueHeldEdgesAndMovesStayContinuousAndCancelExplicitly() {
        val r = Replay()
        fun edge(phase: PadGestureRecognizer.Phase, time: Long, repeat: Int = 0) = r.pad.accept(
            PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, phase, time, 1000, repeat), time)
        edge(PadGestureRecognizer.Phase.DOWN, 1000)
        edge(PadGestureRecognizer.Phase.DOWN, 1300, 1)
        edge(PadGestureRecognizer.Phase.MOVE, 1500)
        r.pad.confirm(1800)
        assertEquals(2, r.actions.size)
        r.pad.cancel(1900)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.MOVE, PadGestureRecognizer.Phase.CANCEL),
            r.actions.filterIsInstance<PadGestureRecognizer.Action.Contact>().map { it.event.phase })
        assertTrue(r.taps().isEmpty())
        edge(PadGestureRecognizer.Phase.UP, 2000); assertEquals(3, r.actions.size)
    }
    @Test fun summaryOnlyDoubleHasExplicitlyInferredTiming() {
        val r = Replay(); r.key(PadGestureRecognizer.Key.DOUBLE, 1000); r.pad.confirm(1400)
        assertEquals(2, r.taps().size)
        assertEquals(listOf(850L, 1000L), r.taps().map { it.downTimeMs })
        assertTrue(r.taps().all { it.timing == PadGestureRecognizer.Timing.INFERRED_SUMMARY })
    }
    @Test fun duplicatePulseDoesNotInvalidateAnotherPendingTap() {
        val r = Replay(); r.marker(1000); r.marker(1170); r.marker(1000)
        r.pad.confirm(2500); assertEquals(2, r.taps().size)
    }
    @Test fun classificationBeforeMarkerUpConsumesOnlyTheNewestContact() {
        val r = Replay(); val first = PointerPosition(10f, 20f, true); val second = PointerPosition(30f, 40f, true)
        r.marker(1000, position = first)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN,
            1200, 1200, position = second), 1200)
        r.key(PadGestureRecognizer.Key.FORWARD, 1210, 1220)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, 1230, 1200), 1230)
        r.pad.confirm(2500)
        assertEquals(first, r.taps().single().position)
        assertEquals(second, r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().single().position)
    }
    @Test fun markerlessClassificationCannotOvertakeEarlierDelayedInput() {
        val r = Replay(); r.marker(1000); r.marker(1170)
        r.key(PadGestureRecognizer.Key.FORWARD, 1280)
        r.key(PadGestureRecognizer.Key.BACKWARD, 1400)
        assertTrue(r.actions.isEmpty())
        r.pad.confirm(2500)
        assertEquals(1000L, r.taps().single().downTimeMs)
        assertTrue(r.actions.first() is PadGestureRecognizer.Action.Tap)
        assertEquals(listOf(160, -160), r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().map { it.pixels })
        assertTrue(r.actions.zipWithNext { a, b -> b.timeMs >= a.timeMs }.all { it })
    }
    @Test fun classifiedIsolatedSwipesDoNotWaitForAmbiguousTapReplay() {
        for ((key, pixels) in listOf(PadGestureRecognizer.Key.FORWARD to 160,
            PadGestureRecognizer.Key.BACKWARD to -160,
            PadGestureRecognizer.Key.FORWARD_ALIAS to 160,
            PadGestureRecognizer.Key.BACKWARD_ALIAS to -160)) {
            val r = Replay(); val point = PointerPosition(123f, 234f, true)
            r.marker(1000, position = point)
            r.key(key, 1200, 1222)
            assertEquals(listOf(PadGestureRecognizer.Action.Scroll(pixels, point, 1222)), r.actions)
            r.pad.confirm(5000)
            assertEquals(1, r.actions.size)
        }
    }
    @Test fun definitiveCompanionDispatchesWithoutFirstKeyUpOrPreliminaryTap() {
        val r = Replay(); val point = PointerPosition(123f, 234f, true)
        r.marker(1000, position = point)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.FORWARD,
            PadGestureRecognizer.Phase.DOWN, 1100, 1100), 1100)
        assertTrue(r.actions.isEmpty())
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.FORWARD_TAIL,
            PadGestureRecognizer.Phase.DOWN, 1300, 1300), 1300)
        assertEquals(listOf(PadGestureRecognizer.Action.Scroll(160, point, 1300)), r.actions)
        r.key(PadGestureRecognizer.Key.FORWARD_ALIAS, 1400)
        r.pad.confirm(5000)
        assertEquals(1, r.actions.size)
    }
    @Test fun markerlessSwipesAlsoDispatchAtClassification() {
        val r = Replay(); val point = PointerPosition(123f, 234f, true)
        r.key(PadGestureRecognizer.Key.FORWARD, 1000, 1022, point)
        r.key(PadGestureRecognizer.Key.BACKWARD, 1200, 1222, point)
        assertEquals(listOf(PadGestureRecognizer.Action.Scroll(160, point, 1022),
            PadGestureRecognizer.Action.Scroll(-160, point, 1222)), r.actions)
    }
    @Test fun immediateSwipeCallbackCancellationCannotReplayItsLateAlias() {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        lateinit var pad: PadGestureRecognizer
        pad = PadGestureRecognizer(300, 500) { action ->
            actions.add(action)
            pad.cancel(action.timeMs)
        }
        fun key(key: PadGestureRecognizer.Key, at: Long) {
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at), at)
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + 22, at), at + 22)
        }
        key(PadGestureRecognizer.Key.CONTACT, 1000)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.FORWARD,
            PadGestureRecognizer.Phase.DOWN, 1100, 1100), 1100)
        pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.FORWARD_TAIL,
            PadGestureRecognizer.Phase.DOWN, 1300, 1300), 1300)
        assertEquals(1, actions.size)
        key(PadGestureRecognizer.Key.FORWARD_ALIAS, 1400)
        pad.confirm(1500)
        assertEquals(1, actions.size) // The old gesture must not act again in a new context.
        key(PadGestureRecognizer.Key.CONTACT, 1600)
        key(PadGestureRecognizer.Key.FORWARD, 1700)
        assertEquals(2, actions.size) // A fresh same-family swipe remains usable after cancellation.
    }
    private fun canceledSwipeThenFreshAlternate(
        first: PadGestureRecognizer.Key, next: PadGestureRecognizer.Key, pixels: Int,
        cancelInCallback: Boolean, classifyBeforeMarkerUp: Boolean,
    ) {
        val actions = mutableListOf<PadGestureRecognizer.Action>()
        lateinit var pad: PadGestureRecognizer
        pad = PadGestureRecognizer(300, 500) { action ->
            actions.add(action)
            if (cancelInCallback && actions.size == 1) pad.cancel(action.timeMs)
        }
        fun edge(key: PadGestureRecognizer.Key, phase: PadGestureRecognizer.Phase, at: Long,
                 down: Long = at, position: PointerPosition? = null) = pad.accept(
            PadGestureRecognizer.Event(key, phase, at, down, position = position), at)
        fun key(key: PadGestureRecognizer.Key, at: Long, up: Long = at + 22, position: PointerPosition? = null) {
            edge(key, PadGestureRecognizer.Phase.DOWN, at, position = position)
            edge(key, PadGestureRecognizer.Phase.UP, up, at)
        }
        val oldPoint = PointerPosition(10f, 20f, true)
        val newPoint = PointerPosition(30f, 40f, true)
        key(PadGestureRecognizer.Key.CONTACT, 1000, position = oldPoint)
        if (first == PadGestureRecognizer.Key.FORWARD || first == PadGestureRecognizer.Key.BACKWARD) {
            edge(first, PadGestureRecognizer.Phase.DOWN, 1100)
            edge(if (pixels > 0) PadGestureRecognizer.Key.FORWARD_TAIL else PadGestureRecognizer.Key.BACKWARD_TAIL,
                PadGestureRecognizer.Phase.DOWN, 1300)
        } else key(first, 1278, 1300)
        assertEquals(listOf(PadGestureRecognizer.Action.Scroll(pixels, oldPoint, 1300)), actions)
        if (!cancelInCallback) pad.cancel(1300)
        edge(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, 1400, position = newPoint)
        if (!classifyBeforeMarkerUp) edge(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, 1422, 1400)
        key(next, 1500)
        if (classifyBeforeMarkerUp) edge(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, 1540, 1400)
        pad.confirm(3000)
        assertEquals(listOf(PadGestureRecognizer.Action.Scroll(pixels, oldPoint, 1300),
            PadGestureRecognizer.Action.Scroll(pixels, newPoint, 1522)), actions)
        assertFalse(pad.hasWork)
    }
    @Test fun freshAliasAfterCancellationConsumesItsNewMarkerInsteadOfClicking() {
        for ((first, next, pixels) in listOf(
            Triple(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Key.FORWARD_ALIAS, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD, PadGestureRecognizer.Key.BACKWARD_ALIAS, -160))) {
            canceledSwipeThenFreshAlternate(first, next, pixels, false, false)
        }
    }
    @Test fun freshPrimaryAfterCancellationConsumesItsNewMarkerInsteadOfClicking() {
        for ((first, next, pixels) in listOf(
            Triple(PadGestureRecognizer.Key.FORWARD_ALIAS, PadGestureRecognizer.Key.FORWARD, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD_ALIAS, PadGestureRecognizer.Key.BACKWARD, -160))) {
            canceledSwipeThenFreshAlternate(first, next, pixels, false, false)
        }
    }
    @Test fun callbackCancellationAllowsFreshAlternateFamiliesAndInFlightMarkers() {
        for ((first, next, pixels) in listOf(
            Triple(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Key.FORWARD_ALIAS, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD, PadGestureRecognizer.Key.BACKWARD_ALIAS, -160),
            Triple(PadGestureRecognizer.Key.FORWARD_ALIAS, PadGestureRecognizer.Key.FORWARD, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD_ALIAS, PadGestureRecognizer.Key.BACKWARD, -160))) {
            for (beforeUp in listOf(false, true)) canceledSwipeThenFreshAlternate(first, next, pixels, true, beforeUp)
        }
    }
    @Test fun cancellationStillSuppressesLateAlternateReportsWithoutANewMarker() {
        for ((first, next, pixels) in listOf(
            Triple(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Key.FORWARD_ALIAS, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD, PadGestureRecognizer.Key.BACKWARD_ALIAS, -160),
            Triple(PadGestureRecognizer.Key.FORWARD_ALIAS, PadGestureRecognizer.Key.FORWARD, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD_ALIAS, PadGestureRecognizer.Key.BACKWARD, -160))) {
            val r = Replay(); val point = PointerPosition(10f, 20f, true)
            r.marker(1000, position = point); r.key(first, 1278, 1300)
            r.pad.cancel(1300)
            r.key(next, 1400)
            r.pad.confirm(3000)
            assertEquals(listOf(PadGestureRecognizer.Action.Scroll(pixels, point, 1300)), r.actions)
            assertFalse(r.pad.hasWork)
        }
    }
    @Test fun cancelingANewMarkerStillSuppressesTheRetiredSwipesCompanion() {
        for ((first, next, pixels) in listOf(
            Triple(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Key.FORWARD_ALIAS, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD, PadGestureRecognizer.Key.BACKWARD_ALIAS, -160),
            Triple(PadGestureRecognizer.Key.FORWARD_ALIAS, PadGestureRecognizer.Key.FORWARD, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD_ALIAS, PadGestureRecognizer.Key.BACKWARD, -160))) {
            for (markerReleased in listOf(false, true)) {
                val r = Replay(); val point = PointerPosition(10f, 20f, true)
                r.marker(1000, position = point); r.key(first, 1278, 1300)
                r.pad.cancel(1300)
                r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,
                    PadGestureRecognizer.Phase.DOWN, 1400, 1400), 1400)
                if (markerReleased) r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,
                    PadGestureRecognizer.Phase.UP, 1422, 1400), 1422)
                r.pad.cancel(1450)
                r.key(next, 1500)
                r.pad.confirm(3000)
                assertEquals(listOf(PadGestureRecognizer.Action.Scroll(pixels, point, 1300)), r.actions)
                assertFalse(r.pad.hasWork)
            }
        }
    }
    @Test fun distinctSummaryOnlyPairsRetainAllFourTouches() {
        val r = Replay(); r.key(PadGestureRecognizer.Key.DOUBLE, 1000); r.key(PadGestureRecognizer.Key.DOUBLE, 1200)
        r.pad.confirm(2000); assertEquals(4, r.taps().size)
    }
    @Test fun canceledRepeatedHeldReportEndsTheContact() {
        val r = Replay()
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1000, 1000), 1000)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.CANCEL, 1500, 1000, 1), 1500)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 1600, 1000), 1600)
        assertEquals(listOf(PadGestureRecognizer.Phase.DOWN, PadGestureRecognizer.Phase.CANCEL),
            r.actions.filterIsInstance<PadGestureRecognizer.Action.Contact>().map { it.event.phase })
        assertFalse(r.pad.hasWork)
    }
    @Test fun latePairSummaryDoesNotReplayAlreadyDeliveredRawSingles() {
        val r = Replay(); r.key(PadGestureRecognizer.Key.TAP, 1000); r.key(PadGestureRecognizer.Key.TAP, 1100)
        r.pad.confirm(1422); assertEquals(2, r.taps().size)
        r.key(PadGestureRecognizer.Key.DOUBLE, 1450); r.pad.confirm(2000)
        assertEquals(2, r.taps().size)
    }
    @Test fun delayedSwipeAliasCannotConsumeTheNextTapMarker() {
        val r = Replay(); r.marker(1000); r.key(PadGestureRecognizer.Key.FORWARD, 1100)
        r.marker(1200); r.key(PadGestureRecognizer.Key.FORWARD_ALIAS, 1250)
        r.pad.confirm(2500)
        assertEquals(1, r.taps().size)
        assertEquals(1, r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().size)
        assertEquals(1200L, r.taps().single().downTimeMs)
    }
    @Test fun documentedTwoDownSwipeSequenceNeedsNoFirstKeyUpAndEmitsOnce() {
        for ((first, tail, pixels) in listOf(
            Triple(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Key.FORWARD_TAIL, 160),
            Triple(PadGestureRecognizer.Key.BACKWARD, PadGestureRecognizer.Key.BACKWARD_TAIL, -160))) {
            val r = Replay(); r.marker(1000)
            r.pad.accept(PadGestureRecognizer.Event(first, PadGestureRecognizer.Phase.DOWN, 1100, 1100), 1100)
            r.pad.accept(PadGestureRecognizer.Event(tail, PadGestureRecognizer.Phase.DOWN, 1300, 1300), 1300)
            r.pad.accept(PadGestureRecognizer.Event(first, PadGestureRecognizer.Phase.UP, 1320, 1100), 1320)
            r.pad.confirm(2500)
            assertTrue(r.taps().isEmpty())
            assertEquals(listOf(pixels), r.actions.filterIsInstance<PadGestureRecognizer.Action.Scroll>().map { it.pixels })
        }
    }
    @Test fun missingDirectionalSequenceTailExpiresWithoutAPreliminaryTap() {
        val r = Replay(); r.marker(1000)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Phase.DOWN, 1100, 1100), 1100)
        assertEquals(1600L, r.pad.deadline)
        r.pad.confirm(3000)
        assertTrue(r.actions.isEmpty()); assertFalse(r.pad.hasWork)
    }
    @Test fun aNewContactInvalidatesIncompleteSwipeWithoutLosingTheNewTap() {
        val r = Replay(); r.marker(1000)
        r.pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.FORWARD, PadGestureRecognizer.Phase.DOWN, 1100, 1100), 1100)
        r.marker(1200); r.pad.confirm(3000)
        assertEquals(1200L, r.taps().single().downTimeMs)
        assertEquals(1, r.actions.size)
    }
}
