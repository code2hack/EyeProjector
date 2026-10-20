package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class LiveScaleTest {
    @Test fun pendingControlWriteDoesNotBecomeAppliedUntilBackendGeometryArrives() {
        val writes = mutableListOf<Geometry>()
        val states = mutableListOf<ScaleState>()
        val scale = LiveScale(writes::add, states::add)
        assertEquals(ScaleStatus.UNAVAILABLE, scale.request(1, 1.25).status)
        assertTrue(writes.isEmpty())
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        assertEquals(ScaleStatus.READY, scale.state().status)
        val pending = scale.request(2, 1.3)
        assertEquals(ScaleStatus.PENDING, pending.status)
        assertEquals(Geometry(480, 640), pending.observed)
        assertEquals(listOf(Geometry(369, 492)), writes)
        // Codec alignment can alter the dimensions independently; report what stock actually emits.
        scale.observe(ScrcpyRecord.Session(Geometry(368, 488), true))
        val result = scale.state()
        assertEquals(ScaleStatus.OBSERVED, result.status)
        assertEquals(1.3, result.requested, 0.0)
        assertEquals(Geometry(368, 488), result.observed)
        assertEquals(480.0 / 368, LayoutScale.applied(result.observed!!), 1e-12)
        assertEquals(2L, result.requestId)
    }

    @Test fun rapidRequestsUseStockDebouncingAndFeedbackAlwaysReportsActualIntermediateGeometry() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        scale.request(1, 1.25)
        scale.request(2, 1.5)
        scale.request(3, 2.0)
        assertEquals(listOf(Geometry(384, 512), Geometry(320, 427), Geometry(240, 320)), writes)
        scale.observe(ScrcpyRecord.Session(Geometry(384, 512), true))
        assertEquals(3L, scale.state().requestId)
        assertEquals(2.0, scale.state().requested, 0.0)
        assertEquals(1.25, LayoutScale.applied(scale.state().observed!!), 0.0)
        scale.observe(ScrcpyRecord.Session(Geometry(240, 320), true))
        assertEquals(ScaleStatus.OBSERVED, scale.state().status)
        assertEquals(2.0, LayoutScale.applied(scale.state().observed!!), 0.0)
    }

    @Test fun roundedNoOpCannotBlockLaterRequestsAndReturningToCurrentSizeCancelsPendingResize() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        scale.request(1, .999)
        assertEquals(Geometry(480, 641), writes.last())
        // The codec may align this back to480x640 and never emit another session record.
        scale.request(2, 1.25)
        assertEquals(Geometry(384, 512), writes.last())
        scale.request(3, 1.0)
        assertEquals(Geometry(480, 640), writes.last())
        assertEquals(3, writes.size)
        assertEquals(1.0, LayoutScale.applied(scale.state().observed!!), 0.0)
        assertEquals(ScaleStatus.PENDING, scale.state().status)
    }

    @Test fun invalidRequestRetainsPriorObservedAndPendingWork() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        scale.request(1, 1.25)
        val before = scale.state()
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, .01, 2000.0)) {
            val rejected = scale.request(9, invalid)
            assertEquals(ScaleStatus.REJECTED, rejected.status)
            assertEquals(before.observed, rejected.observed)
            assertEquals(before, scale.state())
        }
        assertEquals(1, writes.size)
        scale.observe(ScrcpyRecord.Session(Geometry(384, 512), true))
        assertEquals(1L, scale.state().requestId)
        assertEquals(ScaleStatus.OBSERVED, scale.state().status)
    }

    @Test fun rotationUpdatesObservedCoordinatesWithoutConfirmingAnOutstandingResize() {
        val writes = mutableListOf<Geometry>()
        val scale = LiveScale(writes::add, {})
        scale.observe(ScrcpyRecord.Session(Geometry(640, 480), false))
        scale.request(1, 1.25)
        assertEquals(listOf(Geometry(512, 384)), writes)
        scale.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        assertEquals(ScaleStatus.INTERRUPTED, scale.state().status)
        assertEquals(Geometry(480, 640), scale.state().observed)
        assertFalse(scale.state().clientResize)
        scale.request(2, 1.25)
        assertEquals(listOf(Geometry(512, 384), Geometry(384, 512)), writes)
    }
}
