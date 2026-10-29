package com.code2hack.eyeprojector.directrg

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTextureProbe
import android.opengl.DrawSnapshot
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GlProbe
import android.opengl.GlRect
import android.opengl.GLES11Ext
import android.opengl.TextureBinding
import android.os.HandlerThreadProbe
import android.view.Surface
import android.view.SurfaceProbe
import com.code2hack.eyeprojector.directrg.localcamera.*
import com.code2hack.eyeprojector.protocol.Geometry
import kotlin.math.abs

/** Actual RetainedVideoOutput source + explicit Android host doubles, not EGL/Camera2/device proof. */
private class Observer : LocalCameraObserver {
    val receipts = mutableListOf<LocalPresentationReceipt>()
    val failures = mutableListOf<LocalCameraFailure>()
    var onPresented: (LocalPresentationReceipt) -> Unit = { }
    var onFailed: (LocalCameraFailure) -> Unit = { }
    override fun presented(receipt: LocalPresentationReceipt) { receipts.add(receipt); onPresented(receipt) }
    override fun failed(failure: LocalCameraFailure) { failures.add(failure); onFailed(failure) }
}

private val use = CameraUseKey("fixture-projection", 7)
private fun configuration(sampling: CameraSampling = CameraSampling(800, 600)) = LocalCameraConfiguration(
    CameraSourceKey(use, 12), sampling, requestedBufferWidth = 800, requestedBufferHeight = 600,
)
private fun image(width: Int = 101, height: Int = 73, revision: Long = 9) =
    PhoneImageKey(use.projectionEpoch, 3, revision, Geometry(width, height))
private fun layout(image: PhoneImageKey = image()) = ViewfinderLayout(
    image, use, 23, PhoneRect(12.2, 8.7, 90.6, 65.1), listOf(
        PhoneRect(26.4, 14.2, 57.8, 38.6), PhoneRect(46.1, 27.7, 78.3, 48.4),
        PhoneRect(-10.0, 34.5, 22.1, 80.0), PhoneRect(81.4, 0.0, 120.0, 18.6),
    ),
)
private val phoneMatrix = floatArrayOf(
    0.8f, 0.03f, 0f, 0f, -0.04f, 0.7f, 0f, 0f,
    0f, 0f, 1f, 0f, 0.09f, 0.11f, 0f, 1f,
)
private val cameraMatrix = floatArrayOf(
    0.5f, 0.1f, 0f, 0f, -0.2f, 0.75f, 0f, 0f,
    0f, 0f, 1f, 0f, 0.15f, 0.07f, 0f, 1f,
)

private class Fixture {
    val legacy = mutableListOf<Pair<Surface, Long>>()
    val primaryFailures = mutableListOf<Exception>()
    val output = RetainedVideoOutput({ target, generation -> legacy.add(target to generation) }, primaryFailures::add)
    val phone: SurfaceTexture = output.surface.surfaceTexture!!
    val target = Surface(47, 31, "window-one")

    fun barrier() = output.currentPhoneTextureLatch()
    fun phoneFrame(timestamp: Long = 100, matrix: FloatArray = phoneMatrix): PhoneTextureLatchKey {
        check(phone.emitFrame(timestamp, matrix))
        return checkNotNull(barrier())
    }
    fun localFrame(input: LocalCameraInput, timestamp: Long = 200, matrix: FloatArray = cameraMatrix) {
        check(input.surface.surfaceTexture!!.emitFrame(timestamp, matrix))
        barrier()
    }
    fun bind(layout: ViewfinderLayout, latch: PhoneTextureLatchKey = checkNotNull(barrier())) =
        output.bindFixturePhoneImage(latch, layout.image, layout)

    fun prepared(observer: Observer, config: LocalCameraConfiguration = configuration(),
        layout: ViewfinderLayout = layout()): LocalCameraInput {
        output.attach(target, 11)
        phoneFrame()
        val input = output.openLocalCamera(config, observer)
        localFrame(input)
        check(bind(layout))
        return input
    }

