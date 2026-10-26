package com.code2hack.eyeprojector.directrg.localcamera

import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry
import kotlin.math.abs

private fun near(actual: Double, expected: Double) = check(abs(actual - expected) < 1e-9) {
    "$actual != $expected"
}
private val image = PhoneImageKey("projection-A", 13, 91, Geometry(7, 5))
private val use = CameraUseKey("projection-A", 7)
private val source = CameraSourceKey(use, 101)
private val full = PhoneRect(0.0, 0.0, 7.0, 5.0)
private fun layout(preview: PhoneRect = full, controls: List<PhoneRect> = emptyList(),
    key: PhoneImageKey = image, cameraUse: CameraUseKey = use, generation: Long = 23) =
    ViewfinderLayout(key, cameraUse, generation, preview, controls)

private fun geometryAndOriginalInput() {
    val fit = Fit.inside(11, 12, image.geometry)
    check(fit == Fit(0, 2, 11, 7)) // Existing Fit truncates the independently scaled height.
    val preview = PhoneRect(1.0, 1.0, 6.0, 3.0)
    val mapped = preview.inRoot(image.geometry, fit)
    near(mapped.left, 11.0 / 7); near(mapped.top, 3.4)
    near(mapped.right, 66.0 / 7); near(mapped.bottom, 6.2)
    check(layout(preview).replacementScissors(11, 7) == listOf(GlScissor(2, 3, 7, 3)))
    // A preserved shutter still targets its ORIGINAL Phone coordinates through the existing Fit.
    val x = 2.5 * 11 / 7; val y = 2 + 1.5 * 7 / 5
    val before = fit.touch(x.toFloat(), y.toFloat(), image.geometry)!!
    layout(preview, listOf(PhoneRect(2.0, 1.0, 3.0, 2.0))).replacementScissors(11, 7)
    check(fit.touch(x.toFloat(), y.toFloat(), image.geometry) == before)
    check(before.x == 2 && before.y == 1)
    check(fit.touch(0f, 1.99f, image.geometry) == null)
    check(fit.touch(11f, 3f, image.geometry) == null)
    val wide = Fit.inside(20, 10, Geometry(4, 8))
    check(wide == Fit(7, 0, 5, 10))
    check(PhoneRect(0.0, 0.0, 4.0, 8.0).inRoot(Geometry(4, 8), wide) ==
        PhoneRect(7.0, 0.0, 12.0, 10.0))
}

private fun controlOverlapAndPixels() {
    val g = Geometry(8, 6)
    val key = image.copy(geometry = g)
    val controls = mutableListOf(PhoneRect(3.0, 2.0, 5.0, 4.0))
    val mask = layout(PhoneRect(1.0, 1.0, 7.0, 5.0), controls, key)
    controls.clear() // Layout retains the original control snapshot.
    check(mask.replacementScissors(8, 6).toSet() == setOf(
        GlScissor(1, 4, 6, 1), GlScissor(1, 1, 6, 1),
        GlScissor(1, 2, 2, 2), GlScissor(5, 2, 2, 2)))
    fun contains(r: PhoneRect, x: Double, y: Double) =
        x >= r.left && x < r.right && y >= r.top && y < r.bottom
    val cases = listOf(mask,
        layout(PhoneRect(-2.0, -1.0, 9.0, 7.0), listOf(
            PhoneRect(1.1, 0.5, 3.8, 4.2), PhoneRect(2.0, 2.0, 6.2, 5.9),
            PhoneRect(20.0, 20.0, 21.0, 21.0)), key),
        layout(full, listOf(full, full)), layout(PhoneRect(3.0, 1.0, 3.0, 4.0)))
    for (candidate in cases) for ((w, h) in listOf(8 to 6, 11 to 7, 3 to 2)) {
        val regions = candidate.replacementScissors(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val phoneX = (x + 0.5) * candidate.image.geometry.width / w
            val phoneY = (y + 0.5) * candidate.image.geometry.height / h
            val expected = contains(candidate.preview, phoneX, phoneY) &&
                candidate.preservedControls.none { contains(it, phoneX, phoneY) }
            val coverage = regions.count { x >= it.x && x < it.x + it.width &&
                h - 1 - y >= it.y && h - 1 - y < it.y + it.height }
            check(coverage == if (expected) 1 else 0) { "mask pixel $x,$y ($w,$h) coverage=$coverage" }
        }
    }
    val centerBoundary = layout(PhoneRect(0.5, 0.5, 2.5, 2.5), key = key)
    check(centerBoundary.replacementScissors(8, 6) == listOf(GlScissor(0, 4, 2, 2)))
}

