package com.code2hack.eyeprojector.hostprobe

import android.graphics.Insets
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.os.LooperProbe
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewFacts
import android.view.ViewGroup
import android.view.ViewProbe
import android.view.WindowInsets
import kotlin.math.abs

/** Actual exporter source with explicit Android/View observations, not an Android layout engine. */
private fun androidMatrix(vararg values: Float) = Matrix().apply { setValues(values) }
private fun translation(x: Float, y: Float) = androidMatrix(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f)
private fun equal(expected: Any?, actual: Any?, message: String) {
    check(expected == actual) { "$message: expected=$expected actual=$actual" }
}
private fun near(expected: Double, actual: Double, message: String) {
    check(abs(expected - actual) < 0.00001) { "$message: expected=$expected actual=$actual" }
}
private fun expectFailure(action: () -> Unit): Exception {
    try { action() } catch (error: Exception) { return error }
    error("Expected failure")
}
private fun point(x: Double, y: Double) = FixturePoint(x, y)
private fun bounds(l: Double, t: Double, r: Double, b: Double) = FixtureBounds(l, t, r, b)
private fun quad(tl: Pair<Double, Double>, tr: Pair<Double, Double>, br: Pair<Double, Double>, bl: Pair<Double, Double>) =
    FixtureQuad(point(tl.first, tl.second), point(tr.first, tr.second), point(br.first, br.second), point(bl.first, bl.second))

private class Fixture {
    val root = ViewGroup("root", ViewFacts().apply {
        measuredWidth = 1_000; measuredHeight = 800; width = 960; height = 720
        left = 2; top = 3; right = 962; bottom = 723
        windowOrigin = Point(0, 0); screenOrigin = Point(40, 60); globalOffset = Point(230, 310)
        globalVisibleRect = Rect(230, 310, 1190, 1030)
        localToGlobalMatrix = translation(230f, 310f)
        display = Display(3, Surface.ROTATION_180)
        windowVisibleDisplayFrame = Rect(1, 2, 3, 4)
    })
    val decor = ViewGroup("decor", ViewFacts().apply {
        measuredWidth = 900; measuredHeight = 700; width = 880; height = 660
        left = 5; top = 7; right = 885; bottom = 667
        windowOrigin = Point(10, 20); screenOrigin = Point(50, 80); globalOffset = Point(240, 330)
        globalVisibleRect = Rect(240, 330, 1120, 990)
        localToGlobalMatrix = translation(240f, 330f)
        display = Display(5, Surface.ROTATION_270)
        windowVisibleDisplayFrame = Rect(44, 88, 944, 688)
    })
    val ancestor = ViewGroup("ancestor", ViewFacts().apply {
        width = 500; height = 400; left = 11; top = 19; right = 511; bottom = 419
        windowOrigin = Point(50, 60); screenOrigin = Point(90, 120); globalOffset = Point(280, 370)
        globalVisibleRect = Rect(280, 370, 780, 770)
        localToGlobalMatrix = translation(280f, 370f)
    })
    val preview = View("preview", ViewFacts().apply {
        measuredWidth = 321; measuredHeight = 231; width = 300; height = 200
        left = 13; top = 17; right = 313; bottom = 217
        windowOrigin = Point(70, 90); screenOrigin = Point(110, 150); globalOffset = Point(300, 400)
        globalVisibleRect = Rect(310, 420, 570, 580)
        localToGlobalMatrix = translation(300f, 400f)
        display = Display(17, Surface.ROTATION_0)
    })
    // This existing UI object is deliberately not an exporter input or an ancestor.
    val status = View("status-not-an-input")
    val controls = mutableListOf<FixtureControlView>()

    init {
        root.addFixtureChild(decor); decor.addFixtureChild(ancestor); decor.addFixtureChild(status)
        ancestor.addFixtureChild(preview)
        for (view in listOf(root, decor, ancestor, preview, status)) view.facts.root = root
    }

