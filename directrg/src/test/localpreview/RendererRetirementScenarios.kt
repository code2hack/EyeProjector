package com.code2hack.eyeprojector.directrg

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GlProbe
import android.opengl.GlRect
import android.opengl.TextureBinding
import android.os.Handler
import android.os.HandlerThread
import android.os.HandlerThreadProbe
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.*
import com.code2hack.eyeprojector.protocol.Geometry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Manual original-producer fences test actual renderer source, not Camera2/native quiescence. */
private class RetirementObserver439 : LocalCameraObserver {
    val receipts = mutableListOf<LocalPresentationReceipt>()
    val failures = mutableListOf<LocalCameraFailure>()
    var onFailure: (LocalCameraFailure) -> Unit = { }
    override fun presented(receipt: LocalPresentationReceipt) { receipts.add(receipt) }
    override fun failed(failure: LocalCameraFailure) { failures.add(failure); onFailure(failure) }
}

private val retirementUse439 = CameraUseKey("retirement-439", 4)
private val retirementConfiguration439 = LocalCameraConfiguration(CameraSourceKey(retirementUse439, 8),
    CameraSampling(800, 600), 800, 600)
private val retirementImage439 = PhoneImageKey("retirement-439", 3, 19, Geometry(101, 73))
private val retirementLayout439 = ViewfinderLayout(retirementImage439, retirementUse439, 29,
    PhoneRect(12.2, 8.7, 90.6, 65.1), listOf(PhoneRect(26.4, 14.2, 57.8, 38.6), PhoneRect(46.1, 27.7, 78.3, 48.4)))
private val retirementPhoneMatrix439 = floatArrayOf(
    0.8f, 0.03f, 0f, 0f, -0.04f, 0.7f, 0f, 0f,
    0f, 0f, 1f, 0f, 0.09f, 0.11f, 0f, 1f,
)
private fun equal439(expected: Any?, actual: Any?, message: String) {
    check(expected == actual) { "$message: expected=$expected actual=$actual" }
}
private fun failure439(action: () -> Unit): Exception {
    try { action() } catch (error: Exception) { return error }
    error("Expected failure")
}
private fun <T> CompletableFuture<T>.await439(): T = get(2, TimeUnit.SECONDS)

private data class Bound439(
    val input: ProducerRetirementInput,
    val binding: LocalProducerBinding,
    val texture: SurfaceTexture,
    val program: Int,
)

private class RetirementFixture439 {
    val legacy = mutableListOf<Pair<Surface, Long>>()
    val primaryFailures = mutableListOf<Exception>()
    var primaryFailureObserver: (Exception) -> Unit = { }
    val output = RetainedVideoOutput({ surface, generation -> legacy.add(surface to generation) }, {
        primaryFailures.add(it); primaryFailureObserver(it)
    })
    val retirement: RetainedOutputRetirement get() = output as RetainedOutputRetirement
    val owner: HandlerThread = HandlerThreadProbe.threads.single()
    val phone = output.surface.surfaceTexture!!
    val phoneProgram = GlProbe.createdPrograms.single()
    val target = Surface(47, 31, "retirement-window439")
    val bindings = mutableListOf<Bound439>()
    val callbacks = Executors.newSingleThreadExecutor { action ->
        Thread(action, "retirement-callback439").apply { isDaemon = true }
    }
    private var phoneTimestamp = 100L

    init { output.attach(target, 11); phoneFrame() }

