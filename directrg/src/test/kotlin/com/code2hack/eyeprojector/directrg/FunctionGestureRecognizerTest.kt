package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import com.code2hack.eyeprojector.directrg.FunctionGestureRecognizer.Action
import com.code2hack.eyeprojector.directrg.FunctionGestureRecognizer.Report

class FunctionGestureRecognizerTest {
    private class Rig {
        var now = 1000L
        var context: Any? = "projection-1"
        val actions = mutableListOf<Action>()
        data class Timer(val at: Long, val run: () -> Unit, var canceled: Boolean = false)
        val timers = mutableListOf<Timer>()
        var onAction: () -> Unit = {}
        val recognizer = FunctionGestureRecognizer({ now }, { context }, { at, run ->
            val timer = Timer(at, run); timers.add(timer)
            val cancel: () -> Unit = { timer.canceled = true }; cancel
        }, { actions.add(it); onAction() })
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

    @Test fun completeEdgesProduceOnlyFinalSingleDoubleTriple() {
        for (count in 1..3) {
            val r = Rig()
            repeat(count) { r.press(); r.advance(100); assertTrue(r.actions.isEmpty()) }
            r.advance(400)
            assertEquals(listOf(listOf(Action.SINGLE, Action.DOUBLE, Action.TRIPLE)[count - 1]), r.actions)
        }
    }

    @Test fun summariesRaiseRatherThanAddAndAreOrderIndependent() {
        for (reports in listOf(
            listOf(Report.CLICK, Report.DOUBLE, Report.TRIPLE),
            listOf(Report.TRIPLE, Report.DOUBLE, Report.CLICK),
            listOf(Report.DOUBLE, Report.CLICK, Report.TRIPLE, Report.DOUBLE),
        )) {
            val r = Rig(); r.press()
            reports.forEach { r.report(it, 20) }
            assertTrue(r.actions.isEmpty()); r.advance(500)
            assertEquals(listOf(Action.TRIPLE), r.actions)
        }
    }

