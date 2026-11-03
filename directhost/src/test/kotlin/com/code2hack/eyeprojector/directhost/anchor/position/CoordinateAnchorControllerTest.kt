package com.code2hack.eyeprojector.directhost.anchor.position

import com.code2hack.eyeprojector.protocol.AnchorInteractionMode.*
import com.code2hack.eyeprojector.protocol.AnchorPosition
import com.code2hack.eyeprojector.protocol.CoordinateAnchorState
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CoordinateAnchorControllerTest {
    private val app = "example.app"
    private val other = "example.other"
    private val first = AnchorPosition(12.5, 32.0)
    private val second = AnchorPosition(100.0, 200.25)
    private val third = AnchorPosition(400.0, 600.0)
    private class Memory : AnchorPositionStorage {
        val values = mutableMapOf<String, List<AnchorPosition>>()
        val writes = mutableListOf<AppAnchorPositions>()
        var failWrite = false
        var failRead = false
        override fun read(packageName: String): List<AnchorPosition> {
            if (failRead) throw IOException("read failed")
            return values[packageName] ?: emptyList()
        }
        override fun write(packageName: String, positions: List<AnchorPosition>) {
            if (failWrite) throw IOException("write failed")
            values[packageName] = positions.toList()
            writes.add(AppAnchorPositions(packageName, positions.toList()))
        }
    }

    private fun settings(owner: CoordinateAnchorController, packageName: String = app) {
        owner.dispatch(AnchorModeAction.AppChanged(packageName))
        while (owner.snapshot().mode != ANCHOR_SETTINGS) owner.dispatch(AnchorModeAction.CycleMode)
    }

    @Test fun emptyCycleSkipsAnchorAndEmptyBackAndSettingsScrollDoNothing() {
        val disk = Memory(); val owner = CoordinateAnchorController(disk)
        owner.dispatch(AnchorModeAction.AppChanged(app))
        assertEquals(NORMAL, owner.snapshot().mode)
        assertEquals(ANCHOR_SETTINGS, owner.dispatch(AnchorModeAction.CycleMode).state.mode)
        val before = owner.snapshot()
        for (action in listOf(AnchorModeAction.Back, AnchorModeAction.ScrollDown, AnchorModeAction.ScrollUp)) {
            val result = owner.dispatch(action)
            assertTrue(result.consumed); assertFalse(result.positionsSaved); assertNull(result.failure)
            assertEquals(before, result.state)
        }
        assertTrue(disk.writes.isEmpty())
        assertEquals(NORMAL, owner.dispatch(AnchorModeAction.CycleMode).state.mode)
    }

    @Test fun tapsAppendIncludingDuplicatesAndEachSuccessfulBackImmediatelyRemovesNewest() {
        val disk = Memory(); val owner = CoordinateAnchorController(disk); settings(owner)
        val expected = listOf(first, second, first)
        for ((index, point) in expected.withIndex()) {
            val result = owner.dispatch(AnchorModeAction.Tap(point))
            assertTrue(result.consumed); assertTrue(result.positionsSaved); assertNull(result.failure)
            assertEquals(expected.take(index + 1), result.state.positions)
            assertEquals(result.state.positions, disk.values[app])
            assertEquals(index + 1, disk.writes.size)
        }
        for (remaining in 2 downTo 0) {
            val result = owner.dispatch(AnchorModeAction.Back)
            assertTrue(result.positionsSaved); assertTrue(result.consumed)
            assertEquals(expected.take(remaining), disk.values[app])
        }
        assertFalse(owner.dispatch(AnchorModeAction.Back).positionsSaved)
        assertEquals(6, disk.writes.size)
    }

    @Test fun enteringAlwaysSelectsFirstAndDownNextUpPreviousWrapInCreationOrder() {
        val disk = Memory(); disk.values[app] = listOf(first, second, third)
        val owner = CoordinateAnchorController(disk); owner.dispatch(AnchorModeAction.AppChanged(app))
        assertEquals(first, owner.dispatch(AnchorModeAction.CycleMode).state.selectedPosition)
        for (point in listOf(second, third, first)) {
            val result = owner.dispatch(AnchorModeAction.ScrollDown)
            assertTrue(result.consumed); assertEquals(point, result.state.selectedPosition)
        }
        for (point in listOf(third, second, first, third)) {
            assertEquals(point, owner.dispatch(AnchorModeAction.ScrollUp).state.selectedPosition)
        }
        assertEquals(ANCHOR_SETTINGS, owner.dispatch(AnchorModeAction.CycleMode).state.mode)
        assertNull(owner.snapshot().selectedIndex)
        assertEquals(NORMAL, owner.dispatch(AnchorModeAction.CycleMode).state.mode)
        assertEquals(first, owner.dispatch(AnchorModeAction.CycleMode).state.selectedPosition)
        assertTrue(disk.writes.isEmpty())
    }

    @Test fun singleAnchorSelectionWrapsToItselfWithoutSaving() {
        val disk = Memory(); disk.values[app] = listOf(first)
        val owner = CoordinateAnchorController(disk); owner.dispatch(AnchorModeAction.AppChanged(app))
        owner.dispatch(AnchorModeAction.CycleMode)
        repeat(4) {
            assertEquals(first, owner.dispatch(AnchorModeAction.ScrollUp).state.selectedPosition)
            assertEquals(first, owner.dispatch(AnchorModeAction.ScrollDown).state.selectedPosition)
        }
        assertTrue(disk.writes.isEmpty())
    }

    @Test fun normalAndAnchorTapBackRemainOrdinaryAndNormalScrollRemainsOrdinary() {
        val disk = Memory(); disk.values[app] = listOf(first)
        val owner = CoordinateAnchorController(disk); owner.dispatch(AnchorModeAction.AppChanged(app))
        for (action in listOf(AnchorModeAction.Tap(second), AnchorModeAction.Back,
            AnchorModeAction.ScrollDown, AnchorModeAction.ScrollUp)) assertFalse(owner.dispatch(action).consumed)
        owner.dispatch(AnchorModeAction.CycleMode)
        assertFalse(owner.dispatch(AnchorModeAction.Tap(second)).consumed)
        assertFalse(owner.dispatch(AnchorModeAction.Back).consumed)
        assertEquals(listOf(first), owner.snapshot().positions)
        assertTrue(disk.writes.isEmpty())
    }

    @Test fun appSwitchRestoresEachModeAndSelectionButHomeIsAlwaysNormalAndCannotEdit() {
        val disk = Memory(); disk.values[app] = listOf(first, second)
        val owner = CoordinateAnchorController(disk); owner.dispatch(AnchorModeAction.AppChanged(app))
        owner.dispatch(AnchorModeAction.CycleMode); owner.dispatch(AnchorModeAction.ScrollDown)
        val a = owner.snapshot()
        settings(owner, other); owner.dispatch(AnchorModeAction.Tap(third)); val b = owner.snapshot()
        assertEquals(a, owner.dispatch(AnchorModeAction.AppChanged(app)).state)
        assertEquals(a, owner.dispatch(AnchorModeAction.AppChanged(app)).state) // same app is not re-entry
        assertEquals(CoordinateAnchorState(), owner.dispatch(AnchorModeAction.AppChanged(null)).state)
        assertEquals(CoordinateAnchorState(), owner.dispatch(AnchorModeAction.CycleMode).state)
        assertFalse(owner.dispatch(AnchorModeAction.Tap(first)).consumed)
        assertFalse(owner.dispatch(AnchorModeAction.Back).consumed)
        assertEquals(b, owner.dispatch(AnchorModeAction.AppChanged(other)).state)
        assertEquals(a, owner.dispatch(AnchorModeAction.AppChanged(app)).state)
        assertEquals(listOf(third), disk.values[other])
        assertEquals(listOf(first, second), disk.values[app])
    }

    @Test fun restartLoadsOnlyPositionsAndEveryAppStartsNormal() {
        val disk = Memory(); val old = CoordinateAnchorController(disk)
        settings(old); old.dispatch(AnchorModeAction.Tap(first)); old.dispatch(AnchorModeAction.CycleMode)
        old.dispatch(AnchorModeAction.CycleMode)
        settings(old, other); old.dispatch(AnchorModeAction.Tap(second))
        val restarted = CoordinateAnchorController(disk)
        assertEquals(CoordinateAnchorState(), restarted.snapshot())
        for ((name, point) in listOf(app to first, other to second)) {
            assertEquals(CoordinateAnchorState(name, positions = listOf(point)),
                restarted.dispatch(AnchorModeAction.AppChanged(name)).state)
        }
    }

    @Test fun failedAddOrRemoveIsConsumedReportsErrorAndDoesNotClaimSavedOrChangeState() {
        val disk = Memory(); val owner = CoordinateAnchorController(disk); settings(owner)
        owner.dispatch(AnchorModeAction.Tap(first)); val before = owner.snapshot(); disk.failWrite = true
        for (action in listOf(AnchorModeAction.Tap(second), AnchorModeAction.Back)) {
            val result = owner.dispatch(action)
            assertTrue(result.consumed); assertFalse(result.positionsSaved); assertNotNull(result.failure)
            assertEquals(before, result.state); assertEquals(before, owner.snapshot())
            assertEquals(listOf(first), disk.values[app])
        }
        assertEquals(listOf(first), CoordinateAnchorController(disk).dispatch(AnchorModeAction.AppChanged(app)).state.positions)
        disk.failWrite = false
        assertTrue(owner.dispatch(AnchorModeAction.Tap(second)).positionsSaved)
        assertEquals(listOf(first, second), disk.values[app])
    }

    @Test fun failedAppLoadReportsNewContextWithoutReusingOldAnchorsOrOverwritingUnreadData() {
        val disk = Memory(); val owner = CoordinateAnchorController(disk); settings(owner)
        owner.dispatch(AnchorModeAction.Tap(first))
        disk.values[other] = listOf(second); disk.failRead = true
        val failed = owner.dispatch(AnchorModeAction.AppChanged(other))
        assertNotNull(failed.failure)
        assertFalse(failed.positionsSaved)
        assertEquals(CoordinateAnchorState(other), failed.state)
        assertEquals(failed.state, owner.snapshot())
        assertNotNull(owner.dispatch(AnchorModeAction.CycleMode).failure)
        assertFalse(owner.dispatch(AnchorModeAction.Tap(third)).consumed)
        assertEquals(CoordinateAnchorState(other), owner.snapshot())
        assertEquals(listOf(second), disk.values[other])
        assertEquals(1, disk.writes.size)
        disk.failRead = false
        val recovered = owner.dispatch(AnchorModeAction.CycleMode)
        assertNull(recovered.failure)
        assertEquals(second, recovered.state.selectedPosition)
        assertEquals(listOf(first), owner.dispatch(AnchorModeAction.AppChanged(app)).state.positions)
    }

    @Test fun storageAndReturnedListAliasesCannotChangeTheOwnedList() {
        val points = mutableListOf(first, second)
        val disk = Memory(); disk.values[app] = points
        val owner = CoordinateAnchorController(disk)
        val result = owner.dispatch(AnchorModeAction.AppChanged(app)); points.clear()
        (result.state.positions as MutableList).clear()
        assertEquals(listOf(first, second), owner.snapshot().positions)
    }

    @Test fun concurrentSettingsTapsAreSerializedWithoutLostSaves() {
        val disk = Memory(); val owner = CoordinateAnchorController(disk); settings(owner)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 40).map { index -> executor.submit<AnchorModeResult> {
                owner.dispatch(AnchorModeAction.Tap(AnchorPosition(index.toDouble(), 10.0)))
            } }.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(results.all { it.positionsSaved && it.consumed && it.failure == null })
            assertEquals(40, owner.snapshot().positions.size)
            assertEquals(40, owner.snapshot().positions.toSet().size)
            assertEquals((1..40).toList(), disk.writes.map { it.positions.size })
            assertEquals(disk.values[app], owner.snapshot().positions)
        } finally { executor.shutdownNow() }
    }
}