    fun control(key: String, screen: Rect, order: Boolean = true): View {
        val view = View(key, ViewFacts().apply {
            width = screen.width(); height = screen.height()
            measuredWidth = width; measuredHeight = height
            left = 21; top = 27; right = 21 + width; bottom = 27 + height
            screenOrigin = Point(screen.left, screen.top)
            windowOrigin = Point(screen.left - 40, screen.top - 60)
            globalOffset = Point(screen.left + 190, screen.top + 250)
            globalVisibleRect = Rect(screen.left + 190, screen.top + 250, screen.right + 190, screen.bottom + 250)
            localToGlobalMatrix = translation((screen.left + 190).toFloat(), (screen.top + 250).toFloat())
            display = Display(17, Surface.ROTATION_0)
            root = this@Fixture.root
        })
        ancestor.addFixtureChild(view)
        if (order) controls.add(FixtureControlView(key, view))
        return view
    }

    fun snapshot(revision: Long = 411, mapping: FixtureDisplayToEncodedMapping? = null) =
        FixtureLayoutExporter.snapshot(decor, preview, controls, revision, mapping)
}

private fun scenario(action: (Fixture) -> Unit) {
    LooperProbe.reset(); ViewProbe.reset()
    val f = Fixture()
    action(f)
    check(ViewProbe.mutations.isEmpty()) { "Exporter mutated a View: ${ViewProbe.mutations}" }
    check(ViewProbe.reads.none { it.view == f.status.label }) { "Exporter read an unrelated status View" }
}

private fun offsetsClippingAndMeasuredBounds() = scenario { f ->
    val snapshot = f.snapshot(revision = 91)
    val p = snapshot.preview
    equal(91L, snapshot.layoutRevision, "caller layout revision")
    equal(FixtureSize(321, 231), p.measuredSize, "measured dimensions preserved separately")
    equal(FixtureSize(300, 200), p.laidOutSize, "actual laid-out dimensions")
    equal(bounds(13.0, 17.0, 313.0, 217.0), p.transform.parentLayoutBounds, "parent-relative layout bounds")
    equal(point(70.0, 90.0), p.originInWindow, "reported window origin")
    equal(point(110.0, 150.0), p.originOnScreen, "reported screen origin")
    equal(point(40.0, 60.0), p.windowToScreenOffset, "window-to-screen translation")
    equal(FixtureMatrix(1.0, 0.0, 300.0, 0.0, 1.0, 400.0, 0.0, 0.0, 1.0), p.localToGlobalReportedMatrix, "raw global matrix retained")
    equal(point(-190.0, -250.0), p.globalMatrixToScreenOffset, "global matrix reconciled to reported screen origin")
    equal(point(300.0, 400.0), p.globalOffsetReported, "paired root/global offset preserved")
    equal(bounds(310.0, 420.0, 570.0, 580.0), p.visibleBoundsInGlobal, "actual global clipping")
    // Numeric oracle uses independently supplied API outputs, not a duplicate exporter function.
    equal(point(-230.0, -310.0), p.globalToWindowOffset, "global/root is not window")
    equal(point(-190.0, -250.0), p.globalToScreenOffset, "global/root is not screen")
    equal(bounds(80.0, 110.0, 340.0, 270.0), p.visibleBoundsInWindow, "clipped window rectangle")
    equal(bounds(120.0, 170.0, 380.0, 330.0), p.visibleBoundsOnScreen, "clipped screen rectangle")
    equal(quad(110.0 to 150.0, 410.0 to 150.0, 410.0 to 350.0, 110.0 to 350.0), p.layoutQuadOnScreen, "full layout quad")
    equal(quad(70.0 to 90.0, 370.0 to 90.0, 370.0 to 290.0, 70.0 to 290.0), p.layoutQuadInWindow, "full quad in window")
    check(p.layoutQuadOnScreen.axisAlignedBounds != p.visibleBoundsOnScreen)
    check(p.globalVisibleReported)
    equal(FixtureLayoutCorrespondence.LAYOUT_OBSERVED_ONLY, snapshot.correspondence, "layout-only observation")
    check(!snapshot.encodedFrameAssociationEstablished)
    check(!snapshot.actualOcclusionOrTranslucentCoverageObserved)
    check(!snapshot.suppliedControlOrderIsCompleteDrawOrder)
    equal(null, snapshot.callerEncodedMapping, "no implicit encoded mapping")
    equal(null, p.callerMappedGeometry, "no implicit encoded output")
}

