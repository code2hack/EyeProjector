package com.code2hack.eyeprojector.hostprobe

import android.graphics.Insets
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import java.util.Collections

internal data class FixturePoint(val x: Double, val y: Double)
internal data class FixtureSize(val width: Int, val height: Int)
internal data class FixtureBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class FixtureEdges(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class FixtureQuad(
    val topLeft: FixturePoint, val topRight: FixturePoint,
    val bottomRight: FixturePoint, val bottomLeft: FixturePoint,
) {
    val axisAlignedBounds: FixtureBounds get() = FixtureBounds(
        minOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x),
        minOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y),
        maxOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x),
        maxOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y),
    )
}

/** Android Matrix row-major value order, copied into scalars; no mutable Matrix escapes. */
internal data class FixtureMatrix(
    val xx: Double, val xy: Double, val tx: Double,
    val yx: Double, val yy: Double, val ty: Double,
    val px: Double, val py: Double, val pw: Double,
) {
    fun map(x: Double, y: Double): FixturePoint {
        val denominator = px * x + py * y + pw
        return FixturePoint((xx * x + xy * y + tx) / denominator,
            (yx * x + yy * y + ty) / denominator)
    }
}

/** Caller-supplied arithmetic in reported SCREEN pixels, never an observed encoded-image join. */
internal data class FixtureDisplayToEncodedMapping(
    val sourceDisplayId: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val provenance: String,
    val xx: Double, val xy: Double, val tx: Double,
    val yx: Double, val yy: Double, val ty: Double,
) {
    fun map(point: FixturePoint) = FixturePoint(xx * point.x + xy * point.y + tx,
        yx * point.x + yy * point.y + ty)
}

internal enum class FixtureLayoutCorrespondence { LAYOUT_OBSERVED_ONLY }
internal enum class FixtureEncodedCorrespondence { CALLER_MAPPING_ONLY_NO_IMAGE_ASSOCIATION }
internal data class FixtureCallerMappedGeometry(
    val mapping: FixtureDisplayToEncodedMapping,
    val observedDisplayId: Int?,
    // A reported fact, not an assertion that the caller's mapping is geometrically correct.
    val mappingDisplayIdMatchesObservation: Boolean,
    val layoutQuad: FixtureQuad,
    val visibleBoundsQuad: FixtureQuad?,
) {
    val correspondence = FixtureEncodedCorrespondence.CALLER_MAPPING_ONLY_NO_IMAGE_ASSOCIATION
}

internal data class FixtureTransformObservation(
    val parentLayoutBounds: FixtureBounds,
    val localMatrix: FixtureMatrix,
    val rotation: Float, val rotationX: Float, val rotationY: Float,
    val scaleX: Float, val scaleY: Float,
    val translationX: Float, val translationY: Float, val translationZ: Float,
    val pivotX: Float, val pivotY: Float,
    val scrollX: Int, val scrollY: Int,
    val elevation: Float, val z: Float,
    val siblingIndex: Int?,
)

internal data class FixtureViewObservation(
    val key: String,
    val measuredSize: FixtureSize,
    val laidOutSize: FixtureSize,
    val originInWindow: FixturePoint,
    val originOnScreen: FixturePoint,
    val windowToScreenOffset: FixturePoint,
    val visibility: Int,
    val windowVisibility: Int,
    val shown: Boolean,
    val attachedToWindow: Boolean,
    val laidOut: Boolean,
    val alpha: Float,
    val displayId: Int?,
    val displayRotation: Int?,
    val transform: FixtureTransformObservation,
    val ancestorTransforms: List<FixtureTransformObservation>,
    val localToGlobalReportedMatrix: FixtureMatrix,
    val globalMatrixToScreenOffset: FixturePoint,
    val localToScreenMatrix: FixtureMatrix,
    val layoutQuadOnScreen: FixtureQuad,
    val layoutQuadInWindow: FixtureQuad,
    val globalVisibleReported: Boolean,
    val globalOffsetReported: FixturePoint,
    val visibleBoundsInGlobal: FixtureBounds?,
    val globalToWindowOffset: FixturePoint?,
    val globalToScreenOffset: FixturePoint?,
    val visibleBoundsInWindow: FixtureBounds?,
    val visibleBoundsOnScreen: FixtureBounds?,
    val callerMappedGeometry: FixtureCallerMappedGeometry?,
)

