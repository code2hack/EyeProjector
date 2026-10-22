package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.Fact
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.Step

class HidDecisionTraceTest {
    private fun event(sink: (String) -> Unit) = HidDecisionTrace.Event(
        deviceId = -7, keyCode = 83, scanCode = 204, action = 1, repeat = 2,
        canceled = true, downTime = 1000, eventTime = 1020, sink = sink,
    )

    private fun fields(line: String): Map<String, String> {
        val tokens = line.split(' ')
        val result = tokens.associate { token ->
            val pair = token.split('=', limit = 2)
            assertEquals("Every field has a fixed key and one value", 2, pair.size)
            pair[0] to pair[1]
        }
        assertEquals("Field keys must not repeat", tokens.size, result.size)
        return result
    }

    @Test fun recordHasOnlyTheFixedNumericAndEnumVocabularyWithExplicitUnobservedOptionalFacts() {
        val lines = mutableListOf<String>()
        event { lines.add(it) }.record(Step.ARRIVAL)
        assertEquals(1, lines.size)
        assertEquals(linkedMapOf(
            "device" to "-7", "key" to "83", "scan" to "204", "action" to "1",
            "repeat" to "2", "canceled" to "true", "down" to "1000", "time" to "1020",
            "step" to "ARRIVAL", "fact" to "UNOBSERVED", "target" to "UNOBSERVED",
            "available" to "UNOBSERVED", "x" to "UNOBSERVED", "y" to "UNOBSERVED",
            "left" to "UNOBSERVED", "top" to "UNOBSERVED", "right" to "UNOBSERVED",
            "bottom" to "UNOBSERVED", "offsetX" to "UNOBSERVED", "offsetY" to "UNOBSERVED",
            "generationBefore" to "UNOBSERVED", "generationAfter" to "UNOBSERVED",
        ), fields(lines.single()))
    }

