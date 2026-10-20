package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LayoutScaleTest {
    @Test fun inverseDimensionsChangeAvailableDpAtTheFixedBaselineDensity() {
        assertEquals(Geometry(480, 640), LayoutScale.BASELINE)
        assertEquals(160, LayoutScale.DPI)
        assertEquals(Geometry(960, 1280), LayoutScale.target(0.5))
        assertEquals(Geometry(480, 640), LayoutScale.target(1.0))
        assertEquals(Geometry(384, 512), LayoutScale.target(1.25))
        assertEquals(Geometry(240, 320), LayoutScale.target(2.0))
        assertEquals(1.25, LayoutScale.applied(Geometry(384, 512)), 0.0)
    }

    @Test fun appliedFeedbackUsesObservedDimensionsAfterQuantizationOrBackendAdjustment() {
        val requested = 1.1
        val target = checkNotNull(LayoutScale.target(requested))
        assertEquals(Geometry(436, 582), target)
        assertEquals(480.0 / 436, LayoutScale.appliedX(target), 0.0)
        assertEquals(640.0 / 582, LayoutScale.appliedY(target), 0.0)
        assertEquals(640.0 / 582, LayoutScale.applied(target), 0.0)
        assertTrue(abs(requested - LayoutScale.applied(target)) > 0.0001)

        val observed = Geometry(432, 576)
        assertEquals(480.0 / 432, LayoutScale.applied(observed), 0.0)
        assertTrue(LayoutScale.applied(observed) != LayoutScale.applied(target))
    }

    @Test fun landscapeUsesTheRotatedBaselineAndPreservesEffectiveScale() {
        val portrait = checkNotNull(LayoutScale.target(1.1))
        val landscape = checkNotNull(LayoutScale.target(1.1, landscape = true))
        assertEquals(Geometry(portrait.height, portrait.width), landscape)
        assertEquals(LayoutScale.appliedY(portrait), LayoutScale.appliedX(landscape), 0.0)
        assertEquals(LayoutScale.appliedX(portrait), LayoutScale.appliedY(landscape), 0.0)
        assertEquals(LayoutScale.applied(portrait), LayoutScale.applied(landscape), 0.0)
    }

    @Test fun mappingUsesOnlyTheExistingGeometryRangeAndRejectsInvalidRequests() {
        assertEquals(Geometry(3072, 4096), LayoutScale.target(0.15625))
        assertEquals(Geometry(4096, 3072), LayoutScale.target(0.15625, landscape = true))
        assertEquals(Geometry(1, 1), LayoutScale.target(960.0))
        for (scale in listOf(0.1562, 961.0, 0.0, -1.0, Double.MIN_VALUE,
            Double.MAX_VALUE, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull("Scale $scale must not map outside existing geometry bounds", LayoutScale.target(scale))
            assertNull(LayoutScale.target(scale, landscape = true))
        }
    }

    @Test fun requestIsAnExactDoubleAndLeavesSemanticRejectionToTheSession() {
        for (scale in listOf(1.25, 0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val payload = ScaleRequest(scale).bytes()
            assertEquals(8, payload.size)
            assertEquals(scale, DataInputStream(payload.inputStream()).readDouble(), 0.0)
            assertEquals(scale, ScaleRequest.from(payload).scale, 0.0)
        }
        for (length in listOf(0, 7, 9)) {
            assertThrows(IllegalArgumentException::class.java) { ScaleRequest.from(ByteArray(length)) }
        }
    }

    @Test fun allStatesRoundTripRequestedObservedTargetAndCapabilitySeparately() {
        for ((index, status) in ScaleStatus.entries.withIndex()) {
            val state = ScaleState(
                requestId = 0x0102030405060708L + index,
                requested = 1.25,
                observed = if (index % 2 == 0) Geometry(480, 640) else null,
                target = if (index % 3 == 0) null else Geometry(384, 512),
                status = status,
                clientResize = index % 2 == 1,
            )
            assertEquals(40, state.bytes().size)
            assertEquals(state, ScaleState.from(state.bytes()))
        }
        val rejected = ScaleState(7, Double.POSITIVE_INFINITY, Geometry(480, 640), null, ScaleStatus.REJECTED, true)
        assertEquals(rejected, ScaleState.from(rejected.bytes()))
    }

    @Test fun stateUsesTheFixedFortyByteFieldOrderAndZeroPairsForAbsentGeometry() {
        val state = ScaleState(9, 1.25, null, Geometry(384, 512), ScaleStatus.PENDING, true)
        val input = DataInputStream(state.bytes().inputStream())
        assertEquals(9L, input.readLong())
        assertEquals(1.25, input.readDouble(), 0.0)
        assertEquals(0, input.readInt()); assertEquals(0, input.readInt())
        assertEquals(384, input.readInt()); assertEquals(512, input.readInt())
        assertEquals(ScaleStatus.PENDING.ordinal, input.readInt())
        assertEquals(1, input.readInt())
        assertEquals(-1, input.read())
    }

    @Test fun malformedStateLengthsEnumsCapabilitiesAndGeometryAreRejected() {
        for (length in listOf(0, 39, 41)) {
            assertThrows(IllegalArgumentException::class.java) { ScaleState.from(ByteArray(length)) }
        }
        val valid = ScaleState(1, 1.0, Geometry(480, 640), Geometry(384, 512), ScaleStatus.READY, false).bytes()
        fun rejectsInt(offset: Int, value: Int) {
            val malformed = valid.copyOf()
            ByteBuffer.wrap(malformed).putInt(offset, value)
            assertThrows(IllegalArgumentException::class.java) { ScaleState.from(malformed) }
        }
        for (ordinal in listOf(-1, ScaleStatus.entries.size)) rejectsInt(32, ordinal)
        for (capability in listOf(-1, 2)) rejectsInt(36, capability)
        for (offset in listOf(16, 20, 24, 28)) {
            for (dimension in listOf(-1, 0, 4097)) rejectsInt(offset, dimension)
        }
    }
}
