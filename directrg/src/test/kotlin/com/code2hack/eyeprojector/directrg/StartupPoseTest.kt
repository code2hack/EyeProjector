package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Proper unit-quaternion paths; these host cases do not establish a worn-device result. */
class StartupPoseTest {
    private val bounds = PointerBounds(0f, 0f, 480f, 640f)
    private val identity = HeadOrientation(0.0, 0.0, 0.0, 1.0)
    private fun axis(x: Double, y: Double, z: Double, degrees: Double): HeadOrientation {
        val half = Math.toRadians(degrees) / 2
        return HeadOrientation(x * sin(half), y * sin(half), z * sin(half), cos(half))
    }
    private fun rx(degrees: Double) = axis(1.0, 0.0, 0.0, degrees)
    private fun ry(degrees: Double) = axis(0.0, 1.0, 0.0, degrees)
    private fun rz(degrees: Double) = axis(0.0, 0.0, 1.0, degrees)
    private val worlds get() = listOf(identity, rz(73.0) * ry(-41.0) * rx(62.0),
        checkNotNull(HeadOrientation.from(RotationSample(1, .49f, .46f, .53f, .51f))))
    private val starts get() = listOf<Pair<String, (Double) -> HeadOrientation>>(
        "upright" to { identity },
        "sideways roll +90" to { f -> rz(90 * f) },
        "sideways roll -90" to { f -> rz(-90 * f) },
        "upside-down roll" to { f -> rz(180 * f) },
        "face up" to { f -> rx(90 * f) },
        "face down" to { f -> rx(-90 * f) },
        "flipped pitch" to { f -> rx(180 * f) },
        "beyond pitch pole" to { f -> rx(120 * f) },
        "sideways yaw +90" to { f -> ry(90 * f) },
        "sideways yaw -90" to { f -> ry(-90 * f) },
        "backward yaw" to { f -> ry(180 * f) },
        "combined tilt" to { f -> rz(90 * f) * ry(-120 * f) * rx(35 * f) },
    )

    private class Trace(val model: HeadPointerModel) {
        var timeNs = 1L
        fun feed(q: HeadOrientation, alternateSign: Boolean = false): PointerPosition {
            timeNs += 20_000_000
            val sign = if (alternateSign && timeNs / 20_000_000 % 2 == 0L) -1 else 1
            assertTrue(model.sample(RotationSample(timeNs, (q.x * sign).toFloat(), (q.y * sign).toFloat(),
                (q.z * sign).toFloat(), (q.w * sign).toFloat()), timeNs))
            return model.advance(timeNs)
        }
    }

    private fun rebase(trace: Trace, pose: HeadOrientation, rotation: Int, transition: String) {
        when (transition) {
            "focus" -> { trace.model.stop(); assertFalse(trace.model.position.available); trace.model.start() }
            "stale" -> { trace.timeNs += 300_000_000; trace.model.advance(trace.timeNs); assertFalse(trace.model.position.available) }
            "rotation" -> trace.model.resize(bounds, rotation, 32f)
        }
        val fresh = trace.feed(pose)
        assertEquals(240f, fresh.x, .001f); assertEquals(320f, fresh.y, .001f)
    }

    private fun wearing(world: HeadOrientation, startup: (Double) -> HeadOrientation, rotation: Int,
                        startupReset: String? = null): Trace {
        val trace = Trace(HeadPointerModel().apply {
            resize(bounds, if (startupReset == "rotation") (rotation + 1) % 4 else rotation, 32f)
            // Leave room to observe both movement directions after a full 180° startup transition.
            sensitivity(.1, .1, 0); start()
        })
        trace.feed(world * startup(1.0))
        if (startupReset != null) rebase(trace, world * startup(1.0), rotation, startupReset)
        for (step in 1..60) trace.feed(world * startup(1 - step / 60.0))
        repeat(30) { trace.feed(world) }
        return trace
    }