private fun rootInsetsVisibleFrameAndRotations() = scenario { f ->
    val rootInsets = WindowInsets().apply {
        insets[WindowInsets.Type.systemBars()] = Insets.of(1, 22, 3, 44)
        ignoringVisibilityInsets[WindowInsets.Type.systemBars()] = Insets.of(5, 26, 7, 48)
        insets[WindowInsets.Type.ime()] = Insets.of(9, 10, 11, 312)
        visible[WindowInsets.Type.ime()] = true
        insets[WindowInsets.Type.displayCutout()] = Insets.of(13, 14, 15, 16)
        insets[WindowInsets.Type.systemGestures()] = Insets.of(17, 18, 19, 20)
    }
    f.root.facts.insets = rootInsets
    f.decor.facts.insets = WindowInsets().apply {
        insets[WindowInsets.Type.systemBars()] = Insets.of(99, 99, 99, 99)
    }
    val snapshot = f.snapshot()
    equal(FixtureSize(900, 700), snapshot.decor.measuredSize, "actual decor")
    equal(FixtureSize(1_000, 800), snapshot.root.measuredSize, "distinct actual root")
    equal(bounds(44.0, 88.0, 944.0, 688.0), snapshot.windowVisibleDisplayFrameOnScreen, "decor visible frame in reported screen space")
    equal(FixtureRootInsets(FixtureEdges(1, 22, 3, 44), FixtureEdges(5, 26, 7, 48),
        FixtureEdges(9, 10, 11, 312), true, FixtureEdges(13, 14, 15, 16), FixtureEdges(17, 18, 19, 20)),
        snapshot.rootWindowInsets, "root typed insets; decor insets not substituted")
    check(ViewProbe.reads.any { it.view == "root" && it.member == "rootWindowInsets" })
    check(ViewProbe.reads.none { it.view == "decor" && it.member == "rootWindowInsets" })
    check(ViewProbe.reads.none { it.member == "getInsetsIgnoringVisibility:${WindowInsets.Type.ime()}" })
    for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
        f.preview.facts.display = Display(17, rotation)
        val p = f.snapshot().preview
        equal(17, p.displayId, "preview display id")
        equal(rotation, p.displayRotation, "reported display rotation")
        equal(FixtureSize(300, 200), p.laidOutSize, "rotation metadata does not swap dimensions")
        equal(bounds(120.0, 170.0, 380.0, 330.0), p.visibleBoundsOnScreen, "rotation metadata does not rewrite coordinates")
        equal(snapshot.preview.layoutQuadOnScreen, p.layoutQuadOnScreen, "rotation metadata does not replace supplied matrix")
    }
    f.root.facts.insets = null
    equal(null, f.snapshot().rootWindowInsets, "missing root insets remain missing")
    f.root.facts.insets = WindowInsets()
    val zero = checkNotNull(f.snapshot().rootWindowInsets)
    equal(FixtureEdges(0, 0, 0, 0), zero.systemBars, "reported zero insets remain present")
    check(!zero.imeVisible)
}

