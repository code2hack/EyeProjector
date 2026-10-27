package com.code2hack.eyeprojector.directrg.localcamera

import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Half-open edges in the encoded Phone image, with its top-left origin. */
internal data class PhoneRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(left <= right && top <= bottom)
    }
    val width get() = right - left
    val height get() = bottom - top

    fun intersect(other: PhoneRect): PhoneRect? {
        val l = max(left, other.left); val t = max(top, other.top)
        val r = min(right, other.right); val b = min(bottom, other.bottom)
        return if (l < r && t < b) PhoneRect(l, t, r, b) else null
    }

    /** Use the actual rounded Fit edges; this mapping is for the root View, not its Surface. */
    fun inRoot(image: Geometry, fit: Fit) = PhoneRect(
        fit.left + left * fit.width / image.width, fit.top + top * fit.height / image.height,
        fit.left + right * fit.width / image.width, fit.top + bottom * fit.height / image.height,
    )
}

internal data class PhoneImageKey(
    val projectionEpoch: String,
    val geometryGeneration: Long,
    val imageRevision: Long,
    val geometry: Geometry,
)

internal data class CameraUseKey(val projectionEpoch: String, val useGeneration: Long)
internal data class CameraSourceKey(val use: CameraUseKey, val sourceGeneration: Long)

/** Explicit cooperating-app metadata; neither package identity nor dimensions infer this layout. */
internal class ViewfinderLayout(
    val image: PhoneImageKey,
    val cameraUse: CameraUseKey,
    val layoutGeneration: Long,
    val preview: PhoneRect,
    preservedControls: List<PhoneRect>,
) {
    // Snapshot the caller's list: a later UI mutation cannot change a submitted layout.
    val preservedControls: List<PhoneRect> = preservedControls.toList()

    /**
     * Pixel centers decide membership. Partition AFTER rasterization so overlapping controls
     * never cause a second camera pass. The returned scissors use GL's bottom-left origin.
     * The output window already fits the complete Phone image; no root Fit offset belongs here.
     */
    fun replacementScissors(surfaceWidth: Int, surfaceHeight: Int): List<GlScissor> {
        require(surfaceWidth > 0 && surfaceHeight > 0)
        val g = image.geometry
        val bounds = PhoneRect(0.0, 0.0, g.width.toDouble(), g.height.toDouble())
        fun raster(rect: PhoneRect): PixelRect? {
            val clipped = rect.intersect(bounds) ?: return null
            fun edge(value: Double, source: Int, target: Int) =
                ceil(value * target / source - 0.5).toInt().coerceIn(0, target)
            val result = PixelRect(edge(clipped.left, g.width, surfaceWidth),
                edge(clipped.top, g.height, surfaceHeight), edge(clipped.right, g.width, surfaceWidth),
                edge(clipped.bottom, g.height, surfaceHeight))
            return result.takeIf { it.left < it.right && it.top < it.bottom }
        }
        var regions = listOf(raster(preview) ?: return emptyList())
        for (control in preservedControls) {
            val exclusion = raster(control) ?: continue
            regions = regions.flatMap { it.minus(exclusion) }
        }
        return regions.map { GlScissor(it.left, surfaceHeight - it.bottom,
            it.right - it.left, it.bottom - it.top) }
    }
}

internal data class GlScissor(val x: Int, val y: Int, val width: Int, val height: Int)

private data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun minus(other: PixelRect): List<PixelRect> {
        val l = max(left, other.left); val t = max(top, other.top)
        val r = min(right, other.right); val b = min(bottom, other.bottom)
        if (l >= r || t >= b) return listOf(this)
        return buildList {
            if (top < t) add(PixelRect(left, top, right, t))
            if (b < bottom) add(PixelRect(left, b, right, bottom))
            if (left < l) add(PixelRect(left, t, l, b))
            if (r < right) add(PixelRect(r, t, right, b))
        }
    }
}

internal data class CameraPoint(val x: Double, val y: Double)
internal enum class ClockwiseRotation { NONE, QUARTER, HALF, THREE_QUARTERS }

/** Camera framing only; it never transforms Phone image coordinates or Phone input. */
internal data class CameraSampling(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val crop: PhoneRect = PhoneRect(0.0, 0.0, 1.0, 1.0),
    val rotation: ClockwiseRotation = ClockwiseRotation.NONE,
    val mirror: Boolean = false,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= 1 && crop.bottom <= 1)
        require(crop.width > 0 && crop.height > 0)
    }

    /**
     * Center-fill the actual rendered preview size (after rounded Phone-to-Surface scaling).
     * Input/output origins are top-left; the renderer converts
     * the result to texture coordinates, then applies that frame's SurfaceTexture matrix once.
     * SurfaceTexture matrices and camera/Phone clocks are deliberately absent from this mapping.
     */
    fun sample(u: Double, v: Double, targetWidth: Double, targetHeight: Double): CameraPoint {
        require(targetWidth.isFinite() && targetHeight.isFinite() && targetWidth > 0 && targetHeight > 0)
        val odd = rotation == ClockwiseRotation.QUARTER || rotation == ClockwiseRotation.THREE_QUARTERS
        val w = sourceWidth * crop.width; val h = sourceHeight * crop.height
        val sourceAspect = if (odd) h / w else w / h
        val targetAspect = targetWidth / targetHeight
        var x = 0.5 + (u - 0.5) * min(1.0, targetAspect / sourceAspect)
        val y = 0.5 + (v - 0.5) * min(1.0, sourceAspect / targetAspect)
        if (mirror) x = 1.0 - x
        val p = when (rotation) {
            ClockwiseRotation.NONE -> CameraPoint(x, y)
            ClockwiseRotation.QUARTER -> CameraPoint(y, 1.0 - x)
            ClockwiseRotation.HALF -> CameraPoint(1.0 - x, 1.0 - y)
            ClockwiseRotation.THREE_QUARTERS -> CameraPoint(1.0 - y, x)
        }
        return CameraPoint(crop.left + p.x * crop.width, crop.top + p.y * crop.height)
    }
}
