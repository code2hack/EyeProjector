package com.code2hack.eyeprojector.directrg

import kotlin.math.*

/** Synthetic rotation samples through the real model; never a hardware/sensor measurement. */
internal class AnchorAdbSyntheticAim(private val model: HeadPointerModel, private val bounds: PointerBounds,
                                     private val rotation: Int) {
    private var orientation = HeadOrientation(0.0, 0.0, 0.0, 1.0)
    private var seeded = false

    fun tick(x: Float, y: Float, nowNs: Long): PointerPosition {
        if (!seeded) {
            model.start(); seeded = true
            sample(nowNs)
            return model.advance(nowNs)
        }
        val point = model.advance(nowNs)
        fun turn(error: Float, span: Float, range: Double, gain: Double): Double {
            if (abs(error) <= 1f || span <= 0f) return 0.0
            return (error / span * 2 * range / gain).coerceIn(-2.0, 2.0)
        }
        val right = turn(x - point.x, bounds.width, model.settings.horizontalHalfRangeDegrees, model.sensitivityX)
        val down = turn(y - point.y, bounds.height, model.settings.verticalHalfRangeDegrees, model.sensitivityY)
        val (localX, localY) = when (rotation) {
            1 -> -right to down
            2 -> -down to -right
            3 -> right to -down
            else -> down to right
        }
        val rx = Math.toRadians(localX); val ry = Math.toRadians(localY)
        val angle = hypot(rx, ry)
        if (angle != 0.0) {
            val scale = sin(angle / 2) / angle
            val movement = HeadOrientation(rx * scale, ry * scale, 0.0, cos(angle / 2))
            orientation = orientation * movement.inverse()
        }
        sample(nowNs)
        return model.advance(nowNs)
    }

    private fun sample(nowNs: Long) {
        check(model.sample(RotationSample(nowNs, orientation.x.toFloat(), orientation.y.toFloat(),
            orientation.z.toFloat(), orientation.w.toFloat()), nowNs))
    }
}
