package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream

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

    @Test fun matchedWireNineCarriesOriginalIntentionsOnChannelTwentyThree() {
        assertEquals(10, Wire.VERSION); assertEquals(23, Wire.FUNCTION_INTENT)
        val output = ByteArrayOutputStream()
        val expected = listOf(FunctionIntentKind.ACTIVATE, FunctionIntentKind.ACTIVATE, FunctionIntentKind.REGISTER)
            .mapIndexed { n, kind -> FunctionIntent(kind, FunctionSource.GLASSES_NATIVE, n.toLong(), n.toLong(), context) }
        DataOutputStream(output).use { wire -> expected.forEach { Wire.writeFrame(wire, Frame(Wire.FUNCTION_INTENT, payload = it.bytes())) } }
        DataInputStream(output.toByteArray().inputStream()).use { wire ->
            assertEquals(expected, expected.map { val f = Wire.readFrame(wire); assertEquals(0L, f.stamp); FunctionIntent.from(f.payload) })
            assertEquals(-1, wire.read())
        }
        for (length in listOf(0, FunctionIntent.MIN_PAYLOAD_SIZE - 1, FunctionIntent.MAX_PAYLOAD_SIZE + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                Wire.writeFrame(DataOutputStream(ByteArrayOutputStream()), Frame(Wire.FUNCTION_INTENT, payload = ByteArray(length)))
            }
        }
    }

    @Test fun typedConsumersPreserveTheExactOriginalRouteAndDoNotInventAnAnchorAction() {
        val route = Any(); val consumers = FunctionIntentConsumers<Any>()
        val first = CapturedFunctionIntent(FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.PHONE_HID, 1, 1, context), route)
        assertFalse(consumers.dispatch(first))
        val seen = mutableListOf<CapturedFunctionIntent<Any>>()
        consumers.activate = seen::add; consumers.register = seen::add
        assertTrue(consumers.dispatch(first))
        val second = CapturedFunctionIntent(first.intent.copy(kind = FunctionIntentKind.REGISTER, gestureId = 2, sequence = 2), route)
        assertTrue(consumers.dispatch(second))
        assertSame(first, seen[0]); assertSame(second, seen[1]); assertTrue(seen.all { it.original === route })
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