private fun cameraFraming() {
    val fill = CameraSampling(8, 4)
    near(fill.sample(0.0, 0.0, 1.0, 1.0).x, 0.25)
    near(fill.sample(1.0, 1.0, 1.0, 1.0).x, 0.75)
    near(fill.sample(1.0, 1.0, 1.0, 1.0).y, 1.0)
    // Rounded fitted dimensions determine camera crop, independently of Phone source aspect.
    val fittedPreview = PhoneRect(0.0, 0.0, 7.0, 5.0).inRoot(image.geometry, Fit.inside(11, 12, image.geometry))
    near(fill.sample(0.0, 0.0, fittedPreview.width, fittedPreview.height).x, 3.0 / 28)
    // Independent raw-pixel oracle: cropped 6x4 rotates to 4x6, then mirrors horizontally.
    // Output (X,Y) has raw pixel (2+Y,1+X); no square symmetry hides operation order.
    val transform = CameraSampling(10, 8, PhoneRect(0.2, 0.125, 0.8, 0.625),
        ClockwiseRotation.QUARTER, mirror = true)
    for (y in 0..5) for (x in 0..3) {
        val uv = transform.sample((x + 0.5) / 4, (y + 0.5) / 6, 4.0, 6.0)
        near(uv.x, (2 + y + 0.5) / 10); near(uv.y, (1 + x + 0.5) / 8)
    }
    val withoutMirror = transform.copy(mirror = false).sample(0.125, 1.0 / 12, 4.0, 6.0)
    near(withoutMirror.x, 0.25); near(withoutMirror.y, 4.5 / 8)
    val half = CameraSampling(4, 4, rotation = ClockwiseRotation.HALF).sample(0.1, 0.3, 1.0, 1.0)
    near(half.x, 0.9); near(half.y, 0.7)
    val three = CameraSampling(4, 4, rotation = ClockwiseRotation.THREE_QUARTERS).sample(0.1, 0.3, 1.0, 1.0)
    near(three.x, 0.7); near(three.y, 0.1)
}

private fun lifecycleAndAccounting() {
    val state = LocalCompositionState()
    val sampling = CameraSampling(8, 4)
    val frame = LocalCameraFrame(source, 1, 1234, "glasses-sensor-unknown-origin")
    state.replaceCamera(source, sampling); state.cameraLatched(frame); state.attachWindow(41)
    check(state.plan(CompositionCause.CAMERA_FRAME) == null) // Local feed supplies no Phone frame.
    state.phoneLatched(image); state.setLayout(layout())
    val first = state.plan(CompositionCause.PHONE_FRAME)!!
    check(first.local?.frame == frame && first.reportsPhonePresentation)
    check(state.submitted(first))
    for (sequence in 2L..4L) {
        check(state.cameraLatched(frame.copy(sequence = sequence, timestampNanos = 1234 + sequence)))
        val plan = state.plan(CompositionCause.CAMERA_FRAME)!!
        check(plan.image == image && !plan.reportsPhonePresentation)
        check(state.submitted(plan))
    }
    check(state.phoneLatches == 1L && state.cameraLatches == 4L)
    check(state.phoneWindowSubmissions == 1L && state.cameraWindowSubmissions == 3L)
    val oldWindow = state.plan(CompositionCause.REDRAW)!!
    state.detachWindow(41); check(state.plan(CompositionCause.REDRAW) == null)
    state.attachWindow(42); state.detachWindow(41)
    check(!state.submitted(oldWindow))
    check(state.plan(CompositionCause.WINDOW_ATTACH)!!.local != null)
    check(state.phoneLatches == 1L) // Retained reattach is not a decoder latch.
    val oldLayout = state.plan(CompositionCause.REDRAW)!!
    state.setLayout(layout(generation = 24)); check(!state.submitted(oldLayout))
    val newUse = CameraUseKey("projection-A", 8)
    val newSource = CameraSourceKey(newUse, 101) // Same source number, distinct camera use.
    state.replaceCamera(newSource, sampling)
    check(!state.cameraLatched(frame))
    state.closeCamera(source) // Old resource cleanup cannot close the replacement.
    check(state.cameraLatched(frame.copy(source = newSource)))
    check(state.plan(CompositionCause.CAMERA_FRAME)!!.local == null) // Old layout belongs to use7.
    state.setLayout(layout(cameraUse = newUse, generation = 25))
    check(state.plan(CompositionCause.CAMERA_FRAME)!!.local != null)
    val nextImage = image.copy(geometryGeneration = 14) // Equal dimensions do not mean same geometry.
    state.phoneLatched(nextImage)
    check(state.plan(CompositionCause.PHONE_FRAME)!!.local == null)
    state.setLayout(layout()); check(state.plan(CompositionCause.REDRAW)!!.local == null)
    state.setLayout(layout(key = nextImage, cameraUse = newUse))
    check(state.plan(CompositionCause.REDRAW)!!.local != null)
    state.invalidatePhone(); check(state.plan(CompositionCause.CAMERA_FRAME) == null)
    check(state.cameraLatched(frame.copy(source = newSource, sequence = 9)))
    state.phoneLatched(image.copy(projectionEpoch = "projection-B"))
    state.setLayout(layout(key = image.copy(projectionEpoch = "projection-B"), cameraUse = newUse))
    check(state.plan(CompositionCause.REDRAW)!!.local == null)
    state.close(); state.close(); state.phoneLatched(image); state.attachWindow(99)
    check(!state.cameraLatched(frame) && state.plan(CompositionCause.PHONE_FRAME) == null)
}

fun main() {
    geometryAndOriginalInput(); println("PASS rounded fit, Surface scissor and original Phone input")
    controlOverlapAndPixels(); println("PASS overlapping controls, clipping, fractional pixel membership and disjoint coverage")
    cameraFraming(); println("PASS center-fill, crop, rotation and mirror with raw-pixel oracle")
    lifecycleAndAccounting(); println("PASS distinct image/layout/source/use/window ownership and camera-only accounting")
    println("Scope: complete pure current source + actual Wire/Fit; synthetic pixel selection, no EGL/Camera2/device/capture proof")
}
