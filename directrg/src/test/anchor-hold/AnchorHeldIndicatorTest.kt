package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AnchorHeldIndicatorTest {
    private val fact = ReceiverUsability("phone", ReceiverIncarnation("glasses", 1), 10, 3, ReceiverUsabilityMode.PROJECTED)
    private fun intent(current: ReceiverUsability = fact) = FunctionIntent(FunctionIntentKind.ACTIVATE,
        FunctionSource.GLASSES_NATIVE, 1, 1, FunctionIntentContext(current,
            HeadPointerState(3, 1, true, true, 10f, 20f, Fit(0, 0, 480, 640), Geometry(480, 640))))

    @Test fun persistentUntilOriginalReleaseOrCancelAndNoStaleResurrection() {
        val rendered = mutableListOf<Boolean>()
        val indicator = AnchorHeldIndicator { rendered.add(it) }
        val session = Any()
        indicator.bind(session, fact)
        assertTrue(indicator.accept(session, intent(), 1, true))
        repeat(10) { indicator.bind(session, fact.copy(revision = 11L + it)) }
        assertTrue(indicator.visible)
        assertEquals(listOf(true), rendered)
        assertTrue(indicator.accept(session, intent(), 3, false))
        indicator.accept(session, intent(), 2, true)
        indicator.accept(session, intent(), 3, true)
        assertFalse(indicator.visible)
        assertEquals(listOf(true, false), rendered)
    }

    @Test fun changedOriginalSessionAndInputContextClearWithoutAdoptingOldState() {
        for (replacement in listOf("session", "epoch", "incarnation", "input", "mode", "close")) {
            val indicator = AnchorHeldIndicator {}
            val original = Any()
            indicator.bind(original, fact)
            indicator.accept(original, intent(), 1, true)
            val next = if (replacement == "session") Any() else original
            val context = when (replacement) {
                "epoch" -> fact.copy(ownerEpoch = "replacement")
                "incarnation" -> fact.copy(incarnation = ReceiverIncarnation("glasses", 2))
                "input" -> fact.copy(inputRevision = 4)
                "mode" -> fact.copy(mode = ReceiverUsabilityMode.LOCAL_PANEL)
                "close" -> null
                else -> fact
            }
            indicator.bind(if (replacement == "close") null else next, context)
            assertFalse(replacement, indicator.visible)
            assertFalse(replacement, indicator.accept(original, intent(), 2, true))
        }
    }

    @Test fun localRetirementClearsBeforePhoneCancellationAndRevisionSurvivesContextChanges() {
        val indicator = AnchorHeldIndicator {}
        val session = Any()
        indicator.bind(session, fact)
        indicator.accept(session, intent(), 8, true)
        indicator.clear()
        assertFalse(indicator.accept(session, intent(), 9, true))
        val next = fact.copy(inputRevision = 4)
        indicator.bind(session, next)
        indicator.accept(session, intent(next), 7, true)
        assertFalse(indicator.visible)
        indicator.accept(session, intent(next), 10, true)
        assertTrue(indicator.visible)
    }
}
