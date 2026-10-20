package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic sensor math, not on-Glasses ergonomic evidence. */
class PointerAxesTest {
    private fun pose(ms: Long, yaw: Double = 0.0, pitch: Double = 0.0): RotationSample {
        val y = Math.toRadians(yaw) / 2; val p = Math.toRadians(pitch) / 2
        return RotationSample(ms * 1_000_000, sin(p).toFloat(), sin(y).toFloat(), 0f, (cos(y) * cos(p)).toFloat())
    }
    private fun pointer(x: Double, y: Double, rotation: Int = 0) = HeadPointerModel().apply {
        resize(PointerBounds(0f, 0f, 480f, 640f), rotation, 32f)
        sensitivity(x, y, 0); start(); sample(pose(1), 1_000_000)
    }
    private fun move(model: HeadPointerModel, ms: Long, yaw: Double, pitch: Double): PointerPosition {
        model.sample(pose(ms, yaw, pitch), ms * 1_000_000)
        return model.advance(ms * 1_000_000)
    }

    @Test fun independentGainsScaleDisplayedAxesInEveryRotation() {
        for (rotation in 0..3) {
            val base = pointer(1.0, 1.0, rotation)
            val moreX = pointer(2.0, 1.0, rotation)
            val moreY = pointer(1.0, 2.0, rotation)
            for (ms in 21L..1001L step 20) {
                move(base, ms, -5.0, -4.0); move(moreX, ms, -5.0, -4.0); move(moreY, ms, -5.0, -4.0)
            }
            assertEquals("rotation=$rotation X gain", 2 * (base.position.x - 240), moreX.position.x - 240, .1f)
            assertEquals("rotation=$rotation Y unaffected", base.position.y, moreX.position.y, .1f)
            assertEquals("rotation=$rotation Y gain", 2 * (base.position.y - 320), moreY.position.y - 320, .1f)
            assertEquals("rotation=$rotation X unaffected", base.position.x, moreY.position.x, .1f)
        }
    }

    @Test fun rateLimitingOneAxisDoesNotAlterOtherAxisDuringDiagonalMotion() {
        val base = pointer(.1, .1); val moreX = pointer(6.0, .1); val moreY = pointer(.1, 6.0)
        for (ms in 2L..101L step 1) {
            val b = move(base, ms, -40.0, -30.0)
            val x = move(moreX, ms, -40.0, -30.0)
            val y = move(moreY, ms, -40.0, -30.0)
            assertEquals("Y at $ms", b.y, x.y, .01f)
            assertEquals("X at $ms", b.x, y.x, .01f)
        }
        assertTrue(moreX.position.x > base.position.x + 20)
        assertTrue(moreY.position.y > base.position.y + 20)
    }

    @Test fun liveGainChangeHasNoJumpAndDiscardsOldFilteredTravel() {
        val model = pointer(2.0, 2.0)
        val before = move(model, 21, -10.0, -8.0)
        model.sensitivity(4.0, 1.0, 21_000_000)
        assertEquals(before, model.position)
        for (ms in 41L..1001L step 20) move(model, ms, -10.0, -8.0)
        assertEquals(before.x, model.position.x, .01f)
        assertEquals(before.y, model.position.y, .01f)
        model.advance(1_251_000_000)
        assertFalse(model.position.available)
        assertFalse(model.sample(pose(1260).copy(x = Float.NaN), 1_260_000_000))
        assertTrue(model.sample(pose(1280, -40.0, -30.0), 1_280_000_000))
        assertEquals(240f, model.position.x, .01f)
        assertEquals(320f, model.position.y, .01f)
        model.stop(); assertFalse(model.position.available)
        model.start(); assertFalse(model.position.available)
    }

    @Test fun changingOneGainKeepsOtherAxesPendingMotionAndUnchangedGainsKeepBoth() {
        val baseline = pointer(2.0, 2.0)
        val moreX = pointer(2.0, 2.0)
        val moreY = pointer(2.0, 2.0)
        val unchanged = pointer(2.0, 2.0)
        for (model in listOf(baseline, moreX, moreY, unchanged)) move(model, 21, -10.0, -8.0)
        moreX.sensitivity(4.0, 2.0, 21_000_000)
        moreY.sensitivity(2.0, 4.0, 21_000_000)
        unchanged.sensitivity(2.0, 2.0, 21_000_000)
        for (ms in 41L..201L step 20) {
            val b = move(baseline, ms, -10.0, -8.0)
            val x = move(moreX, ms, -10.0, -8.0)
            val y = move(moreY, ms, -10.0, -8.0)
            val same = move(unchanged, ms, -10.0, -8.0)
            assertEquals(b.y, x.y, .01f); assertEquals(b.x, y.x, .01f)
            assertEquals(b, same)
        }
    }