private fun transformedQuadsAndAncestorProvenance() = scenario { f ->
    f.preview.facts.apply {
        width = 12; height = 8
        matrix = androidMatrix(1.25f, 0.5f, 7f, -0.25f, 0.75f, -9f, 0f, 0f, 1f)
        localToGlobalMatrix = androidMatrix(0f, -2f, 720f, 3f, 0f, 910f, 0f, 0f, 1f)
        screenOrigin = Point(400, 100); windowOrigin = Point(360, 40)
        rotation = 35f; rotationX = -10f; rotationY = 20f
        scaleX = 1.2f; scaleY = 0.7f; translationX = 6.5f; translationY = -4.25f; translationZ = 2f
        pivotX = 3f; pivotY = 5f; scrollX = 7; scrollY = -8; elevation = 9f; z = 11f
    }
    f.ancestor.facts.matrix = androidMatrix(2f, 0.25f, 30f, 0.125f, 3f, 40f, 0f, 0f, 1f)
    f.decor.facts.matrix = translation(11f, 12f)
    f.root.facts.matrix = translation(21f, 22f)
    val p = f.snapshot().preview
    equal(FixtureMatrix(0.0, -2.0, 400.0, 3.0, 0.0, 100.0, 0.0, 0.0, 1.0), p.localToScreenMatrix, "global shape translated to independently observed screen origin")
    equal(quad(400.0 to 100.0, 400.0 to 136.0, 384.0 to 136.0, 384.0 to 100.0), p.layoutQuadOnScreen, "numeric transformed-corner oracle")
    equal(bounds(384.0, 100.0, 400.0, 136.0), p.layoutQuadOnScreen.axisAlignedBounds, "rotated/scaled AABB")
    equal(quad(360.0 to 40.0, 360.0 to 76.0, 344.0 to 76.0, 344.0 to 40.0), p.layoutQuadInWindow, "transformed quad keeps window translation")
    equal(FixtureMatrix(1.25, 0.5, 7.0, -0.25, 0.75, -9.0, 0.0, 0.0, 1.0), p.transform.localMatrix, "local matrix retained independently")
    equal(35f, p.transform.rotation, "rotation property retained")
    equal(-10f, p.transform.rotationX, "rotationX property retained")
    equal(20f, p.transform.rotationY, "rotationY property retained")
    equal(1.2f, p.transform.scaleX, "scaleX property retained")
    equal(0.7f, p.transform.scaleY, "scaleY property retained")
    equal(6.5f, p.transform.translationX, "translationX property retained")
    equal(-4.25f, p.transform.translationY, "translationY property retained")
    equal(2f, p.transform.translationZ, "translationZ property retained")
    equal(3f, p.transform.pivotX, "pivotX property retained")
    equal(5f, p.transform.pivotY, "pivotY property retained")
    equal(7, p.transform.scrollX, "scrollX property retained")
    equal(-8, p.transform.scrollY, "scrollY property retained")
    equal(9f, p.transform.elevation, "elevation retained")
    equal(11f, p.transform.z, "z retained")
    equal(0, p.transform.siblingIndex, "actual parent sibling index")
    equal(3, p.ancestorTransforms.size, "all View ancestors")
    equal(FixtureMatrix(2.0, 0.25, 30.0, 0.125, 3.0, 40.0, 0.0, 0.0, 1.0), p.ancestorTransforms[0].localMatrix, "immediate parent first")
    equal(11.0, p.ancestorTransforms[1].localMatrix.tx, "decor next")
    equal(21.0, p.ancestorTransforms[2].localMatrix.tx, "root last")
    equal(null, p.ancestorTransforms.last().siblingIndex, "root has no invented sibling index")
    check(ViewProbe.reads.any { it.view == "preview" && it.member == "transformMatrixToGlobal" })

    f.preview.facts.apply {
        width = 4; height = 2
        localToGlobalMatrix = androidMatrix(127f, 0f, 510f, 150f, 3f, 620f, 0.25f, 0f, 1f)
        screenOrigin = Point(10, 20); windowOrigin = Point(-30, -40)
    }
    val perspective = f.snapshot().preview
    equal(quad(10.0 to 20.0, 9.0 to 10.0, 9.0 to 13.0, 10.0 to 26.0), perspective.layoutQuadOnScreen, "numeric homogeneous-divide corner oracle")
    near(0.25, perspective.localToScreenMatrix.px, "perspective provenance retained")
}