    @Test fun recordInputsHaveNoArbitraryTextThrowableOrDeviceMetadataParameter() {
        val methods = HidDecisionTrace.Event::class.java.declaredMethods.filter { it.name == "record" }
        assertEquals(1, methods.size)
        val parameters = HidDecisionTrace.Event::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() } +
            methods.flatMap { it.parameterTypes.toList() }
        assertFalse(parameters.contains(String::class.java))
        assertFalse(parameters.any { Throwable::class.java.isAssignableFrom(it) })
        assertFalse(parameters.contains(HidBinding::class.java))
        assertFalse(parameters.contains(HidButton::class.java))
        assertFalse(parameters.contains(HidDeviceIdentity::class.java))
    }

    @Test fun eachStepFactAndTargetFormatsItsFixedEnumNameWithoutFreeText() {
        val lines = mutableListOf<String>()
        val trace = event { lines.add(it) }
        Step.entries.forEach { trace.record(it) }
        assertEquals(Step.entries.map { it.name }, lines.map { fields(it).getValue("step") })
        assertTrue(lines.all { fields(it).getValue("fact") == "UNOBSERVED" })
        lines.clear()
        Fact.entries.forEach { trace.record(Step.INPUT_TARGET, it) }
        assertEquals(Fact.entries.map { it.name }, lines.map { fields(it).getValue("fact") })
        lines.clear()
        HidAction.entries.forEach { trace.record(Step.HIT_RESULT, target = it) }
        assertEquals(HidAction.entries.map { it.name }, lines.map { fields(it).getValue("target") })
    }

    @Test fun nonfiniteCoordinatesBecomeUnobservedWhileFiniteValuesAndAvailabilityRemainActual() {
        val lines = mutableListOf<String>()
        val trace = event { lines.add(it) }
        trace.record(Step.HOVER_POINT, point = PointerPosition(Float.NaN, 17.25f, true))
        trace.record(Step.HOVER_POINT, point = PointerPosition(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, false))
        trace.record(Step.HOVER_POINT, point = PointerPosition(-0.0f, Float.MAX_VALUE, false))
        val first = fields(lines[0])
        assertEquals("UNOBSERVED", first["x"])
        assertEquals("17.25", first["y"])
        assertEquals("true", first["available"])
        val second = fields(lines[1])
        assertEquals("UNOBSERVED", second["x"])
        assertEquals("UNOBSERVED", second["y"])
        assertEquals("false", second["available"])
        val third = fields(lines[2])
        assertEquals((-0.0f).toString(), third["x"])
        assertEquals(Float.MAX_VALUE.toString(), third["y"])
        assertEquals("false", third["available"])
        assertTrue(lines.none { it.contains("NaN") || it.contains("Infinity") })
    }

    @Test fun absentOptionalGeometryAndGenerationSentinelStayDistinctFromObservedZeroOrNegativeValues() {
        val lines = mutableListOf<String>()
        val trace = event { lines.add(it) }
        trace.record(Step.HOVER_GENERATION_CHANGED, before = Long.MIN_VALUE, after = 0)
        trace.record(Step.HOVER_GENERATION_CHANGED, before = -1, after = Long.MAX_VALUE)
        val first = fields(lines[0])
        assertEquals("UNOBSERVED", first["generationBefore"])
        assertEquals("0", first["generationAfter"])
        for (name in listOf("left", "top", "right", "bottom", "offsetX", "offsetY")) {
            assertEquals("UNOBSERVED", first[name])
        }
        val second = fields(lines[1])
        assertEquals("-1", second["generationBefore"])
        assertEquals(Long.MAX_VALUE.toString(), second["generationAfter"])
    }

    @Test fun repeatedObservationsHaveOneSinkCallEachAndKeepTheImmutablePerEventKeyTuple() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val trace = event { first.add(it) }
        repeat(3) { trace.record(Step.INPUT_REPEAT) }
        assertEquals(3, first.size)
        assertEquals(1, first.distinct().size)
        val other = HidDecisionTrace.Event(8, 96, 304, 0, 0, false, 2000, 2001) { second.add(it) }
        other.record(Step.ARRIVAL)
        assertFalse(HidDecisionTrace.observed(trace, Step.INPUT_NORMAL, false))
        assertEquals(4, first.size)
        assertEquals(1, second.size)
        val firstTuple = fields(first[0]).filterKeys { it in tupleFields }
        assertTrue(first.all { fields(it).filterKeys { key -> key in tupleFields } == firstTuple })
        assertEquals("-7", firstTuple["device"])
        assertEquals("8", fields(second.single())["device"])
        assertEquals("NO", fields(first.last())["fact"])
    }

    @Test fun throwingSinkCannotChangeObservedPredicateResultsOrEscapeRecord() {
        for (failure in listOf(IllegalStateException("Private sink detail"), AssertionError("Private sink detail"))) {
            var calls = 0
            val trace = event { calls++; throw failure }
            trace.record(Step.ARRIVAL)
            assertFalse(HidDecisionTrace.observed(trace, Step.ADAPTER_INPUT, false))
            assertTrue(HidDecisionTrace.observed(trace, Step.HIT_RESULT, true, HidAction.FUNCTION))
            assertEquals(3, calls)
        }
        assertFalse(HidDecisionTrace.observed(null, Step.HIT_RESULT, false))
        assertTrue(HidDecisionTrace.observed(null, Step.HIT_RESULT, true))
    }

    @Test fun diagnosticCreationFailuresAreContainedAndSuccessfulCaptureReturnsTheExactEvent() {
        for (failure in listOf(IllegalStateException("Private read detail"), AssertionError("Private read detail"))) {
            var reads = 0
            assertNull(HidDecisionTrace.capture { reads++; throw failure })
            assertEquals(1, reads)
        }
        val trace = event { fail("Creating an observation must not emit it") }
        assertSame(trace, HidDecisionTrace.capture { trace })
        assertNull(HidDecisionTrace.capture { null })
    }

    private companion object {
        val tupleFields = setOf("device", "key", "scan", "action", "repeat", "canceled", "down", "time")
    }
}