    @Test fun offFrameGainChangesPreserveOtherAxisElapsedMotion() {
        for (changeX in listOf(true, false)) {
            val baseline = pointer(2.0, 2.0)
            val changed = pointer(2.0, 2.0)
            // The unchanged axis is still smoothing when the other gain changes
            // halfway between display frames (21ms, change at31ms, next frame41ms).
            val yaw = if (changeX) 0.0 else -8.0
            val pitch = if (changeX) -8.0 else 0.0
            val before = move(changed, 21, yaw, pitch)
            assertEquals(move(baseline, 21, yaw, pitch), before)
            changed.sensitivity(if (changeX) 4.0 else 2.0, if (changeX) 2.0 else 4.0, 31_000_000)
            assertEquals("Changing gain must not jump either axis", before, changed.position)
            for (ms in 41L..201L step 20) {
                val expected = move(baseline, ms, yaw, pitch)
                val actual = move(changed, ms, yaw, pitch)
                assertEquals("Unchanged axis at ${ms}ms, changeX=$changeX",
                    if (changeX) expected.y else expected.x, if (changeX) actual.y else actual.x, .001f)
            }
        }
    }

    @Test fun repeatedOffFrameChangesAndNoOpKeepOtherAxisAlignedThroughSettlement() {
        for (changedAxis in listOf("X", "Y", "none")) {
            val baseline = pointer(2.0, 2.0)
            val changed = pointer(2.0, 2.0)
            move(baseline, 21, -10.0, -8.0); move(changed, 21, -10.0, -8.0)
            for (ms in 41L..1001L step 20) {
                val before = changed.position
                val gain = if (ms % 40 == 1L) 4.0 else 3.0
                changed.sensitivity(if (changedAxis == "X") gain else 2.0,
                    if (changedAxis == "Y") gain else 2.0, (ms - 10) * 1_000_000)
                assertEquals("Off-frame setter at ${ms - 10}ms", before, changed.position)
                val expected = move(baseline, ms, -10.0, -8.0)
                val actual = move(changed, ms, -10.0, -8.0)
                if (changedAxis != "X") assertEquals("X at ${ms}ms, changed=$changedAxis", expected.x, actual.x, .0001f)
                if (changedAxis != "Y") assertEquals("Y at ${ms}ms, changed=$changedAxis", expected.y, actual.y, .0001f)
            }
        }
    }

    @Test fun disablingActiveHeadScrollStopsBothEdgesAndReenableStartsFresh() {
        val bounds = PointerBounds(0f, 0f, 480f, 640f)
        for (y in listOf(-10f, 650f)) {
            val motion = EdgeScrollMotion(); val point = PointerPosition(240f, y, true)
            assertEquals(0, motion.step(point, bounds, Long.MAX_VALUE, 1_000_000, 250))
            assertEquals(if (y < 0) -12 else 12, motion.step(point, bounds, Long.MAX_VALUE, 51_000_000, 250))
            motion.enabled = false
            assertEquals(0, motion.direction(point, bounds, Long.MAX_VALUE, 51_000_000))
            for (ms in 101L..501L step 50) assertEquals(0, motion.step(point, bounds, Long.MAX_VALUE, ms * 1_000_000, 250))
            motion.enabled = true
            assertEquals(0, motion.step(point, bounds, Long.MAX_VALUE, 5_001_000_000, 250))
            assertEquals(if (y < 0) -12 else 12, motion.step(point, bounds, Long.MAX_VALUE, 5_051_000_000, 250))
        }
    }

    @Test fun disablingHeadScrollPreservesPointerAndPadScroll() {
        val settings = LocalInputSettings({ null }) { _, _ -> true }
        settings.setHeadScroll(false)
        val edge = EdgeScrollMotion().apply { enabled = settings.headScrollEnabled }
        val pointer = pointer(settings.pointerXGain, settings.pointerYGain)
        val point = move(pointer, 21, -5.0, -4.0)
        assertTrue(point.available); assertTrue(point.x > 240); assertTrue(point.y > 320)
        assertEquals(0, edge.direction(point.copy(y = 650f), pointer.motionBounds, Long.MAX_VALUE, 21_000_000))
        val scrolls = mutableListOf<Int>()
        val pad = PadGestureRecognizer(300, 500) { action ->
            if (action is PadGestureRecognizer.Action.Scroll) scrolls.add(action.pixels)
        }
        for ((key, at) in listOf(PadGestureRecognizer.Key.FORWARD to 1000L, PadGestureRecognizer.Key.BACKWARD to 1500L)) {
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at), at)
            pad.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + 20, at), at + 20)
        }
        pad.confirm(2000)
        assertEquals(listOf(160, -160), scrolls)
    }
}
