package com.code2hack.eyeprojector.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class CoordinateAnchorsTest {
    private val points = listOf(AnchorPosition(10.25, 20.5), AnchorPosition(300.0, 500.0))

    @Test fun homeAndEveryAppModeRoundTripWithExactCreationOrder() {
        val states = listOf(CoordinateAnchorState()) + AnchorInteractionMode.entries.map {
            CoordinateAnchorState("example.应用", it, points, if (it == AnchorInteractionMode.ANCHOR) 1 else null)
        }
        states.forEach { assertEquals(it, CoordinateAnchorState.from(it.bytes())) }
        assertEquals(points[1], states[2].selectedPosition)
        assertNull(states[1].selectedPosition)
    }

    @Test fun coordinatesAreAbsoluteAndNotRelocatedOrRestrictedToAChosenGeometry() {
        val outside = AnchorPosition(-1.25, 100_000.0)
        val state = CoordinateAnchorState("example.app", positions = listOf(outside))
        assertEquals(outside, CoordinateAnchorState.from(state.bytes()).positions.single())
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { AnchorPosition(value, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { AnchorPosition(1.0, value) }
        }
    }

    @Test fun selectionMustDescribeAnExistingAnchorAndHomeAlwaysNormal() {
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState(mode = AnchorInteractionMode.ANCHOR_SETTINGS) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState(positions = points) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState("app", AnchorInteractionMode.ANCHOR) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState("app", AnchorInteractionMode.ANCHOR, points, 2) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState("app", positions = points, selectedIndex = 0) }
    }

    private fun payload(present: Int = 0, mode: Int = 0, selected: Int = -1, count: Int = 0,
        point: AnchorPosition? = null) = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(1); output.writeByte(present)
            if (present == 1) output.writeUTF("app")
            output.writeByte(mode); output.writeInt(selected); output.writeInt(count)
            point?.let { output.writeDouble(it.x); output.writeDouble(it.y) }
        }
    }.toByteArray()

    @Test fun malformedCountsModesPresenceAndTrailingBytesAreRejectedWithoutAllocatingFromCount() {
        for (bytes in listOf(payload(count = -1), payload(count = Int.MAX_VALUE), payload(mode = 255),
            payload(present = 2), payload(selected = -2), payload() + 0.toByte(),
            payload(present = 1, mode = 1, selected = 1, count = 1, point = points[0]))) {
            assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState.from(bytes) }
        }
    }

    @Test fun truncatedUnknownVersionAndNonfiniteWireRecordsCannotBecomeState() {
        val encoded = CoordinateAnchorState("app", positions = points).bytes()
        for (length in encoded.indices) {
            assertThrows(Exception::class.java) { CoordinateAnchorState.from(encoded.copyOf(length)) }
        }
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState.from(encoded.clone().also { it[3] = 2 }) }
        val nonfinite = payload(present = 1, count = 1, point = points[0]).also {
            java.nio.ByteBuffer.wrap(it).putDouble(it.size - 16, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) { CoordinateAnchorState.from(nonfinite) }
    }
}
