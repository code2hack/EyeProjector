package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry

data class CanvasPoint(val x: Double, val y: Double)

/** Actual logical pixels and density are distinct from normalized 160-dpi layout and video pixels. */
data class ProjectionCoordinates(
    val logical: Geometry,
    val physical: Geometry,
    val densityDpi: Int,
    val rotation: Int,
    val scalingDisabled: Boolean,
    val video: Geometry,
) {
    init { require(densityDpi > 0 && rotation in 0..3) }

    private val rotated get() = rotation == 1 || rotation == 3
    private val orientedWidth get() = if (rotated) physical.height else physical.width
    private val orientedHeight get() = if (rotated) physical.width else physical.height
    private val widthConstrained get() = orientedWidth.toLong() * logical.height < orientedHeight.toLong() * logical.width
    private val frameWidth get() = if (scalingDisabled) logical.width else if (widthConstrained) orientedWidth else
        (logical.width.toLong() * orientedHeight / logical.height).toInt()
    private val frameHeight get() = if (scalingDisabled) logical.height else if (widthConstrained)
        (logical.height.toLong() * orientedWidth / logical.width).toInt() else orientedHeight
    private val left get() = (orientedWidth - frameWidth) / 2
    private val top get() = (orientedHeight - frameHeight) / 2

    /** LogicalDisplay's centered aspect-fit mapping, shared with ordinary managed input injection. */
    fun videoToSource(point: CanvasPoint): CanvasPoint {
        val px = point.x * physical.width / video.width
        val py = point.y * physical.height / video.height
        val oriented = when (rotation) {
            0 -> CanvasPoint(px, py)
            1 -> CanvasPoint(py, physical.width - px)
            2 -> CanvasPoint(physical.width - px, physical.height - py)
            else -> CanvasPoint(physical.height - py, px)
        }
        return CanvasPoint((oriented.x - left) * logical.width / frameWidth,
            (oriented.y - top) * logical.height / frameHeight)
    }

    /** Preserve the existing injector's Float conversion and edge clamping. */
    fun videoToInput(point: CanvasPoint): Pair<Float, Float> = videoToSource(point).let {
        it.x.toFloat().coerceIn(0f, (logical.width - 1).toFloat()) to
            it.y.toFloat().coerceIn(0f, (logical.height - 1).toFloat())
    }

    fun sourceToVideo(point: CanvasPoint): CanvasPoint {
        val x = point.x * frameWidth / logical.width + left
        val y = point.y * frameHeight / logical.height + top
        val physicalPoint = when (rotation) {
            0 -> CanvasPoint(x, y)
            1 -> CanvasPoint(physical.width - y, x)
            2 -> CanvasPoint(physical.width - x, physical.height - y)
            else -> CanvasPoint(y, physical.height - x)
        }
        return CanvasPoint(physicalPoint.x * video.width / physical.width,
            physicalPoint.y * video.height / physical.height)
    }

    fun sourceToLayout(point: CanvasPoint) = CanvasPoint(point.x * 160 / densityDpi, point.y * 160 / densityDpi)
    fun layoutToSource(point: CanvasPoint) = CanvasPoint(point.x * densityDpi / 160, point.y * densityDpi / 160)

}
