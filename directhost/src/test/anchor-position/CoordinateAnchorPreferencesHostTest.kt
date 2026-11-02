package com.code2hack.eyeprojector.directhost.anchor.position

import android.content.Context
import com.code2hack.eyeprojector.protocol.AnchorPosition
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoordinateAnchorPreferencesHostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bothRoutesUseSameApplicationOwnerAndDistinctCoordinateDirectory() {
        val application = Context(temporary.root)
        val activity = object : Context(File(temporary.root, "unused")) {
            override val applicationContext: Context get() = application
            override val filesDir: File get() = throw AssertionError("Activity retained")
        }
        val native = CoordinateAnchorPreferences.open(activity)
        val hid = CoordinateAnchorPreferences.open(application)
        assertSame(native, hid)
        native.dispatch(AnchorModeAction.AppChanged("example.app"))
        hid.dispatch(AnchorModeAction.CycleMode)
        val point = AnchorPosition(123.5, 245.0)
        assertTrue(native.dispatch(AnchorModeAction.Tap(point)).positionsSaved)
        assertEquals(listOf(point), hid.snapshot().positions)
        assertEquals(listOf(point), FileAnchorPositionStorage(File(temporary.root, "projection_anchor_positions")).read("example.app"))
        assertFalse(File(temporary.root, "projection_app_anchors").exists())
    }
}