    fun drainOwner() {
        val task = FutureTask<Unit> { }
        check(Handler(owner.looper).post(task)) { "Expected retained original GL lane to accept a barrier" }
        task.get(2, TimeUnit.SECONDS)
    }
    fun phoneFrame() {
        check(phone.emitFrame(++phoneTimestamp, retirementPhoneMatrix439))
        checkNotNull(output.currentPhoneTextureLatch())
    }
    fun bindLayout(): Boolean = output.bindFixturePhoneImage(checkNotNull(output.currentPhoneTextureLatch()),
        retirementImage439, retirementLayout439)
    fun localFrame(input: LocalCameraInput, timestamp: Long = 200) {
        check(input.surface.surfaceTexture!!.emitFrame(timestamp))
        drainOwner()
    }
    fun openBound(observer: RetirementObserver439 = RetirementObserver439(), stop: () -> Unit): Bound439 {
        val input = output.openLocalCamera(retirementConfiguration439, observer) as ProducerRetirementInput
        val binding = checkNotNull(input.bindProducer(stop))
        val bound = Bound439(input, binding, input.surface.surfaceTexture!!, GlProbe.createdPrograms.last())
        bindings.add(bound)
        return bound
    }
    fun prepared(observer: RetirementObserver439 = RetirementObserver439(), stop: () -> Unit): Bound439 {
        val bound = openBound(observer, stop)
        localFrame(bound.input)
        check(bindLayout())
        return bound
    }
    fun assertHeld(bound: Bound439) {
        equal439(0, bound.input.surface.releaseCount, "unsafe original Surface retained")
        equal439(0, bound.texture.releaseCount, "unsafe original SurfaceTexture retained")
        equal439(0, GlProbe.deletedPrograms.count { it == bound.program }, "unsafe original program retained")
        equal439(0, GlProbe.deletedTextures.count { it == bound.texture.textureName }, "unsafe original OES name retained")
    }
    fun assertReleased(bound: Bound439) {
        val result = checkNotNull(bound.input.releaseResult)
        check(result.input === bound.input)
        equal439(LocalProducerFence.OUTPUTS_SAFE_RECEIVED, result.producerFence, "exact original producer fence")
        equal439(1, bound.input.surface.releaseCount, "original Surface once")
        equal439(1, bound.texture.releaseCount, "original SurfaceTexture once")
        equal439(1, GlProbe.deletedPrograms.count { it == bound.program }, "original program once")
        equal439(1, GlProbe.deletedTextures.count { it == bound.texture.textureName }, "original OES name once")
        for (step in listOf("surfaceRelease", "surfaceTextureRelease", "programDelete", "textureDelete"))
            equal439(LocalReleaseDisposition.RETURNED, result.steps.getValue(step).disposition, "$step returned")
    }
    fun assertPhoneState() {
        val state = checkNotNull(GlProbe.observedState(owner))
        equal439(phoneProgram, state.program, "Phone program restored before external callback")
        equal439(GlRect(0, 0, 47, 31), state.viewport, "Phone viewport restored")
        check(GLES20.GL_SCISSOR_TEST !in state.enabledCaps) { "Optional scissor escaped into callback" }
        equal439(GLES20.GL_TEXTURE0, state.activeTexture, "Phone sampler unit restored")
        equal439(phone.textureName, state.textures[TextureBinding(GLES20.GL_TEXTURE0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)],
            "Phone texture restored")
        val uniforms = GlProbe.uniformNames.getValue(phoneProgram)
        equal439(retirementPhoneMatrix439.toList(), state.matrices[phoneProgram to uniforms.getValue("transform")], "Phone matrix restored")
        equal439(0, state.uniformInts[phoneProgram to uniforms.getValue("image")], "Phone sampler restored")
        val expected = listOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        for (name in listOf("position", "coordinate")) {
            val index = GlProbe.attributeNames.getValue(phoneProgram).getValue(name)
            val pointer = state.pointers.getValue(index).snapshot(index in state.enabledAttributes)
            check(pointer.enabled)
            equal439(expected, pointer.allValues, "Phone $name full quad restored")
        }
    }
    fun finish() {
        val finished = CompletableFuture<LocalOutputReleaseResult>()
        retirement.closeAsync(callbacks) { finished.complete(it) }
        for (bound in bindings) bound.binding.outputsSafe()
        val result = finished.await439()
        check(result.workerJoin.disposition == LocalReleaseDisposition.RETURNED && result.workerAlive == false)
        owner.join(2_000)
        check(!owner.isAlive && owner.finished)
        check(HandlerThreadProbe.uncaught.isEmpty())
        equal439(0, target.releaseCount, "renderer never owns presentation wrapper")
        callbacks.shutdown()
        check(callbacks.awaitTermination(2, TimeUnit.SECONDS))
    }
}

private fun retirementCase439(action: (RetirementFixture439) -> Unit) {
    GlProbe.reset()
    val fixture = RetirementFixture439()
    try { action(fixture) } finally { fixture.finish() }
}