/** Input-only references; no View reference is retained in the returned snapshot. */
internal data class FixtureControlView(val key: String, val view: View)
internal data class FixtureControlObservation(
    val view: FixtureViewObservation,
    val suppliedOrder: Int,
    // Null means at least one View had no reported global-visible rectangle.
    val intersectsPreviewVisibleBoundsOnScreen: Boolean?,
)
internal data class FixtureRootInsets(
    val systemBars: FixtureEdges,
    val systemBarsIgnoringVisibility: FixtureEdges,
    val ime: FixtureEdges,
    val imeVisible: Boolean,
    val displayCutout: FixtureEdges,
    val systemGestures: FixtureEdges,
)
internal data class FixtureLayoutSnapshot(
    val layoutRevision: Long,
    val decor: FixtureViewObservation,
    val root: FixtureViewObservation,
    val preview: FixtureViewObservation,
    val controls: List<FixtureControlObservation>,
    /** getWindowVisibleDisplayFrame result, in its reported screen coordinate space. */
    val windowVisibleDisplayFrameOnScreen: FixtureBounds,
    /** Null preserves the distinction between absent root insets and reported zero insets. */
    val rootWindowInsets: FixtureRootInsets?,
    val callerEncodedMapping: FixtureDisplayToEncodedMapping?,
    val observationThread: String,
) {
    val correspondence = FixtureLayoutCorrespondence.LAYOUT_OBSERVED_ONLY
    val encodedFrameAssociationEstablished = false
    val actualOcclusionOrTranslucentCoverageObserved = false
    val suppliedControlOrderIsCompleteDrawOrder = false
}

/**
 * A synchronous UI-thread observation of explicit cooperating-fixture View references.
 *
 * This creates no listener, timer, UI/status write or automatic publication. layoutRevision is
 * supplied by the caller and is never a Phone image/frame revision. Android's global-visible
 * rectangles are geometric axis-aligned clipping observations, not sibling occlusion, translucent
 * coverage or exact rotated shape masks. Local/ancestor and accumulated matrices preserve the
 * transform provenance; even the mapped corners do not identify captured/encoded pixels.
 */
internal object FixtureLayoutExporter {
    fun snapshot(
        decor: View,
        preview: View,
        controls: List<FixtureControlView>,
        layoutRevision: Long,
        encodedMapping: FixtureDisplayToEncodedMapping? = null,
    ): FixtureLayoutSnapshot {
        check(Looper.myLooper() === Looper.getMainLooper()) { "View layout snapshot requires the UI thread" }
        val decorObservation = observe("decor", decor, encodedMapping)
        val root = decor.rootView
        val rootObservation = observe("root", root, encodedMapping)
        val previewObservation = observe("preview", preview, encodedMapping)
        val controlObservations = controls.mapIndexed { index, input ->
            val observed = observe(input.key, input.view, encodedMapping)
            val a = observed.visibleBoundsOnScreen
            val b = previewObservation.visibleBoundsOnScreen
            FixtureControlObservation(observed, index,
                if (a == null || b == null) null else a.left < b.right && b.left < a.right &&
                    a.top < b.bottom && b.top < a.bottom)
        }
        val visibleFrame = Rect()
        decor.getWindowVisibleDisplayFrame(visibleFrame)
        return FixtureLayoutSnapshot(layoutRevision, decorObservation, rootObservation, previewObservation,
            immutable(controlObservations), visibleFrame.copyBounds(), root.rootWindowInsets?.let(::observeInsets),
            encodedMapping, Thread.currentThread().name)
    }

