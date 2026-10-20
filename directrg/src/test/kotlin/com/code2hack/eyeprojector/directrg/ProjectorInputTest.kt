package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Math/gesture evidence only; synthetic poses and keys do not prove hardware ergonomics. */
class ProjectorInputTest {
    private fun pose(ms: Long, yaw: Double = 0.0, pitch: Double = 0.0): RotationSample {
        val y = Math.toRadians(yaw) / 2
        val p = Math.toRadians(pitch) / 2
        return RotationSample(ms * 1_000_000, (cos(y) * sin(p)).toFloat(), (sin(y) * cos(p)).toFloat(),
            (-sin(y) * sin(p)).toFloat(), (cos(y) * cos(p)).toFloat())
    }
    private fun pointer(rotation: Int = 0, gain: Double = 2.0) = HeadPointerModel().apply {
        resize(PointerBounds(8f, 8f, 472f, 632f), rotation, 32f)
        sensitivity(gain, 0); start(); sample(pose(1), 1_000_000)
    }
    private fun settle(model: HeadPointerModel, yaw: Double, pitch: Double = 0.0) {
        for (t in 21L..1001L step 20) { model.sample(pose(t, yaw, pitch), t * 1_000_000); model.advance(t * 1_000_000) }
    }
    @Test fun headLocalAxesApplyInEveryDisplayRotation() {
        val yawDirections = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
        val pitchDirections = listOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)
        fun direction(model: HeadPointerModel, expected: Pair<Int, Int>, sign: Int, description: String) {
            val dx = model.position.x - 240f; val dy = model.position.y - 320f
            if (expected.first == 0) assertEquals(description, 0f, dx, .1f)
            else assertTrue("$description: x=$dx", dx * expected.first * sign > 5f)
            if (expected.second == 0) assertEquals(description, 0f, dy, .1f)
            else assertTrue("$description: y=$dy", dy * expected.second * sign > 5f)
        }
        for (rotation in 0..3) for (sign in listOf(-1, 1)) {
            val yaw = pointer(rotation); settle(yaw, -sign * 5.0)
            direction(yaw, yawDirections[rotation], sign, "rotation=$rotation head right sign=$sign")
            val pitch = pointer(rotation); settle(pitch, 0.0, -sign * 5.0)
            direction(pitch, pitchDirections[rotation], sign, "rotation=$rotation head down sign=$sign")
        }
    }
    @Test fun gainAndRotationRetainScale() {
        val standard = pointer(gain = 1.0); settle(standard, -5.0)
        val twice = pointer(); settle(twice, -5.0)
        assertEquals((standard.position.x - 240) * 2, twice.position.x - 240, .1f)
        val rotated = pointer(rotation = 1); settle(rotated, -5.0)
        assertTrue(rotated.position.y > 320)
        assertEquals(240f, rotated.position.x, .1f)
    }
    @Test fun headMotionKeepsItsAxesAfterRollSinceStartup() {
        fun afterRoll(rollDegrees: Double, yaw: Double, pitch: Double): PointerPosition {
            val model = pointer()
            val radians = Math.toRadians(rollDegrees) / 2
            val roll = HeadOrientation(0.0, 0.0, sin(radians), cos(radians))
            fun sample(t: Long, orientation: HeadOrientation) {
                model.sample(RotationSample(t * 1_000_000, orientation.x.toFloat(), orientation.y.toFloat(),
                    orientation.z.toFloat(), orientation.w.toFloat()), t * 1_000_000)
                model.advance(t * 1_000_000)
            }
            for (t in 21L..1001L step 20) sample(t, roll)
            val moved = roll * checkNotNull(HeadOrientation.from(pose(1, yaw, pitch)))
            for (t in 1021L..2001L step 20) sample(t, moved)
            return model.position
        }
        for (roll in listOf(0.0, 180.0, -180.0, 90.0, -90.0)) for (sign in listOf(-1, 1)) {
            val yaw = afterRoll(roll, -sign * 5.0, 0.0)
            assertTrue("roll=$roll local yaw sign=$sign: $yaw", (yaw.x - 240) * sign > 5)
            assertEquals(320f, yaw.y, .1f)
            val pitch = afterRoll(roll, 0.0, -sign * 5.0)
            assertTrue("roll=$roll local pitch sign=$sign: $pitch", (pitch.y - 320) * sign > 5)
            assertEquals(240f, pitch.x, .1f)
        }
    }
    @Test fun observedWornRightTurnMovesRight() {
        // Rounded sensorservice samples captured while the Owner held forward, then right.
        // This is a regression fixture, not a replacement for physical verification.
        val model = pointer()
        model.stop(); model.start()
        model.sample(RotationSample(1_000_000, .49f, .46f, .53f, .51f), 1_000_000)
        for (t in 21L..1001L step 20) {
            model.sample(RotationSample(t * 1_000_000, .55f, .29f, .41f, .66f), t * 1_000_000)
            model.advance(t * 1_000_000)
        }
        assertTrue("Observed right turn: ${model.position}", model.position.x > 240f)
    }
    @Test fun stripsStayBoundedAndInwardTravelImmediatelyReturns() {
        val model = pointer(); settle(model, 0.0, -80.0)
        assertEquals(664f, model.position.y, .1f)
        for (t in 1021L..1221L step 20) { model.sample(pose(t, 0.0, -76.0), t * 1_000_000); model.advance(t * 1_000_000) }
        assertTrue(model.position.y < 640)
        assertEquals(8f, model.motionBounds.left, 0f)
        assertEquals(-24f, model.motionBounds.top, 0f)
    }
    @Test fun sensorLossStopAndRestartDiscardOldAim() {
        val model = pointer(); settle(model, -12.0)
        model.advance(1_251_000_000); assertFalse(model.position.available)
        assertTrue(model.sample(pose(1300, -70.0), 1_300_000_000))
        assertEquals(240f, model.position.x, .1f)
        model.stop(); assertFalse(model.sample(pose(1400), 1_400_000_000))
        model.start(); assertFalse(model.position.available)
        assertFalse(model.sample(pose(1500).copy(x = Float.NaN), 1_500_000_000))
    }
    @Test fun scrollStopsAtVisibleBoundaryExpiryAndPauseWithoutCatchup() {
        val motion = EdgeScrollMotion(); val bounds = PointerBounds(0f, 0f, 480f, 640f)
        val outside = PointerPosition(240f, 650f, true)
        assertEquals(0, motion.step(outside, bounds, Long.MAX_VALUE, 1_000_000, 240))
        assertEquals(12, motion.step(outside, bounds, Long.MAX_VALUE, 51_000_000, 240))
        assertEquals(0, motion.step(outside.copy(y = 639f), bounds, Long.MAX_VALUE, 71_000_000, 240))
        assertEquals(0, motion.step(outside, bounds, Long.MAX_VALUE, 91_000_000, 240))
        assertEquals(12, motion.step(outside, bounds, Long.MAX_VALUE, 5_000_000_000, 240))
        assertEquals(0, motion.step(outside, bounds, 5_000_000_000, 5_000_000_000, 240))
        motion.stop()
        assertEquals(0, motion.step(outside, bounds, Long.MAX_VALUE, 6_000_000_000, 240))
    }
}