private fun unsafeBoundCloseThenExactSafe439() = retirementCase439 { f ->
    var stops = 0
    val observer = RetirementObserver439()
    val bound = f.prepared(observer) { f.assertPhoneState(); stops++ }
    val staleFrame = bound.texture.capturePostedFrameCallback()
    val updateCount = bound.texture.updateCount
    val receipts = observer.receipts.size
    val legacy = f.legacy.size
    val completion = CompletableFuture<LocalInputReleaseResult>()
    bound.input.closeAsync(f.callbacks) { completion.complete(it) }
    f.drainOwner()
    check(bound.input.retired)
    equal439(LocalProducerFence.PENDING, bound.input.producerFence, "close request is not safe")
    equal439(null, bound.input.releaseResult, "physical result remains pending")
    check(!completion.isDone)
    equal439(1, stops, "one original stop request")
    f.assertHeld(bound)
    check(f.owner.isAlive)
    equal439(0, f.owner.quitSafelyCount, "unsafe input close retains GL lane")
    check(staleFrame()); f.drainOwner()
    equal439(updateCount, bound.texture.updateCount, "logical retirement suppresses captured old frame")
    equal439(receipts, observer.receipts.size, "no retired local receipt")
    equal439(legacy, f.legacy.size, "camera retirement is not legacy Phone presentation")
    f.assertPhoneState()
    equal439(f.phone.textureName, GlProbe.swaps.last().draws.single().texture, "Phone base redraw after logical remove")
    f.phoneFrame()
    equal439(legacy + 1, f.legacy.size, "Phone remains live while producer unsafe")
    bound.input.close(); bound.input.close()
    equal439(1, stops, "repeat close adds no stop")
    bound.binding.outputsSafe()
    val result = completion.await439()
    check(result === bound.input.releaseResult)
    f.drainOwner(); f.assertReleased(bound)
    bound.binding.outputsSafe(); bound.input.close(); f.drainOwner()
    f.assertReleased(bound)
    check(f.primaryFailures.isEmpty())
}

private fun safeBeforeRetirementAndBindingRejection439() = retirementCase439 { f ->
    var stops = 0
    val observer = RetirementObserver439()
    val bound = f.prepared(observer) { stops++ }
    check(bound.input.bindProducer { error("Second producer was adopted") } == null)
    bound.binding.outputsSafe(); f.drainOwner()
    equal439(LocalProducerFence.OUTPUTS_SAFE_RECEIVED, bound.input.producerFence, "early fence retained")
    check(!bound.input.retired)
    equal439(null, bound.input.releaseResult, "safe alone is not a close request")
    f.assertHeld(bound)
    val before = observer.receipts.size
    f.localFrame(bound.input, 201)
    equal439(before + 1, observer.receipts.size, "early safe does not remove active imagery")
    bound.input.close(); f.drainOwner()
    f.assertReleased(bound)
    check(stops in 0..1)
    val stopCount = stops
    bound.binding.outputsSafe(); bound.input.close(); f.drainOwner()
    equal439(stopCount, stops, "duplicate safe/close adds no stop")
    check(bound.input.bindProducer { error("Retired producer was adopted") } == null)
}

private fun sameTupleOldSafeCannotReleaseSuccessor439() = retirementCase439 { f ->
    var originalStops = 0; var nextStops = 0
    val original = f.prepared { originalStops++ }
    val oldFrame = original.texture.capturePostedFrameCallback()
    val nextObserver = RetirementObserver439()
    val next = f.openBound(nextObserver) { nextStops++ }
    equal439(original.input.configuration.source, next.input.configuration.source, "equal scalar source tuple")
    check(original.input !== next.input && original.binding !== next.binding)
    check(original.input.retired && !next.input.retired)
    equal439(1, originalStops, "replacement stops original")
    equal439(0, nextStops, "replacement does not stop successor")
    f.assertHeld(original); f.assertHeld(next)
    f.localFrame(next.input, 300); check(f.bindLayout())
    original.binding.outputsSafe(); f.drainOwner()
    f.assertReleased(original); f.assertHeld(next)
    check(!next.input.retired && next.input.releaseResult == null)
    check(oldFrame()); f.drainOwner()
    original.binding.outputsSafe(); original.input.close(); f.drainOwner()
    f.assertHeld(next)
    equal439(0, nextStops, "old close/safe cannot stop successor")
    f.localFrame(next.input, 301)
    check(nextObserver.receipts.last().input === next.input)
    equal439(next.texture.textureName, GlProbe.swaps.last().draws.last().texture, "successor still contributes pixels")
}

