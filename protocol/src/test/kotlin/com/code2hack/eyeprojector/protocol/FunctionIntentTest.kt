package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test

class FunctionIntentTest {
    private val context = FunctionIntentContext(
        ReceiverUsability("owner", ReceiverIncarnation("receiver", 4), 7, 8, ReceiverUsabilityMode.PROJECTED),
        HeadPointerState(8, 20, true, true, 120f, 240f, Fit(20, 40, 240, 320), Geometry(480, 640)),
    )

    @Test fun bothEndpointsShareEveryTypedSourceAndIntentionWithOriginalContext() {
        for (source in FunctionSource.entries) for (kind in FunctionIntentKind.entries) {
            val original = FunctionIntent(kind, source, 91, 17, context)
            val payload = original.bytes()
            assertEquals(kind.ordinal, payload[0].toInt())
            assertEquals(source.ordinal, payload[1].toInt())
            assertEquals(original, FunctionIntent.from(payload))
        }
        val local = context.copy(pointer = HeadPointerState(9, 21, true, false, 25f, 50f, null, null))
        val registration = FunctionIntent(FunctionIntentKind.REGISTER, FunctionSource.GLASSES_NATIVE, 92, 18, local)
        assertEquals(registration, FunctionIntent.from(registration.bytes()))
    }

    @Test fun delayedIntentRetainsOriginalIncarnationAndLocalIdentityAfterSuccessorAppears() {
        val originalRoute = Any()
        var currentRoute = originalRoute
        var currentContext = context
        val deferred = CapturedFunctionIntent(
            FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.PHONE_HID, 91, 17, currentContext), currentRoute,
        )
        currentRoute = Any()
        currentContext = context.copy(usability = context.usability.copy(incarnation = ReceiverIncarnation("successor", 5)),
            pointer = context.pointer.copy(revision = 9, x = 300f))
        val decoded = FunctionIntent.from(deferred.intent.bytes())
        assertEquals(context, decoded.context)
        assertNotEquals(currentContext, decoded.context)
        assertSame(originalRoute, deferred.original)
        assertNotSame(currentRoute, deferred.original)
    }

    @Test fun twoActivationsHaveDistinctIdentityAndOrderEvenAtTheSamePointer() {
        val intents = listOf(91L, 92L).mapIndexed { index, gesture ->
            FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.PHONE_HID, gesture, index.toLong(), context)
        }
        assertEquals(intents, intents.map { FunctionIntent.from(it.bytes()) })
        assertNotEquals(intents[0].gestureId, intents[1].gestureId)
        assertTrue(intents[0].sequence < intents[1].sequence)
    }

    @Test fun clickAndHoldEdgesRetainExactOriginalRouteContactAndTarget() {
        val originalRoute = Any()
        val originalTarget = Tap(200, 400, Geometry(480, 640))
        val contact = OriginalContact(originalRoute, 88, originalTarget)
        for (edge in listOf(contact.down(), contact.up(), contact.cancel())) {
            assertEquals(88L, edge.contactId)
            assertSame(originalTarget, edge.target)
            assertEquals(edge, Touch.from(edge.bytes()))
        }
        assertSame(originalRoute, contact.context)
        assertEquals(Touch.Phase.UP, contact.up().phase)
        assertEquals(Touch.Phase.CANCEL, contact.cancel().phase)
    }
}