private fun visibilityFlagsAreFactsNotFallbackMasks() = scenario { f ->
    val invisibleCases = listOf(
        Triple(View.INVISIBLE, false, true), Triple(View.GONE, false, true),
        Triple(View.VISIBLE, false, true), Triple(View.VISIBLE, true, false),
    )
    for ((visibility, shown, attached) in invisibleCases) {
        f.preview.facts.apply {
            this.visibility = visibility; this.shown = shown; this.attached = attached
            windowVisibility = View.INVISIBLE; laidOut = false; globalVisible = false
            globalVisibleRect = Rect(777, 888, 999, 1_000)
        }
        val p = f.snapshot().preview
        equal(visibility, p.visibility, "original visibility flag")
        equal(shown, p.shown, "original shown flag")
        equal(attached, p.attachedToWindow, "original attachment flag")
        equal(View.INVISIBLE, p.windowVisibility, "original window visibility")
        check(!p.laidOut && !p.globalVisibleReported)
        equal(null, p.visibleBoundsInGlobal, "failed global observation does not expose stale Rect")
        equal(null, p.visibleBoundsInWindow, "no invented window visibility")
        equal(null, p.visibleBoundsOnScreen, "no full-screen fallback mask")
        equal(null, p.globalToWindowOffset, "no invented valid global/window translation")
        equal(null, p.globalToScreenOffset, "no invented valid global/screen translation")
        equal(point(300.0, 400.0), p.globalOffsetReported, "reported offset retained even without rectangle")
        equal(FixtureSize(300, 200), p.laidOutSize, "detachment does not replace measured layout with display")
        equal(quad(110.0 to 150.0, 410.0 to 150.0, 410.0 to 350.0, 110.0 to 350.0), p.layoutQuadOnScreen, "layout provenance remains independent of visibility")
    }
    f.preview.facts.apply {
        visibility = View.VISIBLE; windowVisibility = View.VISIBLE; shown = true; attached = true
        laidOut = true; alpha = 0f; globalVisible = true; globalVisibleRect = Rect(310, 420, 570, 580)
        display = null
    }
    f.root.facts.insets = null
    val transparent = f.snapshot()
    equal(0f, transparent.preview.alpha, "alpha copied; no geometric opacity assertion")
    check(transparent.preview.globalVisibleReported)
    equal(bounds(120.0, 170.0, 380.0, 330.0), transparent.preview.visibleBoundsOnScreen, "reported geometry not erased by alpha")
    equal(null, transparent.preview.displayId, "missing display not borrowed from decor/root")
    equal(null, transparent.preview.displayRotation, "missing rotation preserved")
    equal(null, transparent.rootWindowInsets, "missing insets preserved")
    check(!transparent.actualOcclusionOrTranslucentCoverageObserved)
}

private fun overlappingControlsAndHalfOpenIntersection() = scenario { f ->
    val a = f.control("control-a", Rect(130, 180, 210, 240), order = false)
    val b = f.control("control-b", Rect(190, 210, 300, 280), order = false)
    val touching = f.control("touches-right-edge", Rect(380, 190, 420, 220), order = false)
    val outside = f.control("outside-preview", Rect(0, 0, 20, 20), order = false)
    val noRectangle = f.control("unreported-visible", Rect(130, 180, 210, 240), order = false)
    noRectangle.facts.globalVisible = false
    a.facts.z = 9f; b.facts.z = 2f
    f.controls.addAll(listOf(FixtureControlView("b", b), FixtureControlView("a", a),
        FixtureControlView("touch", touching), FixtureControlView("out", outside), FixtureControlView("unknown", noRectangle)))
    val controls = f.snapshot().controls
    equal(listOf("b", "a", "touch", "out", "unknown"), controls.map { it.view.key }, "caller order preserved, no Z sort")
    equal(listOf(0, 1, 2, 3, 4), controls.map { it.suppliedOrder }, "explicit supplied order")
    equal(listOf(2, 1, 3, 4, 5), controls.map { it.view.transform.siblingIndex }, "independent actual sibling indices")
    equal(2f, controls[0].view.transform.z, "B z preserved")
    equal(9f, controls[1].view.transform.z, "A z preserved")
    equal(listOf(true, true, false, false, null), controls.map { it.intersectsPreviewVisibleBoundsOnScreen }, "half-open intersections and absent geometry")
    equal(bounds(190.0, 210.0, 300.0, 280.0), controls[0].view.visibleBoundsOnScreen, "overlapping B retained intact")
    equal(bounds(130.0, 180.0, 210.0, 240.0), controls[1].view.visibleBoundsOnScreen, "overlapping A retained intact")
    equal(bounds(0.0, 0.0, 20.0, 20.0), controls[3].view.visibleBoundsOnScreen, "outside control retained intact")
    equal(null, controls[4].view.visibleBoundsOnScreen, "invisible control retained with absent geometry")
    check(!f.snapshot().suppliedControlOrderIsCompleteDrawOrder)
    f.preview.facts.globalVisible = false
    check(f.snapshot().controls.all { it.intersectsPreviewVisibleBoundsOnScreen == null })
}