private fun reentrantAndThrowingStop439() {
    retirementCase439 { f ->
        lateinit var original: Bound439
        var successor: Bound439? = null
        var stops = 0
        original = f.prepared {
            stops++; f.assertPhoneState()
            check(original.input.bindProducer { error("adopted") } == null)
            successor = f.openBound { }
            original.binding.outputsSafe()
        }
        original.input.close(); f.drainOwner()
        equal439(1, stops, "reentrant stop once")
        f.assertReleased(original)
        val next = checkNotNull(successor)
        f.assertHeld(next)
        check(!next.input.retired)
        f.localFrame(next.input, 400); check(f.bindLayout())
        f.assertPhoneState()
    }
    retirementCase439 { f ->
        var stops = 0
        val thrown = IllegalStateException("nonblocking producer stop rejected439")
        val bound = f.prepared { stops++; f.assertPhoneState(); throw thrown }
        bound.input.close(); f.drainOwner()
        check(bound.input.retired && bound.input.releaseResult == null)
        equal439(LocalProducerFence.PENDING, bound.input.producerFence, "throwing stop never invents safe")
        equal439(1, stops, "throwing stop not retried")
        f.assertHeld(bound)
        f.phoneFrame()
        bound.input.close(); equal439(1, stops, "close does not retry failed stop")
        bound.binding.outputsSafe(); f.drainOwner()
        f.assertReleased(bound)
        val result = checkNotNull(bound.input.releaseResult)
        equal439(LocalReleaseDisposition.FAILED, result.steps.getValue("producerStop").disposition, "stop failure preserved")
        check(result.failures.any { it.error === thrown })
    }
}

private fun optionalFaultsRetainUnsafeInputsAndRestorePhone439() {
    for (operation in listOf("latch", "draw")) retirementCase439 { f ->
        var stops = 0; var observations = 0
        val observer = RetirementObserver439()
        val bound = f.prepared(observer) { f.assertPhoneState(); stops++ }
        observer.onFailure = { failure ->
            f.assertPhoneState(); observations++
            check(failure.input === bound.input)
            f.assertHeld(bound)
        }
        val legacy = f.legacy.size
        val receiptCount = observer.receipts.size
        if (operation == "latch") {
            GlProbe.failures.updateTexImage = true
            f.localFrame(bound.input, 500)
        } else {
            GlProbe.failures.drawAtOrdinal = GlProbe.draws.size + 2
            check(f.bindLayout())
            val draws = GlProbe.swaps.last().draws
            equal439(3, draws.size, "base, failed optional pass, full compensating base")
            check(!draws[1].success && draws[1].texture == bound.texture.textureName)
            equal439(f.phone.textureName, draws.last().texture, "partial optional pass erased")
        }
        check(bound.input.retired)
        equal439(1, stops, "$operation requests exact stop")
        check(observations >= 1)
        equal439(receiptCount, observer.receipts.size, "$operation no local receipt")
        equal439(legacy, f.legacy.size, "$operation no fake Phone callback")
        equal439(operation, bound.input.lastFailure?.operation, "original optional failure retained")
        f.assertHeld(bound)
        check(f.primaryFailures.isEmpty())
        f.phoneFrame()
        bound.binding.outputsSafe(); f.drainOwner(); f.assertReleased(bound)
    }
}

