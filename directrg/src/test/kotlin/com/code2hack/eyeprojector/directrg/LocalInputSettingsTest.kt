package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class LocalInputSettingsTest {
    private fun settings(values: MutableMap<String, String>) = LocalInputSettings(values::get) { k, v -> values[k] = v; true }

    @Test fun scalarMigratesToBothAxesAndAllSettingsSurviveReload() {
        val values = mutableMapOf("pointer.tenths" to "37", "edge.pixels-per-second" to "530")
        val first = settings(values)
        assertEquals(3.7, first.pointerXGain, 0.0)
        assertEquals(3.7, first.pointerYGain, 0.0)
        assertTrue(first.headScrollEnabled) // Preserve existing head scrolling on upgrade.
        assertTrue(first.stepPointerX(1)); assertTrue(first.stepPointerY(-1))
        assertTrue(first.stepScroll(1)); assertTrue(first.setHeadScroll(false))
        val loaded = settings(values)
        assertEquals(3.8, loaded.pointerXGain, 0.0)
        assertEquals(3.6, loaded.pointerYGain, 0.0)
        assertEquals(540, loaded.scrollSpeed)
        assertFalse(loaded.headScrollEnabled)
        assertNull(loaded.error)
    }

    @Test fun interruptedMigrationRetainsSavedAxisAndRetriesMissingAxis() {
        val values = mutableMapOf("pointer.tenths" to "37")
        val first = LocalInputSettings(values::get) { k, v ->
            if (k == "pointer.y.tenths") false else { values[k] = v; true }
        }
        assertNotNull(first.error)
        assertEquals(3.7, first.pointerYGain, 0.0)
        assertTrue(first.stepPointerX(1))
        val loaded = settings(values)
        assertEquals(3.8, loaded.pointerXGain, 0.0)
        assertEquals(3.7, loaded.pointerYGain, 0.0)
        assertEquals("38", values["pointer.x.tenths"])
        assertEquals("37", values["pointer.y.tenths"])
        assertNull(loaded.error)
    }

    @Test fun validAxesTakePrecedenceAndInvalidValuesUseBoundedDefaults() {
        val values = mutableMapOf("pointer.tenths" to "60", "pointer.x.tenths" to "1", "pointer.y.tenths" to "42")
        val migrated = settings(values)
        assertEquals(.1, migrated.pointerXGain, 0.0)
        assertEquals(4.2, migrated.pointerYGain, 0.0)
        for (legacy in listOf("garbage", "0", "61", "2147483648")) {
            val invalid = mutableMapOf("pointer.tenths" to legacy, "pointer.x.tenths" to "-1",
                "pointer.y.tenths" to "6.0", "edge.pixels-per-second" to "245", "edge.enabled" to "maybe")
            val loaded = settings(invalid)
            assertEquals(2.0, loaded.pointerXGain, 0.0)
            assertEquals(2.0, loaded.pointerYGain, 0.0)
            assertEquals(240, loaded.scrollSpeed)
            assertTrue(loaded.headScrollEnabled)
            assertEquals("20", invalid["pointer.x.tenths"])
            assertEquals("20", invalid["pointer.y.tenths"])
            assertEquals("240", invalid["edge.pixels-per-second"])
            assertEquals("true", invalid["edge.enabled"])
        }
    }

    @Test fun stepsKeepIndependentAxesAndScrollWithinTheirExistingBounds() {
        val model = settings(mutableMapOf())
        repeat(100) { model.stepPointerX(-1); model.stepPointerY(1); model.stepScroll(-1) }
        assertEquals(.1, model.pointerXGain, 0.0)
        assertEquals(6.0, model.pointerYGain, 0.0)
        assertEquals(10, model.scrollSpeed)
        repeat(130) { model.stepPointerX(1); model.stepPointerY(-1); model.stepScroll(1) }
        assertEquals(6.0, model.pointerXGain, 0.0)
        assertEquals(.1, model.pointerYGain, 0.0)
        assertEquals(1200, model.scrollSpeed)
    }

    @Test fun failedChangesRetainEffectiveAndPersistedValuesAndCanBeRetried() {
        val values = mutableMapOf<String, String>(); var writable = true
        val model = LocalInputSettings(values::get) { k, v -> if (writable) { values[k] = v; true } else false }
        val before = values.toMap()
        writable = false
        assertFalse(model.stepPointerX(1)); assertFalse(model.stepPointerY(-1))
        assertFalse(model.stepScroll(1)); assertFalse(model.setHeadScroll(false))
        assertEquals(2.0, model.pointerXGain, 0.0); assertEquals(2.0, model.pointerYGain, 0.0)
        assertEquals(240, model.scrollSpeed); assertTrue(model.headScrollEnabled)
        assertEquals(before, values); assertNotNull(model.error)
        writable = true
        assertTrue(model.setHeadScroll(false)); assertNull(model.error)
        assertFalse(settings(values).headScrollEnabled)
    }
}
