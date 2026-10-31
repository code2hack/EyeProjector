package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import com.code2hack.eyeprojector.directrg.FunctionGestureRecognizer.Report
import com.code2hack.eyeprojector.protocol.FunctionIntentKind.*

class FunctionGestureRecognizerTest {
    private class Rig(edgeOnly: Boolean = false) {
        var now = 1000L
        var context: Any? = "projection-1"
        var hover = "original-hover"
        val gestures = mutableListOf<FunctionGestureRecognizer.Gesture>()
        val kinds get() = gestures.map { it.kind }
        data class Timer(val at: Long, val run: () -> Unit, var canceled: Boolean = false)
        val timers = mutableListOf<Timer>()
        var onGesture: () -> Unit = {}
        val recognizer = FunctionGestureRecognizer({ now }, { context }, { at, run ->
            val timer = Timer(at, run); timers.add(timer)
            val cancel: () -> Unit = { timer.canceled = true }; cancel
        }, { gestures.add(it); onGesture() }, edgeOnly = edgeOnly, capture = { Pair(it, hover) })
        fun report(report: Report, after: Long = 0) { now += after; recognizer.accept(report) }
        fun press() { report(Report.DOWN); report(Report.UP, 30) }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { !it.canceled && it.at <= end }.minByOrNull { it.at } ?: break
                next.canceled = true; now = maxOf(now, next.at); next.run()
            }
            now = end
        }
    }

    @Test fun everyExplicitShortRemainsAnActivationIncludingDoubleTripleAndLongerBursts() {
        for (edgeOnly in listOf(false, true)) for (count in listOf(1, 2, 3, 8)) {
            val r = Rig(edgeOnly)
            repeat(count) { r.press(); r.advance(50) }
            r.advance(500)
            assertEquals(List(count) { ACTIVATE }, r.kinds)
            assertEquals((0L until count.toLong()).toList(), r.gestures.map { it.gestureId })
            assertEquals((0L until count.toLong()).toList(), r.gestures.map { it.sequence })
        }
    }

    @Test fun summariesDescribeObservedEdgesRatherThanAddingActivations() {
        for (count in 1..3) {
            val r = Rig(); repeat(count) { r.press() }
            val summary = listOf(Report.CLICK, Report.DOUBLE, Report.TRIPLE)[count - 1]
            repeat(3) { r.report(summary); r.report(Report.CLICK); r.report(Report.UP) }
            r.advance(500)
            assertEquals(List(count) { ACTIVATE }, r.kinds)
        }
    }

    @Test fun summaryOnlyCountsExpandOnceRegardlessOfOrder() {
        for (reports in listOf(
            listOf(Report.CLICK, Report.DOUBLE, Report.TRIPLE),
            listOf(Report.TRIPLE, Report.DOUBLE, Report.CLICK),
            listOf(Report.DOUBLE, Report.CLICK, Report.TRIPLE, Report.DOUBLE),
        )) {
            val r = Rig(); reports.forEach { r.report(it, 20) }; r.advance(500)
            assertEquals(List(3) { ACTIVATE }, r.kinds)
            assertEquals(3, r.gestures.map { it.gestureId }.distinct().size)
        }
    }

    @Test fun repeatedDownAndDuplicateUpClickStayOneOriginal() {
        val r = Rig(); r.report(Report.DOWN); repeat(3) { r.report(Report.DOWN, 20) }
        r.report(Report.UP); r.report(Report.CLICK); r.report(Report.UP); r.report(Report.CLICK)
        r.advance(500); assertEquals(listOf(ACTIVATE), r.kinds)
    }

    @Test fun summaryBeforeEdgesAndMissingOrReorderedUpUseTheSameOriginal() {
        val before = Rig(); before.report(Report.CLICK); before.press(); before.advance(500)
        assertEquals(listOf(ACTIVATE), before.kinds)
        for ((report, count) in listOf(Report.CLICK to 1, Report.DOUBLE to 2, Report.TRIPLE to 3)) {
            val r = Rig(); r.report(Report.DOWN); r.report(report, 20); r.advance(500)
            r.report(Report.UP); r.advance(500); assertEquals(List(count) { ACTIVATE }, r.kinds)
        }
    }

    @Test fun nativeQuietBoundaryAndExtensionRemainTheExistingArbitration() {
        val r = Rig(); r.press(); r.advance(499); assertTrue(r.kinds.isEmpty())
        r.report(Report.DOUBLE); r.advance(499); assertTrue(r.kinds.isEmpty())
        r.advance(1); assertEquals(listOf(ACTIVATE, ACTIVATE), r.kinds)
    }

    @Test fun explicitLongReplacesItsPendingShortAndConsumesReleaseAndSummaries() {
        val r = Rig(); r.press(); r.report(Report.LONG, 499)
        r.report(Report.CLICK); r.report(Report.UP); r.advance(1000)
        assertEquals(listOf(REGISTER), r.kinds)
    }

    @Test fun aPreviousShortIsDeliveredBeforeTheNextOriginalLong() {
        val r = Rig(); r.press(); r.report(Report.DOWN); r.report(Report.LONG, 40)
        r.report(Report.UP); r.advance(1000)
        assertEquals(listOf(ACTIVATE, REGISTER), r.kinds)
    }

    @Test fun heldLongRegistersOnceAndReleaseNeverActivates() {
        for (edgeOnly in listOf(false, true)) {
            val r = Rig(edgeOnly); r.report(Report.DOWN); r.advance(799); assertTrue(r.kinds.isEmpty())
            r.advance(1); assertEquals(listOf(REGISTER), r.kinds)
            r.report(Report.DOWN, 1000); r.report(Report.CLICK); r.report(Report.UP); r.advance(1000)
            assertEquals(listOf(REGISTER), r.kinds)
            r.press(); r.advance(500); assertEquals(listOf(REGISTER, ACTIVATE), r.kinds)
        }
    }

    @Test fun delayedLongTimerAndReleaseUseActualDurationWithoutSwallowingNextPress() {
        for (edgeOnly in listOf(false, true)) {
            val r = Rig(edgeOnly); r.report(Report.DOWN); r.report(Report.UP, 800)
            assertEquals(listOf(REGISTER), r.kinds)
            r.press(); r.advance(500); assertEquals(listOf(REGISTER, ACTIVATE), r.kinds)
        }
    }

    @Test fun contextAndHoverAreCapturedAtEachOriginalDownRatherThanCompletion() {
        val r = Rig(); r.report(Report.DOWN); r.hover = "moved"; r.report(Report.UP, 30)
        r.press(); r.hover = "latest"; r.advance(500)
        assertEquals(listOf("projection-1" to "original-hover", "projection-1" to "moved"), r.gestures.map { it.original })
    }

    @Test fun contextReplacementAndCancellationRetirePendingInputButFreshEdgesWork() {
        for (next in listOf("panel", "projection-2", null)) {
            val r = Rig(); r.press(); r.context = next; r.advance(500); assertTrue(r.kinds.isEmpty())
        }
        val r = Rig(); r.report(Report.DOWN); r.recognizer.cancel()
        r.report(Report.TRIPLE); r.report(Report.UP); r.advance(100); assertTrue(r.kinds.isEmpty())
        r.press(); r.advance(500); assertEquals(listOf(ACTIVATE), r.kinds)
    }

    @Test fun callbacksRetireBeforeReentrantCancellationAndContextReplacement() {
        for (replace in listOf(false, true)) {
            val r = Rig(); r.onGesture = { if (replace) r.context = "panel"; r.recognizer.cancel() }
            r.report(Report.TRIPLE); r.advance(500)
            r.report(Report.CLICK); r.report(Report.UP); r.advance(500)
            assertEquals(listOf(ACTIVATE), r.kinds)
        }
    }

    @Test fun canceledReplacedAndEarlyCallbacksCannotActInAnotherOriginal() {
        val r = Rig(); r.press(); val stale = r.timers.toList(); r.recognizer.cancel(); r.press()
        stale.forEach { it.run() }; r.timers.last().run(); assertTrue(r.kinds.isEmpty())
        r.advance(500); assertEquals(listOf(ACTIVATE), r.kinds)
    }

    @Test fun identityFreeRetirementBoundaryRemainsAModeledLimitation() {
        val r = Rig(); r.report(Report.CLICK); r.advance(700)
        r.report(Report.CLICK); r.advance(500); assertEquals(listOf(ACTIVATE), r.kinds)
        r.report(Report.CLICK); r.advance(500); assertEquals(listOf(ACTIVATE, ACTIVATE), r.kinds)
    }

    @Test fun lateLongCannotUndoAnAlreadyCommittedIdentityFreeSummary() {
        val r = Rig(); r.report(Report.CLICK); r.advance(800); r.report(Report.LONG); r.advance(500)
        assertEquals(listOf(ACTIVATE), r.kinds) // Not proof of physical once-only firmware delivery.
    }

    @Test fun sharedSourceIdentityDoesNotRestartWhenAGrammarIsRecreated() {
        val identity = FunctionGestureRecognizer.Identity()
        val observed = mutableListOf<FunctionGestureRecognizer.Gesture>()
        repeat(2) {
            val recognizer = FunctionGestureRecognizer({ 1000 }, { "same-incarnation" }, { _, _ -> {} },
                observed::add, edgeOnly = true, identity = identity)
            recognizer.accept(Report.DOWN); recognizer.accept(Report.UP)
        }
        assertEquals(listOf(0L, 1L), observed.map { it.gestureId })
        assertEquals(listOf(0L, 1L), observed.map { it.sequence })
    }

    @Test fun unavailableContextNeverStartsAGesture() {
        val r = Rig(); r.context = null; r.report(Report.TRIPLE); r.advance(1000); assertTrue(r.kinds.isEmpty())
    }
}