private fun independentCleanupFailures439() = retirementCase439 { f ->
    val bound = f.prepared { }
    bound.input.close()
    val surfaceError = AssertionError("Surface release fault439")
    val textureError = IllegalStateException("SurfaceTexture release fault439")
    bound.input.surface.failNextRelease = surfaceError
    bound.texture.failNextRelease = textureError
    GlProbe.failures.deleteProgram = true
    bound.binding.outputsSafe(); f.drainOwner()
    val result = checkNotNull(bound.input.releaseResult)
    check(result.input === bound.input)
    equal439(LocalGlContextDisposition.ORIGINAL_CURRENT, result.glContext, "deletion context actually current")
    for (step in listOf("surfaceRelease", "surfaceTextureRelease", "programDelete"))
        equal439(LocalReleaseDisposition.FAILED, result.steps.getValue(step).disposition, "$step independent failure")
    equal439(LocalReleaseDisposition.RETURNED, result.steps.getValue("textureDelete").disposition, "texture delete attempted despite earlier failures")
    equal439(1, bound.input.surface.releaseCount, "failed wrapper attempted once")
    equal439(0, bound.input.surface.releaseReturnedCount, "failed wrapper is not a returned release")
    equal439(1, bound.texture.releaseCount, "failed consumer attempted once")
    equal439(0, bound.texture.releaseReturnedCount, "failed consumer is not a returned release")
    equal439(0, GlProbe.deletedPrograms.count { it == bound.program }, "failed program delete not counted as returned")
    equal439(1, GlProbe.deletedTextures.count { it == bound.texture.textureName }, "later texture finalizer returned")
    check(result.failures.any { it.error === surfaceError })
    check(result.failures.any { it.error === textureError })
    val attempts = GlProbe.events.count { it.name == "glDeleteProgram" && it.details["program"] == bound.program }
    equal439(1, attempts, "program delete attempted once")
    bound.binding.outputsSafe(); bound.input.close(); f.drainOwner()
    equal439(1, bound.input.surface.releaseCount, "failed release never retried")
    equal439(attempts, GlProbe.events.count { it.name == "glDeleteProgram" && it.details["program"] == bound.program }, "failed delete never retried")
}

private fun unavailableOriginalContextIsQualified439() {
    for ((eglError, disposition) in listOf(EGL14.EGL_BAD_ALLOC to LocalGlContextDisposition.UNAVAILABLE,
        EGL14.EGL_CONTEXT_LOST to LocalGlContextDisposition.CONFIRMED_CONTEXT_LOST)) retirementCase439 { f ->
        val bound = f.prepared { }
        bound.input.close()
        GlProbe.failures.makeCurrentError = eglError
        GlProbe.failures.makeCurrent = true
        bound.binding.outputsSafe(); f.drainOwner()
        val result = checkNotNull(bound.input.releaseResult)
        equal439(disposition, result.glContext, "actual EGL error qualifies context disposition")
        equal439(LocalReleaseDisposition.FAILED, result.steps.getValue("makeCurrent").disposition, "actual makeCurrent failure")
        equal439(LocalReleaseDisposition.NOT_ATTEMPTED, result.steps.getValue("programDelete").disposition, "no delete without original current context")
        equal439(LocalReleaseDisposition.NOT_ATTEMPTED, result.steps.getValue("textureDelete").disposition, "no texture delete without original current context")
        equal439(1, bound.input.surface.releaseCount, "safe wrapper step attempted independently")
        equal439(1, bound.texture.releaseCount, "safe consumer step attempted independently")
        equal439(0, GlProbe.deletedPrograms.count { it == bound.program }, "no fabricated explicit program deletion")
        equal439(0, GlProbe.deletedTextures.count { it == bound.texture.textureName }, "no fabricated explicit texture deletion")
        check(result.failures.isNotEmpty())
    }
}

private fun confirmedGlLossRetiresBoundOriginal439() = retirementCase439 { f ->
    var stops = 0
    val observer = RetirementObserver439()
    val bound = f.prepared(observer) { stops++ }
    val receipts = observer.receipts.size
    GlProbe.failures.drawAtOrdinal = GlProbe.draws.size + 2
    GlProbe.failures.drawError = GLES20.GL_CONTEXT_LOST
    failure439 { f.bindLayout() }
    check(f.primaryFailures.isNotEmpty())
    check(bound.input.retired)
    equal439(1, stops, "confirmed GL loss stops original bound producer")
    equal439(receipts, observer.receipts.size, "confirmed GL loss has no successful local receipt")
    f.assertHeld(bound)
    bound.binding.outputsSafe(); f.drainOwner()
    val result = checkNotNull(bound.input.releaseResult)
    equal439(LocalGlContextDisposition.CONFIRMED_CONTEXT_LOST, result.glContext, "actual GL_CONTEXT_LOST retained")
    equal439(LocalReleaseDisposition.NOT_ATTEMPTED, result.steps.getValue("programDelete").disposition, "no explicit deletion through lost context")
    equal439(LocalReleaseDisposition.NOT_ATTEMPTED, result.steps.getValue("textureDelete").disposition, "lost-context texture deletion qualified")
    equal439(1, bound.input.surface.releaseCount, "wrapper held until original safe then released")
    equal439(1, bound.texture.releaseCount, "consumer held until original safe then released")
}

