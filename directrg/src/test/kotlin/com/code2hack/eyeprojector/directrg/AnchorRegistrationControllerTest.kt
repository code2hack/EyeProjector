package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AnchorRegistrationControllerTest {
    private val source = Any()
    private val incarnation = ReceiverIncarnation("receiver", 1)
    private val geometry = Geometry(500, 400)
    private val fit = Fit(73, 31, 1000, 800)
    private val original = FunctionIntentContext(
        ReceiverUsability("owner", incarnation, 3, 4, ReceiverUsabilityMode.PROJECTED),
        HeadPointerState(4, 2, true, true, 200f, 100f, fit, geometry))
    private val highlight = AnchorRegistrationHighlight(60.0, 20.0, 120.0, 60.0)
    private fun controller(sent: MutableList<Triple<Any, Long, AnchorRegistrationAction>>) =
        AnchorRegistrationController<Any> { source, id, action -> sent.add(Triple(source, id, action)); true }

    @Test fun mapsActualVideoHighlightUsingOriginalFitAndDoesNotSendAnyInputOnShow() {
        val sent = mutableListOf<Triple<Any, Long, AnchorRegistrationAction>>()
        val controller = controller(sent)
        assertTrue(controller.show(source, 1, original, highlight))
        assertEquals(AnchorRegistrationHighlight(193.0, 71.0, 313.0, 151.0), controller.offer!!.highlight)
        assertTrue(sent.isEmpty())
        assertTrue(controller.current(source, incarnation, geometry, 4, fit))
    }

    @Test fun survivesOnlyItsExplicitChooserTransitionAndPreservesOriginalContext() {
        val controller = controller(mutableListOf())
        controller.show(source, 1, original, highlight)
        assertTrue(controller.enteredChooser(source, incarnation, geometry, 5))
        assertTrue(controller.current(source, incarnation, geometry, 5, fit))
        assertEquals(original, controller.offer!!.original)
        assertFalse(controller.current(source, incarnation, geometry, 4, fit))
        assertFalse(controller.current(source, incarnation, geometry, 6, fit))
        assertFalse(controller.current(Any(), incarnation, geometry, 5, fit))
        assertFalse(controller.current(source, ReceiverIncarnation("replacement", 2), geometry, 5, fit))
        assertFalse(controller.current(source, incarnation, Geometry(400, 500), 5, fit))
        assertFalse(controller.current(source, incarnation, geometry, 5, fit.copy(left = 74)))
    }

    @Test fun secondTransitionCannotAdoptNewInputRevisionOrSession() {
        val sent = mutableListOf<Triple<Any, Long, AnchorRegistrationAction>>()
        val controller = controller(sent)
        controller.show(source, 1, original, highlight)
        controller.enteredChooser(source, incarnation, geometry, 5)
        assertFalse(controller.enteredChooser(source, incarnation, geometry, 6))
        assertFalse(controller.active)
        assertEquals(listOf(Triple(source, 1L, AnchorRegistrationAction.CANCEL)), sent)
        controller.show(source, 2, original, highlight)
        assertFalse(controller.enteredChooser(Any(), incarnation, geometry, 5))
        assertFalse(controller.active)
        assertSame(source, sent.last().first)
    }

    @Test fun clickAndHoldAlwaysRequireAChoiceAndConsumeDuplicateLocalTapsWhileWaiting() {
        for (action in listOf(AnchorRegistrationAction.CLICK, AnchorRegistrationAction.HOLD)) {
            val sent = mutableListOf<Triple<Any, Long, AnchorRegistrationAction>>()
            val controller = controller(sent)
            controller.show(source, 1, original, highlight)
            assertEquals(0, sent.size)
            assertTrue(controller.choose(action))
            assertTrue(controller.active)
            assertTrue(controller.choose(action))
            assertEquals(listOf(Triple(source, 1L, action)), sent)
            assertTrue(controller.completed(source, 1))
            assertFalse(controller.active)
            assertFalse(controller.choose(action))
        }
    }

    @Test fun cancelAndBackDiscardOriginalWithoutDismissingSuccessorOnLateReply() {
        val sent = mutableListOf<Triple<Any, Long, AnchorRegistrationAction>>()
        val controller = controller(sent)
        controller.show(source, 1, original, highlight)
        assertTrue(controller.cancel())
        assertFalse(controller.cancel())
        val replacement = Any()
        controller.show(replacement, 2, original, highlight)
        assertFalse(controller.completed(source, 1))
        assertFalse(controller.completed(source, 2))
        assertTrue(controller.active)
        assertTrue(controller.choose(AnchorRegistrationAction.CANCEL))
        assertFalse(controller.active)
        assertEquals(listOf(Triple(source, 1L, AnchorRegistrationAction.CANCEL),
            Triple(replacement, 2L, AnchorRegistrationAction.CANCEL)), sent)
    }

    @Test fun failedChoiceSubmissionClosesChooserWithoutManufacturingASaveConfirmation() {
        val controller = AnchorRegistrationController<Any> { _, _, _ -> false }
        controller.show(source, 1, original, highlight)
        assertTrue(controller.choose(AnchorRegistrationAction.HOLD))
        assertFalse(controller.active)
        assertFalse(controller.completed(source, 1))
    }

    @Test fun duplicateOfferPreservesOriginalPendingChoiceAndDoesNotCancelIt() {
        val sent = mutableListOf<Triple<Any, Long, AnchorRegistrationAction>>()
        val controller = controller(sent)
        controller.show(source, 1, original, highlight)
        controller.enteredChooser(source, incarnation, geometry, 5)
        controller.choose(AnchorRegistrationAction.HOLD)
        controller.show(source, 1, original, highlight)
        assertEquals(5L, controller.offer!!.chooserRevision)
        assertTrue(controller.offer!!.choosing)
        assertEquals(listOf(Triple(source, 1L, AnchorRegistrationAction.HOLD)), sent)
    }
}