    private fun assertMovement(trace: Trace, world: HeadOrientation, rotation: Int, horizontal: Boolean, sign: Int, label: String) {
        val before = trace.model.position
        for (step in 1..10) {
            val angle = -sign * 6.0 * step / 10
            trace.feed(world * if (horizontal) ry(angle) else rx(angle))
        }
        repeat(30) { trace.feed(world * if (horizontal) ry(-sign * 6.0) else rx(-sign * 6.0)) }
        val after = trace.model.position
        val directions = if (horizontal) listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
            else listOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)
        val expected = directions[rotation]
        val dx = after.x - before.x; val dy = after.y - before.y
        assertTrue("$label: pointer unavailable", after.available)
        if (expected.first == 0) assertEquals("$label: orthogonal X", 0f, dx, .02f)
        else assertTrue("$label: X=$dx", dx * expected.first * sign > 2f)
        if (expected.second == 0) assertEquals("$label: orthogonal Y", 0f, dy, .02f)
        else assertTrue("$label: Y=$dy", dy * expected.second * sign > 2f)
    }

    @Test fun startupPosturesDoNotReverseOrSwapEitherDisplayedAxisAfterNormalWear() {
        val failures = mutableListOf<Pair<String, String>>()
        for ((worldIndex, world) in worlds.withIndex()) for ((name, startup) in starts) {
            for (rotation in 0..3) for (horizontal in listOf(true, false)) for (sign in listOf(-1, 1)) {
                val trace = wearing(world, startup, rotation)
                try {
                    assertMovement(trace, world, rotation, horizontal, sign,
                        "world=$worldIndex startup=$name rotation=$rotation horizontal=$horizontal sign=$sign")
                } catch (failure: AssertionError) { failures.add(name to failure.message.orEmpty()) }
            }
        }
        assertTrue("Failure counts by startup: ${failures.groupingBy { it.first }.eachCount()}\n" +
            failures.distinctBy { it.first }.joinToString("\n") { it.second }, failures.isEmpty())
    }

    @Test fun focusRestartStaleRecoveryAndDisplayRotationRebaseFromCurrentPosture() {
        for ((name, startup) in starts) for (rotation in 0..3) {
            for (transition in listOf("focus", "stale", "rotation")) {
                for (atStartup in listOf(false, true)) {
                    for (horizontal in listOf(true, false)) for (sign in listOf(-1, 1)) {
                        val world = worlds[1]
                        val trace = wearing(world, startup,
                            if (transition == "rotation" && !atStartup) (rotation + 1) % 4 else rotation,
                            if (atStartup) transition else null)
                        if (!atStartup) rebase(trace, world, rotation, transition)
                        assertMovement(trace, world, rotation, horizontal, sign,
                            "$transition atStartup=$atStartup startup=$name rotation=$rotation horizontal=$horizontal sign=$sign")
                    }
                }
            }
        }
    }

    @Test fun pureRollAndQuaternionSignChangesDoNotMoveEitherAxis() {
        for (world in worlds) for (rotation in 0..3) {
            val trace = wearing(world, { f -> rz(180 * f) }, rotation)
            for (angle in 0..360 step 3) trace.feed(world * rz(angle.toDouble()), alternateSign = true)
            repeat(30) { trace.feed(world, alternateSign = true) }
            assertEquals(240f, trace.model.position.x, .001f)
            assertEquals(320f, trace.model.position.y, .001f)
        }
    }

    @Test fun exactHalfTurnHasTheSameMotionForBothQuaternionRepresentations() {
        val half = kotlin.math.sqrt(.5)
        for (pose in listOf(HeadOrientation(half, half, 0.0, 0.0),
            HeadOrientation(0.0, half, half, -0.0), HeadOrientation(1.0, 0.0, 0.0, 0.0))) {
            val opposite = HeadOrientation(-pose.x, -pose.y, -pose.z, -pose.w)
            for (rotation in 0..3) assertEquals(pose.motion(rotation), opposite.motion(rotation))
        }
    }

    @Test fun fixedQuaternionFixturesMoveRightLeftDownUpWithoutUsingCompositionHelper() {
        // Analytically calculated W * Ry(±6°) and W * Rx(±6°), W=(.5,.5,.5,.5).
        // These constants are synthetic, not physical sensor observations.
        val low = .4731467893; val high = .5254827455
        val fixtures = listOf(
            Triple(HeadOrientation(high, low, low, high), 91.2f, 0f),
            Triple(HeadOrientation(low, high, high, low), -91.2f, 0f),
            Triple(HeadOrientation(low, low, high, high), 0f, 165.81818f),
            Triple(HeadOrientation(high, high, low, low), 0f, -165.81818f),
        )
        for ((pose, dx, dy) in fixtures) {
            val trace = Trace(HeadPointerModel().apply { resize(bounds, 0, 32f); start() })
            trace.feed(HeadOrientation(.5, .5, .5, .5))
            repeat(50) { trace.feed(pose) }
            assertEquals(240f + dx, trace.model.position.x, .01f)
            assertEquals(320f + dy, trace.model.position.y, .01f)
        }
    }
}