    @Test fun repeatedDownAndDuplicateUpClickStayOnePress() {
        val r = Rig()
        r.report(Report.DOWN); repeat(3) { r.report(Report.DOWN, 20) }
        r.report(Report.UP); r.report(Report.CLICK); r.report(Report.UP); r.report(Report.CLICK)
        r.advance(500); assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun summaryOnlySingleDoubleTriple() {
        for ((report, action) in listOf(Report.UP to Action.SINGLE, Report.CLICK to Action.SINGLE,
            Report.DOUBLE to Action.DOUBLE, Report.TRIPLE to Action.TRIPLE)) {
            val r = Rig(); r.report(report); r.report(report, 10); r.advance(500)
            assertEquals(listOf(action), r.actions)
        }
    }

    @Test fun summaryBeforeReleaseIsNotAnotherPress() {
        val r = Rig(); r.report(Report.CLICK); r.report(Report.DOWN, 10); r.report(Report.UP, 20)
        r.advance(500); assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun completedSummaryClosesMissingOrReorderedUp() {
        for ((report, expected) in listOf(Report.CLICK to Action.SINGLE, Report.DOUBLE to Action.DOUBLE, Report.TRIPLE to Action.TRIPLE)) {
            val r = Rig(); r.report(Report.DOWN); r.report(report, 20); r.advance(500)
            r.report(Report.UP); r.advance(500); assertEquals(listOf(expected), r.actions)
        }
    }

    @Test fun quietBoundaryAndExtension() {
        val r = Rig(); r.press(); r.advance(499); assertTrue(r.actions.isEmpty())
        r.report(Report.DOUBLE); r.advance(499); assertTrue(r.actions.isEmpty())
        r.advance(1); assertEquals(listOf(Action.DOUBLE), r.actions)
        r.advance(1); assertEquals(1, r.actions.size)
    }

    @Test fun explicitLongRetiresClickAndRelease() {
        val r = Rig(); r.press(); r.report(Report.LONG, 499)
        r.report(Report.CLICK); r.report(Report.UP); r.advance(1000)
        assertEquals(listOf(Action.LONG), r.actions)
    }

    @Test fun heldLongHasNoConstituentActionAndNoRepeatSequence() {
        val r = Rig(); r.report(Report.DOWN); r.advance(799); assertTrue(r.actions.isEmpty())
        r.advance(1); assertEquals(listOf(Action.LONG), r.actions)
        r.report(Report.DOWN, 1000); r.report(Report.CLICK); r.report(Report.UP); r.advance(1000)
        assertEquals(listOf(Action.LONG), r.actions)
        r.press(); r.advance(500); assertEquals(listOf(Action.LONG, Action.SINGLE), r.actions)
    }

    @Test fun longReleaseWithoutTimerDeliveryDoesNotSwallowNextPress() {
        val r = Rig(); r.report(Report.DOWN); r.report(Report.UP, 800)
        assertEquals(listOf(Action.LONG), r.actions)
        r.press(); r.advance(500); assertEquals(listOf(Action.LONG, Action.SINGLE), r.actions)
    }

    @Test fun freshEdgesStartNextSequenceImmediatelyAfterDispatch() {
        val r = Rig(); r.press(); r.advance(500)
        r.press(); r.advance(500); assertEquals(listOf(Action.SINGLE, Action.SINGLE), r.actions)
    }

    @Test fun duplicateAfterTransitionDoesNotExitOrGoHome() {
        for ((summary, expected) in listOf(Report.TRIPLE to Action.TRIPLE, Report.DOUBLE to Action.DOUBLE)) {
            val r = Rig(); r.onAction = { r.context = "local-panel"; r.recognizer.cancel() }
            r.report(summary); r.advance(500)
            listOf(summary, Report.CLICK, Report.UP).forEach { r.report(it, 50) }
            r.advance(500); assertEquals(listOf(expected), r.actions)
        }
    }

    @Test fun changedContextOrSessionCancelsPendingDispatch() {
        for (next in listOf("panel", "projection-2", null)) {
            val r = Rig(); r.press(); r.context = next; r.advance(500)
            assertTrue(r.actions.isEmpty())
        }
    }

    @Test fun cancellationRetiresSummariesButAllowsFreshEdgesWithoutMissingRelease() {
        val r = Rig(); r.report(Report.DOWN); r.recognizer.cancel()
        r.report(Report.TRIPLE); r.report(Report.UP); r.advance(100); assertTrue(r.actions.isEmpty())
        r.press(); r.advance(500); assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun contextChangeAtReportRetiresOldSequence() {
        val r = Rig(); r.press(); r.context = "panel"; r.report(Report.TRIPLE, 10); r.advance(500)
        assertTrue(r.actions.isEmpty())
        r.press(); r.advance(500); assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun canceledAndReplacedSchedulerCallbacksCannotAct() {
        val r = Rig(); r.press(); val stale = r.timers.toList()
        r.recognizer.cancel(); r.press(); stale.forEach { it.run() }
        assertTrue(r.actions.isEmpty()); r.advance(500); assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun earlySchedulerCallbackRearmsWithoutPrematureAction() {
        val r = Rig(); r.press(); r.timers.last().run()
        assertTrue(r.actions.isEmpty()); r.advance(500); assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun summaryOnlyNextSequenceAfterQuietInterval() {
        val r = Rig(); r.report(Report.CLICK); r.advance(1000); r.report(Report.DOUBLE); r.advance(500)
        assertEquals(listOf(Action.SINGLE, Action.DOUBLE), r.actions)
    }

    @Test fun identityFreeSummaryRetirementBoundaryIsExplicit() {
        val r = Rig(); r.report(Report.CLICK); r.advance(700)
        // A fresh summary-only click is indistinguishable from a late duplicate here.
        r.report(Report.CLICK); r.advance(500)
        assertEquals(listOf(Action.SINGLE), r.actions)
        r.report(Report.CLICK); r.advance(500)
        assertEquals(listOf(Action.SINGLE, Action.SINGLE), r.actions)
    }

    @Test fun lateLongCannotUndoAnAlreadyCommittedSummary() {
        val r = Rig(); r.report(Report.CLICK); r.advance(800); r.report(Report.LONG)
        r.advance(500)
        // Explicit firmware-evidence limitation, not a physical once-only/long-consume pass.
        assertEquals(listOf(Action.SINGLE), r.actions)
    }

    @Test fun unavailableContextDoesNotStartGesture() {
        val r = Rig(); r.context = null; r.report(Report.TRIPLE); r.advance(1000)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun allContextualOperationsAndLongNoOp() {
        for (projection in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val router = FunctionNavigation(
                projectedDouble = { calls.add("projectedDouble") },
                localDouble = { calls.add("localDouble") },
                back = { calls.add("singleBack") },
                sleep = { calls.add("tripleSleep") },
            )
            Action.entries.forEach { router.dispatch(it, projection) }
            assertEquals(if (projection) listOf("singleBack", "projectedDouble", "tripleSleep")
                else listOf("singleBack", "localDouble", "tripleSleep"), calls)
        }
    }
}