private fun explicitCallerMappingAndNoFrameJoin() = scenario { f ->
    val noMapping = f.snapshot(revision = 123_456_789)
    equal(null, noMapping.callerEncodedMapping, "default mapping absent")
    check(listOf(noMapping.decor, noMapping.root, noMapping.preview).all { it.callerMappedGeometry == null })
    val mapping = FixtureDisplayToEncodedMapping(99, 640, 480, "caller calibration 411; screen pixels -> encoded coordinates",
        0.5, 0.25, -10.0, -0.1, 0.75, 20.0)
    val snapshot = f.snapshot(revision = 123_456_789, mapping = mapping)
    val mapped = checkNotNull(snapshot.preview.callerMappedGeometry)
    equal(mapping, snapshot.callerEncodedMapping, "explicit mapping and provenance retained")
    equal(mapping.provenance, mapped.mapping.provenance, "caller provenance")
    equal(17, mapped.observedDisplayId, "actual display retained")
    check(!mapped.mappingDisplayIdMatchesObservation) { "Mismatched caller display must not be silently relabelled" }
    equal(quad(82.5 to 121.5, 232.5 to 91.5, 282.5 to 241.5, 132.5 to 271.5), mapped.layoutQuad, "numeric affine full-layout oracle")
    equal(quad(92.5 to 135.5, 222.5 to 109.5, 262.5 to 229.5, 132.5 to 255.5), mapped.visibleBoundsQuad, "numeric affine clipped-bounds oracle")
    equal(FixtureEncodedCorrespondence.CALLER_MAPPING_ONLY_NO_IMAGE_ASSOCIATION, mapped.correspondence, "mapping is arithmetic only")
    equal(FixtureLayoutCorrespondence.LAYOUT_OBSERVED_ONLY, snapshot.correspondence, "no frame/image revision association")
    equal(noMapping.layoutRevision, snapshot.layoutRevision, "caller revision preserved without generated frame identity")
    check(!snapshot.encodedFrameAssociationEstablished)
    val matching = f.snapshot(mapping = mapping.copy(sourceDisplayId = 17))
    check(matching.preview.callerMappedGeometry!!.mappingDisplayIdMatchesObservation)
    check(!matching.encodedFrameAssociationEstablished) { "Matching display is still not an encoded frame join" }
    f.preview.facts.display = null
    val unknownDisplay = f.snapshot(mapping = mapping).preview.callerMappedGeometry!!
    equal(null, unknownDisplay.observedDisplayId, "mapping cannot invent display identity")
    check(!unknownDisplay.mappingDisplayIdMatchesObservation)
}

