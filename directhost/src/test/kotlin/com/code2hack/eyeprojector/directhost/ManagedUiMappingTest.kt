package com.code2hack.eyeprojector.directhost

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

}