    fun finish() {
        output.close()
        output.close()
        // A close from an observer runs on its owner thread; bounded join observes its FIFO exit.
        for (thread in HandlerThreadProbe.threads) {
            thread.join(2_000)
            check(!thread.isAlive) { "Handler thread survived close: ${thread.name}" }
            check(thread.finished)
        }
        check(HandlerThreadProbe.uncaught.isEmpty()) { "Uncaught owner-thread error: ${HandlerThreadProbe.uncaught}" }
        for (surface in SurfaceProbe.surfaces) {
            equal(if (surface.surfaceTexture == null) 0 else 1, surface.releaseCount, "Surface release count: $surface")
        }
        for (texture in SurfaceTextureProbe.textures) equal(1, texture.releaseCount, "SurfaceTexture release count")
        for (id in GlProbe.createdTextures) equal(1, GlProbe.deletedTextures.count { it == id }, "texture deletion $id")
        for (id in GlProbe.createdPrograms) equal(1, GlProbe.deletedPrograms.count { it == id }, "program deletion $id")
        val createdShaders = GlProbe.events.filter { it.name == "glCreateShader" }.map { it.details.getValue("shader") as Int }
        for (id in createdShaders) equal(1, GlProbe.deletedShaders.count { it == id }, "shader deletion $id")
        check(GlProbe.deletedTextures.all { it in GlProbe.createdTextures }) { "Deleted an unacquired texture" }
        check(GlProbe.events.filter { it.name == "glDeleteProgram" }.all {
            (it.details.getValue("program") as Int) in GlProbe.createdPrograms
        }) { "Deleted an unacquired program" }
        check(GlProbe.events.filter { it.name == "glDeleteShader" }.all {
            (it.details.getValue("shader") as Int) in createdShaders
        }) { "Deleted an unacquired shader" }
        for (context in GlProbe.createdContexts) equal(1, GlProbe.destroyedContexts.count { it === context }, "context deletion")
        for (surface in GlProbe.createdSurfaces) equal(1, GlProbe.destroyedSurfaces.count { it === surface }, "EGLSurface deletion")
        val ownerIds = HandlerThreadProbe.threads.map { it.id }.toSet()
        for (event in GlProbe.events.filter { it.name.startsWith("gl") ||
            (it.name.startsWith("egl") && it.name != "eglGetDisplay") ||
            it.name == "SurfaceTexture.updateTexImage" || it.name == "SurfaceTexture.getTransformMatrix" }) {
            check(event.threadId in ownerIds) { "GL/texture operation escaped its owner: $event" }
        }
    }
}

private fun withFixture(action: (Fixture) -> Unit) {
    GlProbe.reset()
    val fixture = Fixture()
    try { action(fixture) } finally { fixture.finish() }
}
private fun equal(expected: Any?, actual: Any?, label: String) {
    check(expected == actual) { "$label: expected=$expected actual=$actual" }
}
private fun near(expected: Double, actual: Float, label: String) {
    check(abs(expected - actual.toDouble()) < 0.00001) { "$label: expected=$expected actual=$actual" }
}
private fun expectFailure(action: () -> Unit): Exception {
    try { action() } catch (error: Exception) { return error }
    error("Expected operation to fail")
}
private fun quad(draw: DrawSnapshot): List<Float> {
    val position = draw.attributes.getValue("position")
    val coordinate = draw.attributes.getValue("coordinate")
    equal(2, position.size, "position width")
    equal(2, coordinate.size, "coordinate width")
    equal(16, position.stride, "position stride")
    equal(16, coordinate.stride, "coordinate stride")
    equal(0, position.bufferPosition, "position offset")
    equal(2, coordinate.bufferPosition, "coordinate offset")
    check(position.enabled && coordinate.enabled)
    equal(position.allValues, coordinate.allValues, "shared interleaved vertex buffer")
    return position.allValues
}
private fun cameraDraws(input: LocalCameraInput): List<DrawSnapshot> =
    GlProbe.swaps.last().draws.filter { it.texture == input.surface.surfaceTexture!!.textureName }
private fun assertPhoneDraw(draw: DrawSnapshot, fixture: Fixture) {
    equal(fixture.phone.textureName, draw.texture, "Phone texture")
    equal(phoneMatrix.toList(), draw.transform, "Phone matrix preserved")
    check(!draw.scissorEnabled)
    equal(listOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f), quad(draw), "Phone quad preserved")
}

/** Observe API state on its owner thread AT the callback, before a later Phone pass can repair it. */
private fun assertRestoredPhoneState(fixture: Fixture) {
    val state = GlProbe.state()
    val base = GlProbe.draws.last { it.texture == fixture.phone.textureName && it.success }
    equal(base.program, state.program, "Phone program restored before observer")
    equal(GlRect(0, 0, fixture.target.width, fixture.target.height), state.viewport, "full viewport restored")
    check(GLES20.GL_SCISSOR_TEST !in state.enabledCaps) { "Scissor leaked into observer/reentrant work" }
    equal(GLES20.GL_TEXTURE0, state.activeTexture, "sampler unit restored")
    equal(fixture.phone.textureName, state.textures[TextureBinding(GLES20.GL_TEXTURE0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)],
        "Phone texture restored")
    val uniforms = GlProbe.uniformNames.getValue(base.program)
    equal(phoneMatrix.toList(), state.matrices[base.program to uniforms.getValue("transform")], "Phone matrix restored")
    equal(0, state.uniformInts[base.program to uniforms.getValue("image")], "Phone sampler restored")
    for (name in listOf("position", "coordinate")) {
        val index = GlProbe.attributeNames.getValue(base.program).getValue(name)
        val pointer = state.pointers.getValue(index).snapshot(index in state.enabledAttributes)
        equal(base.attributes.getValue(name), pointer, "Phone $name pointer restored")
    }
}