private fun immutableDetachedSnapshot() = scenario { f ->
    val control = f.control("control", Rect(130, 180, 210, 240))
    val originalInsets = WindowInsets().apply {
        insets[WindowInsets.Type.systemBars()] = Insets.of(1, 2, 3, 4)
    }
    f.root.facts.insets = originalInsets
    val snapshot = f.snapshot()
    val before = snapshot.toString()
    f.controls.clear()
    f.preview.facts.apply {
        measuredWidth = 999; width = 888; left = -50; screenOrigin.x = 777; windowOrigin.y = 666
        globalOffset.x = -555; globalVisibleRect.left = 444; alpha = 0.123f; display = null
        matrix.setValues(floatArrayOf(3f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 1f))
        localToGlobalMatrix.setValues(floatArrayOf(9f, 0f, 8f, 0f, 7f, 6f, 0f, 0f, 1f))
    }
    control.facts.globalVisibleRect.right = 3
    f.ancestor.facts.matrix.setValues(floatArrayOf(5f, 0f, 4f, 0f, 3f, 2f, 0f, 0f, 1f))
    f.ancestor.children.clear()
    originalInsets.insets[WindowInsets.Type.systemBars()] = Insets.of(99, 99, 99, 99)
    f.decor.facts.windowVisibleDisplayFrame.set(9, 8, 7, 6)
    equal(before, snapshot.toString(), "returned snapshot detached from every mutable input")
    equal(1, snapshot.controls.size, "submitted control list copied")
    equal(3, snapshot.preview.ancestorTransforms.size, "ancestor list copied")
    val controlsError = expectFailure { (snapshot.controls as MutableList<FixtureControlObservation>).clear() }
    check(controlsError is UnsupportedOperationException)
    val ancestorsError = expectFailure { (snapshot.preview.ancestorTransforms as MutableList<FixtureTransformObservation>).clear() }
    check(ancestorsError is UnsupportedOperationException)
    equal(before, snapshot.toString(), "immutable collection rejection leaves snapshot unchanged")
}

private fun uiThreadContractAndNoStatusFeedback() = scenario { f ->
    for (noLooper in listOf(false, true)) {
        ViewProbe.reset(); LooperProbe.onMain = false; LooperProbe.noLooper = noLooper
        val error = expectFailure { f.snapshot() }
        check(error is IllegalStateException)
        check(ViewProbe.reads.isEmpty()) { "UI-thread check must precede all View access" }
        check(ViewProbe.mutations.isEmpty())
    }
    LooperProbe.reset(); ViewProbe.reset()
    f.control("explicit-control", Rect(130, 180, 210, 240))
    val snapshot = f.snapshot()
    equal(Thread.currentThread().name, snapshot.observationThread, "observation thread provenance")
    val allowed = setOf("root", "decor", "ancestor", "preview", "explicit-control", "WindowInsets")
    check(ViewProbe.reads.all { it.view in allowed }) { "Exporter read an unrelated View: ${ViewProbe.reads}" }
    check(ViewProbe.reads.none { it.view == "status-not-an-input" })
    check(ViewProbe.mutations.isEmpty()) { "No setter, layout, invalidate, or status feedback" }
}

fun main() {
    val cases = listOf(
        "global/root window screen offsets, actual clipping and measured bounds" to ::offsetsClippingAndMeasuredBounds,
        "distinct decor/root, visible frame, typed insets and four display rotations" to ::rootInsetsVisibleFrameAndRotations,
        "transformed and perspective corners with immediate-parent-first provenance" to ::transformedQuadsAndAncestorProvenance,
        "visibility attachment alpha and missing observations without fallback" to ::visibilityFlagsAreFactsNotFallbackMasks,
        "overlapping and outside controls, order Z and half-open intersections" to ::overlappingControlsAndHalfOpenIntersection,
        "explicit affine caller mapping provenance without encoded-frame join" to ::explicitCallerMappingAndNoFrameJoin,
        "detached immutable snapshot and copied collections" to ::immutableDetachedSnapshot,
        "UI-thread precondition and no unrelated status/layout feedback" to ::uiThreadContractAndNoStatusFeedback,
    )
    for ((name, run) in cases) { run(); println("PASS: $name") }
    println("PASS: ${cases.size} actual-exporter groups with explicit Android/View doubles; no Android runtime, captured-frame, encoded-image, display-optical or device proof")
}
