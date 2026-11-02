package com.code2hack.eyeprojector.directhost.uiaccess

import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry

data class UiPoint(val x: Double, val y: Double)
data class UiRectangle(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Actual logical pixels and density are distinct from normalized 160-dpi layout and video pixels. */
data class UiSourceGeometry(
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
    fun videoToSource(point: UiPoint): UiPoint {
        val px = point.x * physical.width / video.width
        val py = point.y * physical.height / video.height
        val oriented = when (rotation) {
            0 -> UiPoint(px, py)
            1 -> UiPoint(py, physical.width - px)
            2 -> UiPoint(physical.width - px, physical.height - py)
            else -> UiPoint(physical.height - py, px)
        }
        return UiPoint((oriented.x - left) * logical.width / frameWidth,
            (oriented.y - top) * logical.height / frameHeight)
    }

    /** Preserve the existing injector's Float conversion and edge clamping. */
    fun videoToInput(point: UiPoint): Pair<Float, Float> = videoToSource(point).let {
        it.x.toFloat().coerceIn(0f, (logical.width - 1).toFloat()) to
            it.y.toFloat().coerceIn(0f, (logical.height - 1).toFloat())
    }

    fun sourceToVideo(point: UiPoint): UiPoint {
        val x = point.x * frameWidth / logical.width + left
        val y = point.y * frameHeight / logical.height + top
        val physicalPoint = when (rotation) {
            0 -> UiPoint(x, y)
            1 -> UiPoint(physical.width - y, x)
            2 -> UiPoint(physical.width - x, physical.height - y)
            else -> UiPoint(y, physical.height - x)
        }
        return UiPoint(physicalPoint.x * video.width / physical.width,
            physicalPoint.y * video.height / physical.height)
    }

    fun sourceToLayout(point: UiPoint) = UiPoint(point.x * 160 / densityDpi, point.y * 160 / densityDpi)
    fun layoutToSource(point: UiPoint) = UiPoint(point.x * densityDpi / 160, point.y * densityDpi / 160)

    /** Bounds are already screen-relative: do not add/subtract status bars, window origins or IME. */
    fun sourceToVideo(bounds: UiBounds): UiRectangle = envelope(bounds, ::sourceToVideo)

    fun sourceToReceiver(bounds: UiBounds, rootWidth: Int, rootHeight: Int): UiRectangle {
        val fit = Fit.inside(rootWidth, rootHeight, video)
        return envelope(bounds) { point -> sourceToVideo(point).let {
            UiPoint(fit.left + it.x * fit.width / video.width, fit.top + it.y * fit.height / video.height)
        } }
    }

    fun receiverToSource(point: UiPoint, rootWidth: Int, rootHeight: Int): UiPoint {
        val fit = Fit.inside(rootWidth, rootHeight, video)
        return videoToSource(UiPoint((point.x - fit.left) * video.width / fit.width,
            (point.y - fit.top) * video.height / fit.height))
    }

    private fun envelope(bounds: UiBounds, transform: (UiPoint) -> UiPoint): UiRectangle {
        val corners = listOf(UiPoint(bounds.left.toDouble(), bounds.top.toDouble()),
            UiPoint(bounds.right.toDouble(), bounds.top.toDouble()),
            UiPoint(bounds.right.toDouble(), bounds.bottom.toDouble()),
            UiPoint(bounds.left.toDouble(), bounds.bottom.toDouble())).map(transform)
        return UiRectangle(corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}