private fun emptyAndIneligibleMasksAndRestoredCallback() = withFixture { f ->
    val observer = Observer()
    observer.onPresented = { assertRestoredPhoneState(f) }
    val config = configuration().copy(requestedBufferWidth = 320, requestedBufferHeight = 240)
    val input = f.prepared(observer, config)
    equal(320, input.surface.surfaceTexture!!.defaultWidth, "requested buffer width distinct from sampling")
    equal(240, input.surface.surfaceTexture!!.defaultHeight, "requested buffer height distinct from sampling")
    equal(800, input.configuration.sampling.sourceWidth, "claimed source geometry remains separate")
    val key = checkNotNull(f.barrier())
    val before = observer.receipts.size
    val legacy = f.legacy.size
    val whole = PhoneRect(0.0, 0.0, 101.0, 73.0)
    check(f.bind(ViewfinderLayout(image(), use, 44, whole, listOf(whole))))
    f.localFrame(input, 1001)
    equal(before, observer.receipts.size, "fully excluded preview cannot report local presentation")
    equal(1, GlProbe.swaps.last().draws.size, "empty mask draws only Phone base")
    val otherUse = use.copy(useGeneration = 8)
    val nextObserver = Observer()
    val next = f.output.openLocalCamera(config.copy(source = CameraSourceKey(otherUse, 12)), nextObserver)
    f.localFrame(next, 1002)
    check(f.bind(layout())) // Layout still names use7, whereas the new source belongs to use8.
    equal(0, nextObserver.receipts.size, "ineligible use cannot report local presentation")
    equal(1, GlProbe.swaps.last().draws.size, "ineligible use draws only Phone base")
    equal(legacy, f.legacy.size, "empty/ineligible local work never calls legacy Phone observer")
    equal(1, f.phone.updateCount, "data-only layout never latches Phone")
    check(f.barrier() === key && f.primaryFailures.isEmpty())
}

private fun coverageAndContinuousUv() = withFixture { f ->
    val observer = Observer()
    val layout = layout()
    val input = f.prepared(observer, layout = layout)
    val swap = GlProbe.swaps.last()
    val draws = cameraDraws(input)
    check(draws.size > 1) { "Overlapping controls must produce several independently recorded cells" }
    equal(1, observer.receipts.size, "fixture bind receipt")
    equal(LocalDrawCause.FIXTURE_LAYOUT, observer.receipts.single().cause, "bind cause")
    check(observer.receipts.single().input === input)
    equal(LayoutCorrespondence.FIXTURE_ASSERTED, observer.receipts.single().fixtureBinding.correspondence, "fixture claim")
    equal("SURFACE_TEXTURE_PRODUCER_UNVERIFIED", observer.receipts.single().localLatch.timestampClockDomain, "producer clock claim")
    equal("GLASSES_SYSTEM_NANO_TIME", observer.receipts.single().swapClockDomain, "swap clock claim")
    assertPhoneDraw(swap.draws.first(), f)
    val fullQuad = quad(draws.first())
    for (draw in draws) {
        check(draw.scissorEnabled)
        equal(fullQuad, quad(draw), "same full-preview geometry/UV across every clip cell")
        equal(cameraMatrix.toList(), draw.transform, "separate camera transform")
        equal(47, draw.viewport.width, "whole-window viewport width")
        equal(31, draw.viewport.height, "whole-window viewport height")
    }
    near(2 * layout.preview.left / 101 - 1, fullQuad[0], "non-fullscreen left edge")
    near(1 - 2 * layout.preview.bottom / 73, fullQuad[1], "non-fullscreen bottom edge")
    near(2 * layout.preview.right / 101 - 1, fullQuad[4], "non-fullscreen right edge")
    near(1 - 2 * layout.preview.top / 73, fullQuad[9], "non-fullscreen top edge")
    // Independent oracle: no replacementScissors(), rectangle subtraction, ceil(), or renderer mapping.
    // Each window pixel center is tested directly against the original half-open Phone rectangles.
    fun inside(rect: PhoneRect, x: Double, y: Double) =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
    var expectedPixels = 0
    for (topY in 0 until swap.height) for (x in 0 until swap.width) {
        val phoneX = (x + 0.5) * layout.image.geometry.width / swap.width
        val phoneY = (topY + 0.5) * layout.image.geometry.height / swap.height
        val expected = inside(layout.preview, phoneX, phoneY) &&
            layout.preservedControls.none { inside(it, phoneX, phoneY) }
        val glY = swap.height - 1 - topY
        val count = draws.count { val s = it.scissor
            x >= s.x && x < s.x + s.width && glY >= s.y && glY < s.y + s.height }
        equal(if (expected) 1 else 0, count, "independent pixel-center coverage at ($x,$topY)")
        if (expected) expectedPixels++
    }
    check(expectedPixels > 0 && expectedPixels < swap.width * swap.height)
    equal(expectedPixels, draws.sumOf { it.scissor.width * it.scissor.height }, "disjoint cell area")
    check(f.primaryFailures.isEmpty() && observer.failures.isEmpty())
}