    private fun observe(key: String, view: View, mapping: FixtureDisplayToEncodedMapping?): FixtureViewObservation {
        val inWindow = IntArray(2); view.getLocationInWindow(inWindow)
        val onScreen = IntArray(2); view.getLocationOnScreen(onScreen)
        val windowOrigin = FixturePoint(inWindow[0].toDouble(), inWindow[1].toDouble())
        val screenOrigin = FixturePoint(onScreen[0].toDouble(), onScreen[1].toDouble())
        val windowToScreen = screenOrigin - windowOrigin
        val visible = Rect()
        val offset = Point()
        val globallyVisible = view.getGlobalVisibleRect(visible, offset)
        val globalOffset = FixturePoint(offset.x.toDouble(), offset.y.toDouble())
        // getGlobalVisibleRect's rectangle/offset are global/root coordinates, NOT screen pixels.
        // Derive each translation from that View's paired reported origin and global offset.
        val globalToWindow = if (globallyVisible) windowOrigin - globalOffset else null
        val globalToScreen = if (globallyVisible) screenOrigin - globalOffset else null
        val globalBounds = if (globallyVisible) visible.copyBounds() else null
        val screenBounds = globalBounds?.translated(screenOrigin - globalOffset)
        val windowBounds = globalBounds?.translated(windowOrigin - globalOffset)
        val width = view.width; val height = view.height
        val globalMatrix = Matrix()
        view.transformMatrixToGlobal(globalMatrix)
        val reportedGlobal = globalMatrix.copyValues()
        // Do not equate an API's global space with display/screen space. Anchor the complete
        // transformed shape at the independently reported screen origin, retaining both facts.
        val matrixToScreen = screenOrigin - reportedGlobal.map(0.0, 0.0)
        val accumulated = reportedGlobal.translated(matrixToScreen)
        val quadOnScreen = FixtureQuad(accumulated.map(0.0, 0.0), accumulated.map(width.toDouble(), 0.0),
            accumulated.map(width.toDouble(), height.toDouble()), accumulated.map(0.0, height.toDouble()))
        val display = view.display
        val displayId = display?.displayId
        val ancestors = mutableListOf<FixtureTransformObservation>()
        var parent = view.parent as? View
        while (parent != null) {
            ancestors.add(observeTransform(parent))
            parent = parent.parent as? View
        }
        val mapped = mapping?.let { supplied -> FixtureCallerMappedGeometry(supplied, displayId,
            displayId == supplied.sourceDisplayId, quadOnScreen.mapped(supplied::map),
            screenBounds?.quad()?.mapped(supplied::map)) }
        return FixtureViewObservation(key, FixtureSize(view.measuredWidth, view.measuredHeight),
            FixtureSize(width, height), windowOrigin, screenOrigin, windowToScreen,
            view.visibility, view.windowVisibility, view.isShown, view.isAttachedToWindow, view.isLaidOut,
            view.alpha, displayId, display?.rotation, observeTransform(view), immutable(ancestors),
            reportedGlobal, matrixToScreen, accumulated,
            quadOnScreen, quadOnScreen.mapped { it - windowToScreen }, globallyVisible, globalOffset,
            globalBounds, globalToWindow, globalToScreen, windowBounds, screenBounds, mapped)
    }

    private fun observeTransform(view: View) = FixtureTransformObservation(
        FixtureBounds(view.left.toDouble(), view.top.toDouble(), view.right.toDouble(), view.bottom.toDouble()),
        view.matrix.copyValues(), view.rotation, view.rotationX, view.rotationY, view.scaleX, view.scaleY,
        view.translationX, view.translationY, view.translationZ, view.pivotX, view.pivotY,
        view.scrollX, view.scrollY, view.elevation, view.z, (view.parent as? ViewGroup)?.indexOfChild(view),
    )

    private fun observeInsets(insets: WindowInsets) = FixtureRootInsets(
        insets.getInsets(WindowInsets.Type.systemBars()).copyEdges(),
        insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).copyEdges(),
        insets.getInsets(WindowInsets.Type.ime()).copyEdges(), insets.isVisible(WindowInsets.Type.ime()),
        insets.getInsets(WindowInsets.Type.displayCutout()).copyEdges(),
        insets.getInsets(WindowInsets.Type.systemGestures()).copyEdges(),
    )

    private fun Matrix.copyValues(): FixtureMatrix {
        val values = FloatArray(9)
        getValues(values)
        return FixtureMatrix(values[0].toDouble(), values[1].toDouble(), values[2].toDouble(),
            values[3].toDouble(), values[4].toDouble(), values[5].toDouble(),
            values[6].toDouble(), values[7].toDouble(), values[8].toDouble())
    }
    private fun Rect.copyBounds() = FixtureBounds(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())
    private fun Insets.copyEdges() = FixtureEdges(left, top, right, bottom)
    private fun FixtureMatrix.translated(offset: FixturePoint) = FixtureMatrix(
        xx + offset.x * px, xy + offset.x * py, tx + offset.x * pw,
        yx + offset.y * px, yy + offset.y * py, ty + offset.y * pw, px, py, pw,
    )
    private operator fun FixturePoint.minus(other: FixturePoint) = FixturePoint(x - other.x, y - other.y)
    private fun FixtureBounds.translated(offset: FixturePoint) =
        FixtureBounds(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y)
    private fun FixtureBounds.quad() = FixtureQuad(FixturePoint(left, top), FixturePoint(right, top),
        FixturePoint(right, bottom), FixturePoint(left, bottom))
    private fun FixtureQuad.mapped(map: (FixturePoint) -> FixturePoint) =
        FixtureQuad(map(topLeft), map(topRight), map(bottomRight), map(bottomLeft))
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
