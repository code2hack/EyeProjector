package com.code2hack.eyeprojector.directhost.anchor.position

import com.code2hack.eyeprojector.protocol.AnchorInteractionMode
import com.code2hack.eyeprojector.protocol.AnchorPosition
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnchorPositionStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app = "example.应用"
    private val points = listOf(AnchorPosition(10.25, 20.5), AnchorPosition(300.0, 600.75))
    private fun directory() = File(temporary.root, "positions")
    private fun disk() = FileAnchorPositionStorage(directory())

    @Test fun absentListDoesNotCreateFilesAndLegacyBytesRemainUnconverted() {
        assertTrue(disk().read(app).isEmpty())
        assertFalse(directory().exists())
        val legacy = File(temporary.root, "projection_app_anchors/legacy.anchor")
        legacy.parentFile.mkdirs(); val bytes = byteArrayOf(0, 0, 0, 1, 3, 7, 9)
        legacy.writeBytes(bytes)
        val owner = CoordinateAnchorController(disk())
        assertTrue(owner.dispatch(AnchorModeAction.AppChanged(app)).state.positions.isEmpty())
        owner.dispatch(AnchorModeAction.CycleMode); owner.dispatch(AnchorModeAction.Tap(points[0]))
        assertArrayEquals(bytes, legacy.readBytes())
        assertEquals(listOf(points[0]), disk().read(app))
    }

    @Test fun eachAddAndLifoRemovalIsImmediatelyReadableByFreshOwnerAndOnlyPositionsRestart() {
        val owner = CoordinateAnchorController(disk())
        owner.dispatch(AnchorModeAction.AppChanged(app)); owner.dispatch(AnchorModeAction.CycleMode)
        points.forEachIndexed { index, point ->
            assertTrue(owner.dispatch(AnchorModeAction.Tap(point)).positionsSaved)
            val fresh = CoordinateAnchorController(disk()).dispatch(AnchorModeAction.AppChanged(app)).state
            assertEquals(points.take(index + 1), fresh.positions)
            assertEquals(AnchorInteractionMode.NORMAL, fresh.mode); assertNull(fresh.selectedIndex)
        }
        assertTrue(owner.dispatch(AnchorModeAction.Back).positionsSaved)
        assertEquals(points.take(1), disk().read(app))
        assertTrue(owner.dispatch(AnchorModeAction.Back).positionsSaved)
        assertTrue(disk().read(app).isEmpty())
        assertFalse(owner.dispatch(AnchorModeAction.Back).positionsSaved)
        assertEquals(1, directory().listFiles()!!.size)
    }

    @Test fun packageKeysAndCreationOrderAreIndependentAndInterruptedTemporaryFileIsIgnored() {
        disk().write(app, points + points[0]); disk().write("other", points.reversed())
        File(directory(), "positions-interrupted.pending").writeBytes(byteArrayOf(3, 9))
        assertEquals(points + points[0], disk().read(app))
        assertEquals(points.reversed(), disk().read("other"))
        disk().write("other", emptyList())
        assertEquals(points + points[0], disk().read(app))
        assertTrue(disk().read("other").isEmpty())
    }

    @Test fun failedSettingsWritesPreserveExactPreviousRecordAndReloadedList() {
        val storage = disk(); storage.write(app, points)
        val savedFile = directory().listFiles()!!.single(); val originalBytes = savedFile.readBytes()
        val failing = object : AnchorPositionStorage {
            override fun read(packageName: String) = storage.read(packageName)
            override fun write(packageName: String, positions: List<AnchorPosition>) { throw IOException("disk full") }
        }
        val owner = CoordinateAnchorController(failing)
        owner.dispatch(AnchorModeAction.AppChanged(app)); owner.dispatch(AnchorModeAction.CycleMode)
        owner.dispatch(AnchorModeAction.CycleMode)
        for (action in listOf(AnchorModeAction.Tap(points[0]), AnchorModeAction.Back)) {
            val result = owner.dispatch(action)
            assertNotNull(result.failure); assertFalse(result.positionsSaved); assertTrue(result.consumed)
            assertEquals(points, result.state.positions)
            assertArrayEquals(originalBytes, savedFile.readBytes())
            assertEquals(points, disk().read(app))
        }
    }

    @Test fun actualFilesystemFailureReportsNoSaveAndCleansPendingFile() {
        disk().write(app, points)
        val target = directory().listFiles()!!.single()
        assertTrue(target.delete()); assertTrue(target.mkdir())
        assertThrows(IOException::class.java) { disk().write(app, points.take(1)) }
        assertTrue(target.isDirectory)
        assertTrue(directory().listFiles()!!.none { it.name.endsWith(".pending") })
    }

    @Test fun corruptRecordOnForegroundSwitchPreservesBytesAndNeverReusesPreviousAppSelection() {
        disk().write("old", points); disk().write(app, points.reversed())
        val target = directory().listFiles()!!.single { AnchorPositionCodec.decode(it.readBytes()).packageName == app }
        val corrupt = target.readBytes().dropLast(1).toByteArray(); target.writeBytes(corrupt)
        val owner = CoordinateAnchorController(disk())
        owner.dispatch(AnchorModeAction.AppChanged("old")); owner.dispatch(AnchorModeAction.CycleMode)
        val result = owner.dispatch(AnchorModeAction.AppChanged(app))
        assertEquals(app, result.state.packageName); assertNotNull(result.failure)
        assertEquals(AnchorInteractionMode.NORMAL, result.state.mode)
        assertTrue(result.state.positions.isEmpty()); assertNull(result.state.selectedPosition)
        assertNotNull(owner.dispatch(AnchorModeAction.CycleMode).failure)
        assertArrayEquals(corrupt, target.readBytes())
        assertEquals(points, disk().read("old"))
    }

    @Test fun codecRoundTripsUnicodeEmptyAndDuplicateListsWithoutPersistingModeOrSelection() {
        for (positions in listOf(emptyList(), points, points + points[0])) {
            val value = AppAnchorPositions(app, positions)
            assertEquals(value, AnchorPositionCodec.decode(AnchorPositionCodec.encode(value)))
        }
    }

    @Test fun codecRejectsUnknownLegacyTruncatedTrailingNegativeHugeAndNonfiniteRecords() {
        val valid = AnchorPositionCodec.encode(AppAnchorPositions(app, points))
        for (length in valid.indices) assertThrows(Exception::class.java) { AnchorPositionCodec.decode(valid.copyOf(length)) }
        for (bad in listOf(valid + 0.toByte(), valid.clone().also { it[0] = 0 }, valid.clone().also { it[7] = 2 },
            valid.clone().also { ByteBuffer.wrap(it).putInt(it.size - 32 - 4, -1) },
            valid.clone().also { ByteBuffer.wrap(it).putInt(it.size - 32 - 4, Int.MAX_VALUE) },
            valid.clone().also { ByteBuffer.wrap(it).putDouble(it.size - 32, Double.POSITIVE_INFINITY) })) {
            assertThrows(IllegalArgumentException::class.java) { AnchorPositionCodec.decode(bad) }
        }
    }

    @Test fun concurrentDiskReaderSeesOnlyWholeCommittedLists() {
        val old = points; val next = points.reversed() + points[0]
        disk().write(app, old)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit {
                repeat(250) { assertTrue(disk().read(app) in listOf(old, next)) }
            }
            repeat(30) { disk().write(app, if (it % 2 == 0) next else old) }
            read.get(5, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
}