private fun asymmetricSamplingAndMatrices() = withFixture { f ->
    // Deliberately simple physical aspect: 60x40 Surface, 60x40 Phone preview in a 100x80 image.
    // Rendered preview is 36x20; rotated crop aspect is 360/640, so center-fill retains y=.34375..65625.
    f.target.width = 60; f.target.height = 40
    val sampling = CameraSampling(800, 600, PhoneRect(0.1, 0.2, 0.9, 0.8), ClockwiseRotation.QUARTER, true)
    val layout = ViewfinderLayout(image(100, 80), use, 31, PhoneRect(20.0, 10.0, 80.0, 50.0),
        listOf(PhoneRect(42.0, 21.0, 53.0, 40.0)))
    val input = f.prepared(Observer(), configuration(sampling), layout)
    val draws = cameraDraws(input)
    check(draws.size > 1)
    val expectedUv = listOf(0.625 to 0.8, 0.625 to 0.2, 0.375 to 0.8, 0.375 to 0.2)
    val expectedAfterMatrix = listOf(0.3025 to 0.7325, 0.4225 to 0.2825, 0.1775 to 0.7075, 0.2975 to 0.2575)
    for (draw in draws) {
        val data = quad(draw)
        equal(cameraMatrix.toList(), draw.transform, "nonidentity matrix uploaded exactly once")
        for (i in 0..3) {
            near(expectedUv[i].first, data[i * 4 + 2], "cropped/rotated/mirrored pre-matrix u $i")
            near(expectedUv[i].second, data[i * 4 + 3], "cropped/rotated/mirrored pre-matrix v $i")
            val u = data[i * 4 + 2]; val v = data[i * 4 + 3]; val m = draw.transform
            near(expectedAfterMatrix[i].first, m[0] * u + m[4] * v + m[12], "shader matrix x $i")
            near(expectedAfterMatrix[i].second, m[1] * u + m[5] * v + m[13], "shader matrix y $i")
        }
        near(-0.6, data[0], "preview left")
        near(-0.25, data[1], "preview bottom")
        near(0.6, data[4], "preview right")
        near(0.75, data[9], "preview top")
    }
    assertPhoneDraw(GlProbe.swaps.last().draws.first(), f)
    f.output.redraw(); f.barrier()
    assertPhoneDraw(GlProbe.swaps.last().draws.first(), f)
    check(f.primaryFailures.isEmpty())
}

private fun unboundLocalFramesConsumeWithoutWindowPresentation() = withFixture { f ->
    val observer = Observer()
    observer.onPresented = { assertRestoredPhoneState(f) }
    f.output.attach(f.target, 1)
    val phoneLatch = f.phoneFrame(100)
    val input = f.output.openLocalCamera(configuration(), observer)
    val texture = checkNotNull(input.surface.surfaceTexture)
    val initialDraws = GlProbe.draws.size
    val initialSwaps = GlProbe.swaps.size
    val initialLegacy = f.legacy.size

    val transforms = listOf(0.11f, 0.22f, 0.33f).map { offset ->
        cameraMatrix.copyOf().also { it[12] = offset }
    }
    transforms.forEachIndexed { index, transform -> f.localFrame(input, 201L + index, transform) }
    equal(3, texture.updateCount, "every unbound local frame is consumed")
    equal(initialDraws, GlProbe.draws.size, "unbound local frames do not redraw Phone")
    equal(initialSwaps, GlProbe.swaps.size, "unbound local frames do not swap the window")
    equal(initialLegacy, f.legacy.size, "unbound local frames do not report Phone presentation")
    equal(0, observer.receipts.size, "unbound local frames have no local presentation receipt")
    equal(1, f.phone.updateCount, "local consumption does not relatch Phone")
    check(f.barrier() === phoneLatch && !input.retired)

    check(f.bind(layout(), phoneLatch))
    equal(initialSwaps + 1, GlProbe.swaps.size, "exact fixture binding still presents")
    val bound = observer.receipts.single()
    equal(LocalDrawCause.FIXTURE_LAYOUT, bound.cause, "explicit fixture binding cause")
    check(bound.input === input && bound.fixtureBinding.latch === phoneLatch)
    equal(3L, bound.localLatch.sequence, "fixture uses the latest of all consumed local latches")
    equal(203L, bound.localLatch.surfaceTimestampNanos, "fixture uses the latest consumed local texture")
    val boundDraws = cameraDraws(input)
    check(boundDraws.isNotEmpty())
    for (draw in boundDraws) equal(transforms.last().toList(), draw.transform, "fixture uses latest consumed local matrix")

    f.localFrame(input, 204)
    equal(initialSwaps + 2, GlProbe.swaps.size, "bound local frame still presents")
    equal(2, observer.receipts.size, "bound local frame keeps its presentation receipt")
    equal(LocalDrawCause.CAMERA_LATCH, observer.receipts.last().cause, "bound camera latch cause")
    equal(4L, observer.receipts.last().localLatch.sequence, "bound frame advances original local latch")
    equal(initialLegacy, f.legacy.size, "fixture/local presentations stay separate from Phone receipt")

    val nextPhone = f.phoneFrame(101)
    check(nextPhone !== phoneLatch && f.barrier() === nextPhone)
    equal(initialSwaps + 3, GlProbe.swaps.size, "next Phone frame presents normally")
    equal(initialLegacy + 1, f.legacy.size, "next Phone frame keeps its legacy receipt")
    equal(1, GlProbe.swaps.last().draws.size, "next Phone frame clears the previous fixture binding")
    assertPhoneDraw(GlProbe.swaps.last().draws.single(), f)
    val afterPhoneDraws = GlProbe.draws.size
    for (timestamp in 205L..206L) f.localFrame(input, timestamp)
    equal(6, texture.updateCount, "local frames keep consuming after Phone clears the binding")
    equal(afterPhoneDraws, GlProbe.draws.size, "local frames cannot redraw with the cleared binding")
    equal(initialSwaps + 3, GlProbe.swaps.size, "local frames cannot swap with the cleared binding")
    equal(initialLegacy + 1, f.legacy.size, "cleared local binding cannot report Phone presentation")
    equal(2, observer.receipts.size, "cleared binding cannot report another local presentation")
    check(observer.failures.isEmpty() && f.primaryFailures.isEmpty() && !input.retired)
}

