package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetRequest
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Tap
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual production mapping owner/injector, with modeled observations and no Android operations. */
class ManagedUiMappingTest {
    private fun owner(): ManagedProjectionDisplay = ManagedProjectionDisplay::class.java.declaredConstructors
        .single { it.parameterCount == 1 }.apply { isAccessible = true }
        .newInstance({ _: String, _: Int?, _: Int? -> Unit }) as ManagedProjectionDisplay

    private fun ManagedProjectionDisplay.field(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun ManagedProjectionDisplay.set(name: String, value: Any?) = field(name).set(this, value)
    private fun ManagedProjectionDisplay.flag(name: String, value: Boolean) = (field(name).get(this) as AtomicBoolean).set(value)

    private fun source(rotation: Int = 0, disabled: Boolean = false) = ManagedProjectionDisplay.SourceObservation(
        Geometry(400, 400), Geometry(1000, 800), 320, rotation, disabled)

    @Test fun immutableSnapshotUsesExistingInputAdmissionAndAllowsMissingOptionalUniqueId() {
        val owner = owner()
        val source = source()
        val video = Geometry(500, 400)
        assertNull(owner.uiInputSnapshot())
        owner.set("capturedDisplayId", 34)
        owner.set("observedSource", source)
        owner.inputGeometry(video)
        val snapshot = owner.uiInputSnapshot()!!
        assertEquals(34, snapshot.displayId)
        assertNull(snapshot.displayUniqueId)
        assertEquals(source.logical, snapshot.geometry.logical)
        assertEquals(source.physical, snapshot.geometry.physical)
        assertEquals(video, snapshot.geometry.video)
        var called = false
        assertTrue(owner.withInputMapping(video) { display, mapping ->
            assertEquals(snapshot.displayId, display)
            assertEquals(snapshot.geometry, mapping.coordinates)
            called = true; true
        })
        assertTrue(called)
        owner.set("originalDisplayUniqueId", "actual-managed-observation")
        assertEquals("actual-managed-observation", owner.uiInputSnapshot()!!.displayUniqueId)
        for (flag in listOf("displayChanged", "closeRequested")) {
            owner.flag(flag, true)
            assertNull(owner.uiInputSnapshot())
            assertFalse(owner.withInputMapping(video) { _, _ -> error("Must retain input admission") })
            owner.flag(flag, false)
        }
        owner.set("sourceTransitioning", true)
        assertNull(owner.uiInputSnapshot())
        owner.set("sourceTransitioning", false)
        owner.resize(0.8)
        assertNull(owner.uiInputSnapshot())
        owner.inputGeometry(video)
        assertNull(owner.uiInputSnapshot())
        @Suppress("UNCHECKED_CAST")
        val resize = owner.field("resizeRequested").get(owner) as AtomicReference<Double?>
        resize.set(null)
        owner.inputGeometry(video)
        owner.set("observedSource", source.copy(densityDpi = 416))
        assertNull(owner.uiInputSnapshot())
        assertEquals(320, snapshot.geometry.densityDpi)
    }

    @Test fun actualInjectorUsesSharedTransformAtIndependentLandmarksAndKeepsFloatClamps() {
        val input = ManagedProjectionInput(owner())
        val method = input.javaClass.getDeclaredMethod("sourceCoordinates", Tap::class.java,
            ManagedProjectionDisplay.InputMapping::class.java).apply { isAccessible = true }
        fun mapped(x: Int, y: Int, source: ManagedProjectionDisplay.SourceObservation = source()): Pair<Float, Float> {
            @Suppress("UNCHECKED_CAST")
            return method.invoke(input, Tap(x, y, Geometry(500, 400)),
                ManagedProjectionDisplay.InputMapping(Geometry(500, 400), source)) as Pair<Float, Float>
        }
        // physical1000x800, logical400x400 -> centered800x800 frame at x100; video half physical.
        assertEquals(100f to 150f, mapped(150, 150))
        assertEquals(0f to 0f, mapped(0, 0))
        assertEquals(399f to 399f, mapped(499, 399))
        assertEquals(100f to 50f, mapped(200, 175, source(disabled = true).copy(logical = Geometry(400, 200))))
        // ROTATION_90: video(375,100) -> physical(750,200) -> oriented(200,250), then logical half.
        assertEquals(100f to 125f, mapped(375, 100, source(rotation = 1).copy(logical = Geometry(400, 500))))
    }

    @Test fun contextCodecKeepsOptionalObservedMetadataAndSelectedIntentSeparate() {
        val coordinates = ManagedProjectionDisplay.InputMapping(Geometry(500, 400), source()).coordinates
        val original = ManagedUiSource(ProjectionContext("owner", "projection"), "source-generation", 34,
            null, "current.app", null, coordinates)
        assertEquals(original, ManagedUiSource.from(original.bytes()))
        val selected = UiTargetRequest(original, "selected.app", 7)
        assertEquals("selected.app", selected.selectedPackage)
        assertEquals("current.app", selected.source.foregroundPackage)
        assertNull(selected.source.foregroundActivity)
        assertNull(selected.source.displayUniqueId)
        val observed = original.copy(displayUniqueId = "observed", foregroundActivity = "current.app/.Actual")
        assertEquals(observed, ManagedUiSource.from(observed.bytes()))
        assertThrows(IllegalArgumentException::class.java) { ManagedUiSource.from(original.bytes() + byteArrayOf(0)) }
        assertThrows(java.io.EOFException::class.java) { ManagedUiSource.from(original.bytes().dropLast(1).toByteArray()) }
    }

    @Test fun defaultCapturedSessionContractIsExplicitlyUnavailable() {
        val original = object : CapturedProjection {
            override fun phoneHidEdge(routeId: Long, edge: com.code2hack.eyeprojector.protocol.PhoneHidEdge) = Unit
            override fun launchApp(requestId: Long, packageName: String): ProjectionDisplayState = error("unused")
            override fun projectionDisplayState(): ProjectionDisplayState = error("unused")
        }
        assertNull(original.uiTargetContext()) // The real Session override is a separate coordinated handoff.
    }
}