private fun throwingLegacyFailureObserverKeepsBoundFinalizerLane439() = retirementCase439 { f ->
    var stops = 0
    val observerFault = IllegalStateException("explicit legacy failure observer fault439")
    f.primaryFailureObserver = { throw observerFault }
    val bound = f.prepared { stops++ }
    GlProbe.failures.drawAtOrdinal = GlProbe.draws.size + 2
    GlProbe.failures.drawError = GLES20.GL_CONTEXT_LOST
    // This frame executes on the original private Handler, outside any caller FutureTask.
    f.localFrame(bound.input)
    check(f.primaryFailures.isNotEmpty() && f.primaryFailures.none { it === observerFault })
    check(bound.input.retired && f.owner.isAlive)
    equal439(1, stops, "throwing failure observer does not suppress original stop")
    f.assertHeld(bound)
    val completed = CompletableFuture<LocalOutputReleaseResult>()
    f.retirement.closeAsync(Executor { it.run() }) { completed.complete(it) }
    bound.binding.outputsSafe()
    val result = completed.await439()
    check(result.workerJoin.disposition == LocalReleaseDisposition.RETURNED && result.workerAlive == false)
    check(result.failures.any { it.error === observerFault })
    val inputResult = checkNotNull(bound.input.releaseResult)
    equal439(LocalGlContextDisposition.CONFIRMED_CONTEXT_LOST, inputResult.glContext,
        "actual context loss remains separate from callback fault")
    equal439(LocalReleaseDisposition.NOT_ATTEMPTED, inputResult.steps.getValue("programDelete").disposition,
        "no explicit deletion through confirmed lost context")
    equal439(1, bound.input.surface.releaseCount, "original wrapper held until actual safe")
    check(HandlerThreadProbe.uncaught.isEmpty())
}

private fun parentCloseRetainsBothOriginalsAndJoinsOffCaller439() {
    for (oldFirst in listOf(true, false)) retirementCase439 { f ->
        var oldStops = 0; var currentStops = 0
        val old = f.prepared { oldStops++ }
        val current = f.openBound { currentStops++ }
        val completed = CompletableFuture<LocalOutputReleaseResult>()
        val callingThread = Thread.currentThread()
        var callbackThread: Thread? = null
        var callbackSawAlive: Boolean? = null
        val inline = Executor { it.run() }
        f.retirement.closeAsync(inline) {
            callbackThread = Thread.currentThread(); callbackSawAlive = f.owner.isAlive
            completed.complete(it)
        }
        f.drainOwner()
        equal439(1, oldStops, "already retiring original stop once")
        equal439(1, currentStops, "current original stop once")
        check(old.input.retired && current.input.retired)
        f.assertHeld(old); f.assertHeld(current)
        check(!completed.isDone && f.retirement.releaseResult == null && f.owner.isAlive)
        equal439(0, f.owner.quitSafelyCount, "unsafe originals retain finalizer lane")
        equal439(0, f.output.surface.releaseCount, "parent Phone endpoint retained with required context lane")
        equal439(0, GlProbe.destroyedContexts.size, "no premature EGL context teardown")
        equal439(null, f.output.currentPhoneTextureLatch(), "logical close blocks new public latch work")
        failure439 { f.output.openLocalCamera(retirementConfiguration439, null) }
        val first = if (oldFirst) old else current
        val last = if (oldFirst) current else old
        first.binding.outputsSafe(); f.drainOwner()
        f.assertReleased(first); f.assertHeld(last)
        check(!completed.isDone && f.owner.isAlive)
        equal439(0, f.owner.quitSafelyCount, "one remaining unsafe original retains lane")
        last.binding.outputsSafe()
        val result = completed.await439()
        check(result.inputs.any { it.input === old.input } && result.inputs.any { it.input === current.input })
        equal439(LocalReleaseDisposition.RETURNED, result.workerJoin.disposition, "actual output worker join returned")
        equal439(false, result.workerAlive, "actual output worker not alive")
        equal439(false, callbackSawAlive, "completion follows actual join")
        check(callbackThread !== callingThread && callbackThread !== f.owner) { "Inline callback executor must not move join onto UI/output" }
        equal439(LocalReleaseDisposition.RETURNED, result.steps.getValue("quitSafely").disposition, "quit submission separate from join")
        f.assertReleased(old); f.assertReleased(current)
        old.binding.outputsSafe(); current.binding.outputsSafe(); f.output.close()
        equal439(1, old.input.surface.releaseCount, "late old safe after output close harmless")
        equal439(1, current.input.surface.releaseCount, "late current safe after output close harmless")
    }
}