private fun localWorkDoesNotReportLegacyPhone() = withFixture { f ->
    val observer = Observer()
    f.output.attach(f.target, 1)
    val input = f.output.openLocalCamera(configuration(), observer)
    f.localFrame(input)
    equal(0, f.legacy.size, "local frame without Phone")
    equal(0, GlProbe.swaps.size, "no base image to submit")
    f.phoneFrame()
    equal(1, f.legacy.size, "actual Phone frame")
    val before = f.legacy.size
    check(f.bind(layout()))
    f.localFrame(input, 201)
    equal(before, f.legacy.size, "fixture bind/local frame do not report legacy Phone")
    equal(2, observer.receipts.size, "only mask-contributing local swaps")
    input.close()
    equal(before, f.legacy.size, "camera removal does not report legacy Phone")
    equal(2, observer.receipts.size, "removal has no local receipt")
    f.output.redraw(); f.barrier()
    equal(before + 1, f.legacy.size, "legacy redraw remains legacy")
    check(f.primaryFailures.isEmpty())
}

private fun latchIdentityAndInvalidation() = withFixture { f ->
    f.output.attach(f.target, 3)
    val first = f.phoneFrame(999)
    check(f.barrier() === first)
    equal(1L, first.latchSequence, "first actual latch sequence")
    equal(999L, first.surfaceTimestampNanos, "actual Phone timestamp")
    val observer = Observer()
    val input = f.output.openLocalCamera(configuration(), observer)
    f.localFrame(input)
    check(f.bind(layout(), first))
    val forged = PhoneTextureLatchKey(first.outputIdentity, first.latchSequence, first.surfaceTimestampNanos)
    check(!f.bind(layout(), forged)) { "Equal fields must not forge latch identity" }
    val secondOutput = RetainedVideoOutput({ _, _ -> }, { error("Unexpected other-output failure: $it") })
    try {
        check(secondOutput.surface.surfaceTexture!!.emitFrame(999, phoneMatrix))
        val foreign = checkNotNull(secondOutput.currentPhoneTextureLatch())
        check(!f.bind(layout(), foreign)) { "Another output's actual key must be rejected" }
    } finally { secondOutput.close() }
    val count = observer.receipts.size
    val second = f.phoneFrame(999)
    check(second !== first)
    equal(2L, second.latchSequence, "sequence advances even when producer timestamp repeats")
    check(!f.bind(layout(), first))
    equal(1, GlProbe.swaps.last().draws.size, "new Phone latch retires the predecessor's fixture binding")
    equal(count, observer.receipts.size, "new Phone did not assert correspondence")
    check(!input.retired)
    f.localFrame(input, 203)
    equal(count, observer.receipts.size, "local frame cannot revive old correspondence")
    check(f.bind(layout(), second))
    equal(count + 1, observer.receipts.size, "explicit new correspondence")
    f.output.invalidate()
    check(f.barrier() == null)
    check(!f.bind(layout(), second))
    val swaps = GlProbe.swaps.size
    f.output.redraw(); f.barrier()
    equal(swaps, GlProbe.swaps.size, "invalidated Phone cannot draw")
    val third = f.phoneFrame(1000)
    equal(3L, third.latchSequence, "invalidate preserves monotonic sequence")
    check(!f.bind(layout(), second))
}

private fun retainWithoutWindowAndReattach() = withFixture { f ->
    val observer = Observer()
    val key = f.phoneFrame()
    val input = f.output.openLocalCamera(configuration(), observer)
    f.localFrame(input)
    check(f.bind(layout(), key))
    equal(0, GlProbe.swaps.size, "no-target latches remain retained")
    equal(0, f.legacy.size, "no-target has no legacy presentation")
    equal(0, observer.receipts.size, "no-target has no local receipt")
    f.output.attach(f.target, 41)
    equal(1, observer.receipts.size, "retained camera drawn on attach")
    equal(LocalDrawCause.WINDOW_ATTACH, observer.receipts.last().cause, "attach cause")
    equal(41L, observer.receipts.last().windowGeneration, "first window generation")
    val swaps = GlProbe.swaps.size
    f.output.attach(null, 42)
    equal(swaps, GlProbe.swaps.size, "detach does not submit")
    val replacement = Surface(47, 31, "window-two")
    f.output.attach(replacement, 57)
    check(observer.receipts.last().target === replacement)
    equal(57L, observer.receipts.last().windowGeneration, "replacement generation")
    check(f.barrier() === key)
    equal(1, f.phone.updateCount, "attach never relatches Phone")
    equal(1, input.surface.surfaceTexture!!.updateCount, "attach never relatches camera")
    equal(2, f.legacy.size, "both actual window attaches remain legacy")
}

private fun sameTupleReplacementAndStaleCallbacks() = withFixture { f ->
    val originalObserver = Observer()
    val config = configuration()
    val original = f.prepared(originalObserver, config)
    val originalTexture = original.surface.surfaceTexture!!
    val stale = originalTexture.capturePostedFrameCallback()
    val oldUpdates = originalTexture.updateCount
    val oldReceipts = originalObserver.receipts.size
    val nextObserver = Observer()
    val next = f.output.openLocalCamera(config, nextObserver)
    check(original.retired && !next.retired && original !== next)
    equal(config.source, next.configuration.source, "same source tuple")
    check(stale()); f.barrier()
    equal(oldUpdates, originalTexture.updateCount, "stale original callback ignored on GL owner thread")
    equal(oldReceipts, originalObserver.receipts.size, "stale original produces no receipt")
    val events = GlProbe.events.size
    original.close()
    equal(events, GlProbe.events.size, "old close cannot touch successor GL state")
    check(!next.retired)
    f.localFrame(next, 300)
    check(f.bind(layout()))
    check(nextObserver.receipts.single().input === next)
    equal(1, original.surface.releaseCount, "original wrapper exactly once")
    equal(1, originalTexture.releaseCount, "original consumer exactly once")
    f.output.close()
    next.close(); original.close()
    check(!stale()) { "Stopped Handler must reject retained delivery" }
    equal(1, next.surface.releaseCount, "parent then child close exactly once")
}

private fun partialOpenFailures() {
    for (operation in listOf("surfaceTexture", "surface", "compileShader", "createProgram", "linkProgram")) withFixture { f ->
        val observer = Observer()
        val phoneProgram = GlProbe.createdPrograms.single()
        val phoneTexture = f.phone.textureName
        when (operation) {
            "surfaceTexture" -> GlProbe.failures.surfaceTexture = true
            "surface" -> GlProbe.failures.surface = true
            "compileShader" -> GlProbe.failures.compileShader = true
            "createProgram" -> GlProbe.failures.createProgram = true
            "linkProgram" -> GlProbe.failures.linkProgram = true
        }
        expectFailure { f.output.openLocalCamera(configuration(), observer) }
        equal(1, observer.failures.size, "$operation optional open report")
        equal("open", observer.failures.single().operation, "$operation failure operation")
        equal(0, GlProbe.deletedPrograms.count { it == phoneProgram }, "$operation preserves Phone program")
        equal(0, GlProbe.deletedTextures.count { it == phoneTexture }, "$operation preserves Phone texture")
        equal(0, f.phone.releaseCount, "$operation preserves Phone consumer")
        equal(0, f.output.surface.releaseCount, "$operation preserves Phone endpoint")
        for (id in GlProbe.createdTextures.filter { it != phoneTexture })
            equal(1, GlProbe.deletedTextures.count { it == id }, "$operation acquired local texture cleanup")
        for (id in GlProbe.createdPrograms.filter { it != phoneProgram })
            equal(1, GlProbe.deletedPrograms.count { it == id }, "$operation acquired local program cleanup")
        for (texture in SurfaceTextureProbe.textures.filter { it !== f.phone })
            equal(1, texture.releaseCount, "$operation acquired local consumer cleanup")
        check(f.primaryFailures.isEmpty())
        f.output.attach(f.target, 5); f.phoneFrame()
        equal(1, f.legacy.size, "$operation Phone continues")
        val next = f.output.openLocalCamera(configuration(), Observer())
        check(!next.retired)
    }
}