private fun completionDispatchRejectionIsNotCleanupSuccess439() = retirementCase439 { f ->
    val bound = f.prepared { }
    val rejected = RejectedExecutionException("callback executor rejected439")
    var invoked = false
    val reject = Executor { throw rejected }
    f.retirement.closeAsync(reject) { invoked = true }
    val accepted = CompletableFuture<LocalOutputReleaseResult>()
    f.retirement.closeAsync(f.callbacks) { accepted.complete(it) }
    f.drainOwner()
    check(!accepted.isDone)
    bound.binding.outputsSafe()
    accepted.await439()
    check(!invoked && !f.owner.isAlive)
    val result = checkNotNull(f.retirement.releaseResult)
    equal439(LocalReleaseDisposition.RETURNED, result.workerJoin.disposition, "rejected callback does not fake or suppress actual join")
    check(result.failures.any { it.operation == "completion-dispatch" && it.error === rejected })
    f.assertReleased(bound)
}

private fun rejectedOriginalFinalizerPostRetainsPendingDuty439() {
    GlProbe.reset()
    val f = RetirementFixture439()
    try {
        var stops = 0
        val bound = f.prepared { stops++ }
        bound.input.close(); f.drainOwner()
        check(bound.input.retired && bound.input.producerFence == LocalProducerFence.PENDING)
        f.assertHeld(bound)
        equal439(1, stops, "original nonblocking stop submitted before post rejection")

        f.owner.looper.rejectNextPost = true
        val signalingThread = Thread.currentThread()
        bound.binding.outputsSafe()
        equal439(1, f.owner.looper.rejectedPostCount, "exact original finalizer post rejected once")
        val rejection = HandlerThreadProbe.rejectedPosts.single()
        check(rejection.owner === f.owner && rejection.caller === signalingThread)
        f.drainOwner() // Its accepted FIFO barrier cannot manufacture the missing finalizer.
        equal439(LocalProducerFence.OUTPUTS_SAFE_RECEIVED, bound.input.producerFence, "actual producer fence is retained")
        equal439(null, bound.input.releaseResult, "safe plus rejected GL post is not physical completion")
        check(bound.input.retirementFailures.any { it.operation == "finalizer-post" })
        equal439("finalizer-post", bound.input.lastFailure?.operation, "pending dispatch fault remains observable")
        f.assertHeld(bound)

        val outputCompletion = CompletableFuture<LocalOutputReleaseResult>()
        f.retirement.closeAsync(f.callbacks) { outputCompletion.complete(it) }
        f.drainOwner()
        check(!outputCompletion.isDone && f.retirement.releaseResult == null)
        check(f.owner.isAlive)
        equal439(0, f.owner.quitSafelyCount, "renderer retains original lane and unresolved finalizer duty")
        equal439(0, f.output.surface.releaseCount, "no parent Phone teardown ahead of pending original")
        equal439(0, GlProbe.destroyedContexts.size, "no claimed context cleanup")
        bound.binding.outputsSafe(); bound.input.close(); f.output.close(); f.drainOwner()
        equal439(1, f.owner.looper.rejectedPostCount, "duplicate signal/close never retries the rejected post")
        equal439(1, HandlerThreadProbe.rejectedPosts.size, "only exact injected post rejected")
        equal439(1, stops, "rejection never retries producer stop")
        equal439(null, bound.input.releaseResult, "original release remains pending after duplicates")
        check(!outputCompletion.isDone && f.retirement.releaseResult == null)
        f.assertHeld(bound)
    } finally {
        // FAILURE-CLEANUP ONLY: stop the explicit host queue so this control cannot leak a thread.
        // This deliberately does NOT call output.close/finish, execute retained finalizers, release
        // Surface/ST/GL resources, or produce an input/output/native-quiescence completion claim.
        GlProbe.record("fixture-failure-cleanup-only439", "physicalCleanupClaim" to false,
            "nativeQuiescenceClaim" to false)
        f.owner.quitSafely()
        f.owner.join(2_000)
        check(!f.owner.isAlive && f.owner.finished)
        check(HandlerThreadProbe.uncaught.isEmpty())
        f.callbacks.shutdown()
        check(f.callbacks.awaitTermination(2, TimeUnit.SECONDS))
        equal439(null, f.retirement.releaseResult, "fixture queue cleanup does not invent output completion")
    }
}