private fun optionalLatchAndMetadataFailures() {
    for (operation in listOf("updateTexImage", "getTransformMatrix")) withFixture { f ->
        val observer = Observer()
        val input = f.prepared(observer)
        val legacy = f.legacy.size
        val receipts = observer.receipts.size
        if (operation == "updateTexImage") GlProbe.failures.updateTexImage = true else GlProbe.failures.getTransformMatrix = true
        f.localFrame(input, 400)
        check(input.retired)
        equal("latch", observer.failures.single().operation, "$operation local failure")
        equal(receipts, observer.receipts.size, "$operation no receipt")
        equal(legacy, f.legacy.size, "$operation no fake legacy callback")
        assertPhoneDraw(GlProbe.swaps.last().draws.single(), f)
        check(f.primaryFailures.isEmpty())
        f.phoneFrame(500)
        equal(legacy + 1, f.legacy.size, "$operation Phone continues")
    }
    withFixture { f ->
        val observer = Observer()
        val input = f.prepared(observer)
        val original = layout()
        val wrong = layout(image(revision = original.image.imageRevision + 1))
        check(!f.output.bindFixturePhoneImage(checkNotNull(f.barrier()), original.image, wrong))
        equal("fixture-metadata", observer.failures.single().operation, "metadata failure local only")
        check(!input.retired && f.primaryFailures.isEmpty())
        val before = f.legacy.size
        f.phoneFrame(501)
        equal(before + 1, f.legacy.size, "metadata failure does not stop Phone")
    }
}

private fun optionalDrawFailureErasesPartialPass() = withFixture { f ->
    val observer = Observer()
    observer.onFailed = { assertRestoredPhoneState(f) }
    f.output.attach(f.target, 9); f.phoneFrame()
    val input = f.output.openLocalCamera(configuration(), observer)
    f.localFrame(input)
    val legacy = f.legacy.size
    GlProbe.failures.drawAtOrdinal = GlProbe.draws.size + 2
    check(f.bind(layout()))
    val swap = GlProbe.swaps.last()
    equal(3, swap.draws.size, "Phone base, failed optional pass, compensating Phone base")
    assertPhoneDraw(swap.draws.first(), f)
    check(!swap.draws[1].success && swap.draws[1].texture == input.surface.surfaceTexture!!.textureName)
    assertPhoneDraw(swap.draws.last(), f)
    check(swap.success && input.retired)
    equal("draw", observer.failures.single().operation, "optional GL error stays local")
    equal(0, observer.receipts.size, "failed optional draw has no receipt")
    equal(legacy, f.legacy.size, "compensation has no legacy callback")
    check(f.primaryFailures.isEmpty())
    f.phoneFrame(600)
    equal(legacy + 1, f.legacy.size, "Phone continues after optional GL error")
}

private fun sharedFailuresStayPrimary() {
    for (operation in listOf("makeCurrent", "windowMakeCurrent", "windowSwap", "cameraContextLost")) withFixture { f ->
        val observer = Observer()
        val input = f.prepared(observer)
        val receipts = observer.receipts.size
        val legacy = f.legacy.size
        when (operation) {
            "makeCurrent" -> { GlProbe.failures.makeCurrent = true; f.localFrame(input, 700) }
            "windowMakeCurrent" -> {
                GlProbe.failures.windowMakeCurrent = true
                GlProbe.failures.makeCurrentError = EGL14.EGL_CONTEXT_LOST
                expectFailure { f.bind(layout()) }
            }
            "windowSwap" -> {
                GlProbe.failures.windowSwap = true
                GlProbe.failures.windowError = EGL14.EGL_BAD_NATIVE_WINDOW
                check(f.bind(layout()))
                check(f.primaryFailures.single() is PresentationSurfaceLost)
                check(!GlProbe.swaps.last().success)
            }
            "cameraContextLost" -> {
                GlProbe.failures.drawAtOrdinal = GlProbe.draws.size + 2
                GlProbe.failures.drawError = GLES20.GL_CONTEXT_LOST
                expectFailure { f.bind(layout()) }
            }
        }
        equal(1, f.primaryFailures.size, "$operation primary failure")
        equal(0, observer.failures.size, "$operation is not an optional camera failure")
        equal(receipts, observer.receipts.size, "$operation has no local receipt")
        equal(legacy, f.legacy.size, "$operation has no legacy success callback")
        check(!input.retired)
        f.phoneFrame(701)
        if (operation == "windowSwap") f.output.attach(Surface(47, 31, "replacement-after-loss"), 10)
        equal(legacy + 1, f.legacy.size, "$operation one-shot failure allows subsequent Phone work")
    }
}