private fun recoverableWindowDetachDoesNotStopProducer439() = retirementCase439 { f ->
    var stops = 0
    val observer = RetirementObserver439()
    val bound = f.prepared(observer) { stops++ }
    f.output.attach(null, 12)
    f.localFrame(bound.input, 600)
    equal439(0, stops, "presentation detach never stops producer")
    check(!bound.input.retired)
    f.assertHeld(bound)
    val replacement = Surface(47, 31, "retirement-new-window439")
    f.output.attach(replacement, 13)
    check(observer.receipts.last().target === replacement)
    equal439(13L, observer.receipts.last().windowGeneration, "retained camera attaches to exact new window")
    GlProbe.failures.windowError = EGL14.EGL_BAD_SURFACE
    GlProbe.failures.windowSwap = true
    f.localFrame(bound.input, 601)
    check(f.primaryFailures.last() is PresentationSurfaceLost)
    equal439(0, stops, "recoverable native window loss is presentation-only")
    check(!bound.input.retired)
    f.assertHeld(bound)
    f.output.attach(Surface(47, 31, "retirement-after-loss439"), 14)
    equal439(14L, observer.receipts.last().windowGeneration, "original camera still contributes after window recovery")
    equal439(0, stops, "reattach does not replace producer")
}

private fun runRendererRetirement439Scenarios() {
    val cases = listOf(
        "unsafe bound close holds acquired resources until exact safe" to ::unsafeBoundCloseThenExactSafe439,
        "early and duplicate safe plus original binding rejection" to ::safeBeforeRetirementAndBindingRejection439,
        "same-tuple replacement and stale original completion" to ::sameTupleOldSafeCannotReleaseSuccessor439,
        "reentrant and throwing producer stop remain original and truthful" to ::reentrantAndThrowingStop439,
        "optional latch/draw faults restore Phone before callbacks while unsafe" to ::optionalFaultsRetainUnsafeInputsAndRestorePhone439,
        "independent once-attempted cleanup failures" to ::independentCleanupFailures439,
        "unavailable versus confirmed-lost original context qualifies deletion" to ::unavailableOriginalContextIsQualified439,
        "observed GL context loss retires only bound originals" to ::confirmedGlLossRetiresBoundOriginal439,
        "throwing legacy failure observer preserves bound finalizer lane" to ::throwingLegacyFailureObserverKeepsBoundFinalizerLane439,
        "parent close retains two originals in either safe order and joins off caller" to ::parentCloseRetainsBothOriginalsAndJoinsOffCaller439,
        "rejected completion executor remains a recorded failure" to ::completionDispatchRejectionIsNotCleanupSuccess439,
        "rejected original GL finalizer post retains unresolved duty without retry" to ::rejectedOriginalFinalizerPostRetainsPendingDuty439,
        "recoverable window detach/loss preserves producer" to ::recoverableWindowDetachDoesNotStopProducer439,
    )
    for ((name, run) in cases) { run(); println("PASS retirement439: $name") }
    println("PASS retirement439: ${cases.size} separate actual-renderer groups with manual original fences and explicit Android/GL doubles; no Camera2, EGL/native, optical, device, or global callback-quiescence proof")
}

object RendererRetirement439Runner {
    @JvmStatic fun main(args: Array<String>) {
        // This invokes the existing thirteen function bodies without editing their criteria/timers.
        com.code2hack.eyeprojector.directrg.main()
        runRendererRetirement439Scenarios()
    }
}