private fun observerFaultIsOptional() = withFixture { f ->
    val observer = Observer()
    val thrown = AssertionError("optional observer fault")
    observer.onPresented = { throw thrown }
    observer.onFailed = { throw AssertionError("optional failure observer fault") }
    val input = f.prepared(observer)
    check(!input.retired)
    equal("observer", observer.failures.single().operation, "observer error classification")
    check(observer.failures.single().error.cause === thrown || (observer.failures.single().error as Throwable) === thrown)
    check(f.primaryFailures.isEmpty())
    val before = f.legacy.size
    f.phoneFrame(800)
    equal(before + 1, f.legacy.size, "observer failure does not stop Phone")
}

private fun observerReentrancePreservesSuccessor() {
    for (mode in listOf("child-close", "replace", "replace-then-fault", "parent-close")) withFixture { f ->
        val observer = Observer()
        var successor: LocalCameraInput? = null
        val nextObserver = Observer()
        observer.onPresented = { receipt ->
            // Mutate only once; a later redraw of a successor must not repeat this action.
            observer.onPresented = { }
            when (mode) {
                "child-close" -> receipt.input.close()
                "replace" -> successor = f.output.openLocalCamera(configuration(), nextObserver)
                "replace-then-fault" -> {
                    successor = f.output.openLocalCamera(configuration(), nextObserver)
                    throw IllegalStateException("observer after replacement")
                }
                "parent-close" -> f.output.close()
            }
        }
        observer.onFailed = { observer.receipts.first().input.close() }
        val original = f.prepared(observer)
        check(original.retired)
        if (successor != null) {
            val next = checkNotNull(successor)
            check(!next.retired)
            f.localFrame(next, 900)
            check(f.bind(layout()))
            check(nextObserver.receipts.single().input === next)
            equal(0, next.surface.releaseCount, "$mode successor endpoint retained")
            equal(0, next.surface.surfaceTexture!!.releaseCount, "$mode successor texture retained")
            original.close()
            check(!next.retired)
        }
        if (mode == "replace-then-fault") equal("observer", original.lastFailure?.operation, "old observer fault remains with old lease")
        if (mode == "parent-close") check(f.barrier() == null)
        check(f.primaryFailures.isEmpty())
    }
    withFixture { f ->
        val observer = Observer()
        val original = f.prepared(observer)
        val nextObserver = Observer()
        var successor: LocalCameraInput? = null
        observer.onFailed = {
            successor = f.output.openLocalCamera(configuration(), nextObserver)
            throw IllegalArgumentException("failure observer after replacement")
        }
        GlProbe.failures.updateTexImage = true
        f.localFrame(original, 901)
        val next = checkNotNull(successor)
        check(original.retired && !next.retired)
        equal("latch", original.lastFailure?.operation, "original fault association")
        check(original.lastFailure!!.error.suppressed.isNotEmpty())
        f.localFrame(next, 902)
        check(f.bind(layout()))
        check(nextObserver.receipts.single().input === next)
        original.close()
        check(!next.retired && f.primaryFailures.isEmpty())
    }
}

fun main() {
    val scenarios = listOf(
        "overlap pixel coverage and continuous full-preview UV" to ::coverageAndContinuousUv,
        "asymmetric crop rotation mirror and separate matrices" to ::asymmetricSamplingAndMatrices,
        "empty and ineligible masks and restored callback GL state" to ::emptyAndIneligibleMasksAndRestoredCallback,
        "unbound local frames consume without window presentation" to ::unboundLocalFramesConsumeWithoutWindowPresentation,
        "local work preserves legacy callback semantics" to ::localWorkDoesNotReportLegacyPhone,
        "actual Phone latch identity sequence and invalidation" to ::latchIdentityAndInvalidation,
        "retained no-target latches detach and new window generation" to ::retainWithoutWindowAndReattach,
        "same-tuple replacement stale callback and exact close" to ::sameTupleReplacementAndStaleCallbacks,
        "partial optional opens clean only acquired resources" to ::partialOpenFailures,
        "optional latch and metadata failures keep Phone active" to ::optionalLatchAndMetadataFailures,
        "optional GL draw fault erases partial mask" to ::optionalDrawFailureErasesPartialPass,
        "shared EGL and GL context failures stay primary" to ::sharedFailuresStayPrimary,
        "optional observer faults stay optional" to ::observerFaultIsOptional,
        "observer reentrance preserves successor and owner lifecycle" to ::observerReentrancePreservesSuccessor,
    )
    for ((name, run) in scenarios) { run(); println("PASS: $name") }
    println("PASS: ${scenarios.size} actual-renderer scenario groups using explicit Android host doubles; no EGL, Camera2, optical, or device proof")
}
