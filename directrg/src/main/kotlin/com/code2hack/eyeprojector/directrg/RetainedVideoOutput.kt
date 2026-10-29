package com.code2hack.eyeprojector.directrg

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

internal class PresentationSurfaceLost(val surface: Surface?, val generation: Long) : Exception("Presentation Surface unavailable")

/** Real decoder consumer. The last decoded texture survives the absence of a window. */
internal interface ReceiverVideoOutput : Closeable {
    val surface: Surface
    fun attach(next: Surface?, generation: Long)
    fun invalidate()
    fun redraw()
}

internal class RetainedVideoOutput(
    private val presented: (Surface, Long) -> Unit,
    private val failed: (Exception) -> Unit,
) : ReceiverVideoOutput, LocalPreviewOutput, RetainedOutputRetirement {
    private val thread = HandlerThread("eyeprojector-video-output").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var buffer = EGL14.EGL_NO_SURFACE
    private var window = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private lateinit var texture: SurfaceTexture
    private var textureName = 0
    private var program = 0
    private var target: Surface? = null
    private var targetGeneration = 0L
    private var hasFrame = false
    private val outputIdentity = Any()
    private var phoneLatchSequence = 0L
    private var phoneLatch: PhoneTextureLatchKey? = null
    private var fixtureBinding: FixturePhoneBinding? = null
    private var localInput: CameraLease? = null
    // These original lease records outlive the current slot and equal source tuples.
    private val inputs = ArrayList<CameraLease>()
    @Volatile private var hasBoundProducer = false
    private var originalContextCurrent = false
    private var contextLostObserved = false
    private var parentCloseStarted = false
    private var parentRetiring = false
    private var parentFinalized = false
    private var joinObserverStarted = false
    private var parentSubmissionClaimed = false
    private var transactionDepth = 0
    private var dispatchingDeferred = false
    private val deferred = ArrayList<() -> Unit>()
    private val outputLock = Any()
    private val outputSteps = LinkedHashMap<String, LocalReleaseStep>()
    private val outputFailures = ArrayList<LocalReleaseFailure>()
    private val outputObservers = ArrayList<Pair<Executor, (LocalOutputReleaseResult) -> Unit>>()
    @Volatile override var releaseResult: LocalOutputReleaseResult? = null
        private set
    override val retirementFailures get() = synchronized(outputLock) { outputFailures.toList() }
    @Volatile private var closed = false
    override lateinit var surface: Surface
        private set
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    private class SharedEglFailure(message: String, val actualCode: Int? = null) : Exception(message) {
        val contextLost get() = actualCode == 0x0507 || actualCode == 0x300e
    }

    private inner class CameraLease(
        override val configuration: LocalCameraConfiguration,
        val observer: LocalCameraObserver?,
    ) : ProducerRetirementInput {
        var textureName = 0
        var program = 0
        var texture: SurfaceTexture? = null
        var endpoint: Surface? = null
        val matrix = FloatArray(16)
        var sequence = 0L
        var latch: LocalTextureLatch? = null
        @Volatile override var retired = false
            private set
        @Volatile override var lastFailure: LocalCameraFailure? = null
            private set
        private val lifecycle = Any()
        private var producerBound = false
        private var producerSafe = false
        private var producerStop: (() -> Unit)? = null
        private var stopClaimed = false
        private var retirementStarted = false
        private var finalizerPosted = false
        private var finalizationStarted = false
        private var finalizationFinished = false
        private val steps = LinkedHashMap<String, LocalReleaseStep>()
        private val failures = ArrayList<LocalReleaseFailure>()
        private val observers = ArrayList<Pair<Executor, (LocalInputReleaseResult) -> Unit>>()
        private var cleanupFailure: Throwable? = null
        private var glDisposition = LocalGlContextDisposition.NOT_NEEDED
        @Volatile override var releaseResult: LocalInputReleaseResult? = null
            private set
        override val retirementFailures get() = synchronized(lifecycle) { failures.toList() }
        override val surface get() = checkNotNull(endpoint)
        override val producerFence get() = synchronized(lifecycle) { fenceLocked() }

        private fun fenceLocked() = when {
            !producerBound -> LocalProducerFence.NOT_BOUND_LEGACY
            producerSafe -> LocalProducerFence.OUTPUTS_SAFE_RECEIVED
            else -> LocalProducerFence.PENDING
        }

        fun bound() = synchronized(lifecycle) { producerBound }

        override fun bindProducer(requestStop: () -> Unit): LocalProducerBinding? = synchronized(lifecycle) {
            if (retired || closed || producerBound) return@synchronized null
            producerBound = true
            hasBoundProducer = true
            producerStop = requestStop
            object : LocalProducerBinding {
                override fun outputsSafe() {
                    val first = synchronized(lifecycle) {
                        if (producerSafe) false else { producerSafe = true; true }
                    }
                    if (first) postFinalizerIfReady()
                }
            }
        }

        fun record(operation: String, error: Throwable, cleanup: Boolean = false) {
            synchronized(lifecycle) {
                if (failures.none { it.operation == operation && it.error === error })
                    failures.add(LocalReleaseFailure(operation, error))
                if (cleanup) {
                    val primary = cleanupFailure
                    if (primary == null) cleanupFailure = error
                    else if (primary !== error && primary.suppressed.none { it === error }) primary.addSuppressed(error)
                }
                releaseResult = releaseResult?.copy(steps = steps.toMap(), failures = failures.toList())
            }
        }

        fun step(name: String, acquired: Boolean = true, action: () -> Unit) {
            val attempt = synchronized(lifecycle) {
                if (steps.containsKey(name)) false else {
                    // Claim before native entry; a fault never permits a second attempt.
                    steps[name] = LocalReleaseStep(LocalReleaseDisposition.NOT_ATTEMPTED)
                    acquired
                }
            }
            if (!attempt) return
            val outcome = try { action(); LocalReleaseStep(LocalReleaseDisposition.RETURNED) }
                catch (error: Throwable) {
                    record(name, error, cleanup = true)
                    LocalReleaseStep(LocalReleaseDisposition.FAILED, error)
                }
            synchronized(lifecycle) { steps[name] = outcome }
        }

        fun skip(name: String, error: Throwable? = null) {
            synchronized(lifecycle) {
                if (!steps.containsKey(name)) steps[name] = LocalReleaseStep(LocalReleaseDisposition.NOT_ATTEMPTED, error)
            }
        }

        fun cleanupError(): Exception? = synchronized(lifecycle) { cleanupFailure?.let(::asException) }

        fun claimFinalizer(): Boolean = synchronized(lifecycle) {
            if (!retirementStarted || finalizationStarted || (producerBound && !producerSafe)) false
            else { finalizationStarted = true; true }
        }

        private fun postFinalizerIfReady() {
            val post = synchronized(lifecycle) {
                if (!retirementStarted || !producerSafe || finalizerPosted || finalizationStarted) false
                else { finalizerPosted = true; true }
            }
            if (post) try {
                if (!handler.post { transaction { finalizeInput(this) } })
                    submissionFailed("finalizer-post", IllegalStateException("Original output finalizer lane stopped"))
            } catch (error: Throwable) { submissionFailed("finalizer-post", error) }
        }

        private fun submissionFailed(operation: String, error: Throwable) {
            record(operation, error, cleanup = true)
            val exception = asException(error)
            lastFailure = LocalCameraFailure(operation, exception, this)
            // A producer callback may race an active GL transaction. A rejected post cannot
            // establish restored Phone state or justify a second post just to notify observers.
            if (Thread.currentThread() === thread) defer { report(operation, exception) }
        }

        fun report(operation: String, error: Exception) {
            record(operation, error)
            val failure = LocalCameraFailure(operation, error, this)
            lastFailure = failure
            try { observer?.failed(failure) } catch (observerError: Throwable) {
                if (observerError !== error) error.addSuppressed(observerError)
                record("observer:$operation", observerError)
            }
        }

        /** Logical retirement is independent of the original producer's physical-use fence. */
        fun retire(deleteGl: Boolean = true): Exception? {
            var stop: (() -> Unit)? = null
            val first = synchronized(lifecycle) {
                retired = true
                if (retirementStarted) false else {
                    retirementStarted = true
                    if (producerBound && !stopClaimed) { stopClaimed = true; stop = producerStop }
                    true
                }
            }
            if (!first) return null
            if (localInput === this) { localInput = null; fixtureBinding = null }
            step("listenerDetach", texture != null) { texture!!.setOnFrameAvailableListener(null) }
            stop?.let { originalStop -> defer {
                step("producerStop") { originalStop() }
                synchronized(lifecycle) { steps["producerStop"] }?.error?.let { report("producer-stop", asException(it)) }
            } }
            if (bound()) postFinalizerIfReady()
            else {
                skip("producerStop")
                finalizeInput(this, legacyCurrent = deleteGl && originalContextCurrent)
            }
            return cleanupError()
        }

        fun finishPhysical(disposition: LocalGlContextDisposition) {
            val result: LocalInputReleaseResult
            val callbacks: List<Pair<Executor, (LocalInputReleaseResult) -> Unit>>
            synchronized(lifecycle) {
                finalizationFinished = true
                glDisposition = disposition
                result = LocalInputReleaseResult(this, fenceLocked(), glDisposition, steps.toMap(), failures.toList())
                releaseResult = result
                callbacks = observers.toList(); observers.clear()
            }
            for ((executor, callback) in callbacks) defer { dispatch(executor, callback, releaseResult ?: result) }
        }

        fun finished() = synchronized(lifecycle) { finalizationFinished }

        private fun dispatch(executor: Executor, callback: (LocalInputReleaseResult) -> Unit,
            result: LocalInputReleaseResult) {
            try { executor.execute {
                try { callback(result) } catch (error: Throwable) { record("completion-observer", error) }
            } } catch (error: Throwable) { record("completion-dispatch", error) }
        }

        override fun closeAsync(completionExecutor: Executor, onRelease: (LocalInputReleaseResult) -> Unit) {
            val result = synchronized(lifecycle) {
                val completed = releaseResult
                if (completed == null) { observers.add(completionExecutor to onRelease); retired = true }
                completed
            }
            if (result != null) { dispatch(completionExecutor, onRelease, result); return }
            try {
                if (!handler.post { transaction {
                    try { closeInput(this) } catch (error: Exception) {
                        if (error is SharedEglFailure) rendererFailed(error) else report("close", error)
                    }
                } })
                    submissionFailed("retirement-post", IllegalStateException("Original output retirement lane stopped"))
            } catch (error: Throwable) { submissionFailed("retirement-post", error) }
        }

        override fun close() {
            if (retired || closed) return
            var entered = false
            try { call {
                entered = true
                if (retired || closed) return@call
                try { current(buffer) } catch (error: Exception) {
                    record("close", error)
                    retire(deleteGl = false)?.let { if (it !== error) error.addSuppressed(it) }
                    throw error
                }
                val active = localInput === this
                val error = retire()
                try { if (active) draw(LocalDrawCause.CAMERA_REMOVE) }
                finally { if (error != null) report("close", error) }
            } } catch (error: Exception) {
                // A concurrent parent close already retired this lease before stopping its Handler.
                if (!entered && (retired || closed)) return
                if (error is SharedEglFailure) rendererFailed(error) else report("close", error)
            }
        }
    }

    init {
        try { call {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE),
                0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL config unavailable" }
            config = configs[0]!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL context unavailable" }
            buffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(buffer != EGL14.EGL_NO_SURFACE) { "EGL pbuffer unavailable" }
            current(buffer)
            val names = IntArray(1); GLES20.glGenTextures(1, names, 0); textureName = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = program()
            texture = SurfaceTexture(textureName)
            surface = Surface(texture)
            texture.setOnFrameAvailableListener({ if (!closed) transaction { runFrame() } }, handler)
        } } catch (error: Exception) { runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed); throw error }
    }

    private fun <T> call(action: () -> T): T {
        if (Thread.currentThread() === thread) return transaction(action)
        val task = FutureTask { transaction(action) }
        check(handler.post(task)) { "Video output thread stopped" }
        return try { task.get() } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause as? Exception ?: error)
        }
    }

    private fun asException(error: Throwable): Exception = error as? Exception
        ?: RuntimeException("Local resource operation failed", error)

    private fun <T> transaction(action: () -> T): T {
        transactionDepth++
        try { return action() }
        finally {
            transactionDepth--
            if (transactionDepth == 0 && !dispatchingDeferred) {
                dispatchingDeferred = true
                try {
                    while (deferred.isNotEmpty()) {
                        val next = deferred.removeAt(0)
                        try { next() } catch (error: Throwable) { recordOutputFailure("deferred-action", error) }
                    }
                } finally { dispatchingDeferred = false }
            }
        }
    }

    private fun defer(action: () -> Unit) { deferred.add(action) }

    private fun current(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            originalContextCurrent = false
            val error = SharedEglFailure("EGL makeCurrent failed", EGL14.eglGetError())
            if (error.contextLost) contextLostObserved = true
            throw error
        }
        originalContextCurrent = true
    }

    private fun restorePhoneBindings() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        bindPixels(program, textureName, matrix, vertices)
    }

    private fun closeInput(original: CameraLease) {
        if (original.finished()) return
        var contextError: Exception? = null
        try { current(buffer) } catch (error: Exception) { contextError = error; original.record("close", error) }
        val active = localInput === original
        val cleanup = original.retire(deleteGl = contextError == null)
        try { if (active && !closed && contextError == null) draw(LocalDrawCause.CAMERA_REMOVE) }
        finally {
            val primary = contextError ?: cleanup
            if (primary != null) {
                if (cleanup != null && cleanup !== primary) primary.addSuppressed(cleanup)
                original.report("close", primary)
            }
        }
        if (contextError is SharedEglFailure) rendererFailed(contextError)
    }

    /** The caller is the original GL lane; no output identity or context is substituted. */
    private fun finalizeInput(original: CameraLease, legacyCurrent: Boolean? = null) {
        if (!original.claimFinalizer()) return
        val needsGl = original.program != 0 || original.textureName != 0
        var ready = false
        var unavailable: Throwable? = null
        var disposition = LocalGlContextDisposition.NOT_NEEDED
        if (needsGl) {
            when {
                legacyCurrent == true -> {
                    ready = true
                    disposition = LocalGlContextDisposition.ORIGINAL_CURRENT
                }
                contextLostObserved -> disposition = LocalGlContextDisposition.CONFIRMED_CONTEXT_LOST
                legacyCurrent != null -> {
                    ready = legacyCurrent
                    disposition = if (ready) LocalGlContextDisposition.ORIGINAL_CURRENT else LocalGlContextDisposition.UNAVAILABLE
                }
                else -> {
                    original.step("makeCurrent") { current(buffer); ready = true }
                    disposition = if (ready) LocalGlContextDisposition.ORIGINAL_CURRENT
                        else if (contextLostObserved) LocalGlContextDisposition.CONFIRMED_CONTEXT_LOST
                        else LocalGlContextDisposition.UNAVAILABLE
                }
            }
            if (!ready) {
                unavailable = SharedEglFailure("Local GL deletion unavailable: original context is not current",
                    if (contextLostObserved) 0x300e else null)
                original.record("gl-context", unavailable, cleanup = true)
            }
        }
        if (legacyCurrent != null || !needsGl || contextLostObserved) original.skip("makeCurrent")
        original.step("surfaceRelease", original.endpoint != null) { original.endpoint!!.release() }
        original.step("surfaceTextureRelease", original.texture != null) { original.texture!!.release() }
        if (ready) {
            original.step("programDelete", original.program != 0) { GLES20.glDeleteProgram(original.program) }
            original.step("textureDelete", original.textureName != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(original.textureName), 0)
            }
            original.step("phoneStateRestore") { restorePhoneBindings() }
        } else {
            original.skip("programDelete", unavailable)
            original.skip("textureDelete", unavailable)
            original.skip("phoneStateRestore", unavailable)
        }
        original.finishPhysical(disposition)
        maybeFinalizeParent()
    }

    /** Legacy unbound fixtures retain their old fatal-error lifetime semantics. */
    private fun rendererFailed(error: Exception) {
        if (error is SharedEglFailure) {
            if (Thread.currentThread() === thread) retireSharedInputs(error)
            else try {
                if (!handler.post { transaction { retireSharedInputs(error) } })
                    recordOutputFailure("shared-retirement-post", error)
            } catch (postError: Throwable) { recordOutputFailure("shared-retirement-post", postError) }
        }
        if (!hasBoundProducer) failed(error)
        else try { failed(error) } catch (observerError: Throwable) {
            if (observerError !== error) error.addSuppressed(observerError)
            recordOutputFailure("failure-observer", observerError)
        }
    }

    private fun retireSharedInputs(error: SharedEglFailure) {
        if (error.contextLost) { contextLostObserved = true; originalContextCurrent = false }
        for (original in inputs.toList()) if (original.bound() && !original.finished()) {
            original.record("shared-renderer", error)
            original.retire(deleteGl = false)
        }
    }

    override fun currentPhoneTextureLatch(): PhoneTextureLatchKey? = if (closed) null else call { if (closed) null else phoneLatch }

    override fun bindFixturePhoneImage(latch: PhoneTextureLatchKey, image: PhoneImageKey,
        layout: ViewfinderLayout?): Boolean = if (closed) false else call {
        if (closed || !hasFrame || phoneLatch !== latch) return@call false
        if (layout != null && (layout.image != image || layout.cameraUse.projectionEpoch != image.projectionEpoch)) {
            localInput?.report("fixture-metadata", IllegalArgumentException("Layout does not name fixture image/use"))
            return@call false
        }
        fixtureBinding = FixturePhoneBinding(latch, image, layout)
        try { draw(LocalDrawCause.FIXTURE_LAYOUT) } catch (error: Exception) { rendererFailed(error); throw error }
        true
    }

    override fun openLocalCamera(configuration: LocalCameraConfiguration,
        observer: LocalCameraObserver?): LocalCameraInput = call {
        check(!closed) { "Video output closed" }
        val lease = CameraLease(configuration, observer)
        inputs.add(lease)
        var originalCleanup: Pair<CameraLease, Exception>? = null
        try {
            current(buffer)
            localInput?.let { original -> original.retire()?.let { originalCleanup = original to it } }
            draw(LocalDrawCause.CAMERA_REMOVE)
            current(buffer)
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0); lease.textureName = names[0]
            check(lease.textureName != 0) { "Local texture unavailable" }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, lease.textureName)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            lease.program = program()
            lease.texture = SurfaceTexture(lease.textureName)
            lease.texture!!.setDefaultBufferSize(configuration.requestedBufferWidth, configuration.requestedBufferHeight)
            lease.endpoint = Surface(lease.texture!!)
            checkGl("Local input allocation failed")
            lease.texture!!.setOnFrameAvailableListener({
                if (!closed && !lease.retired && localInput === lease) transaction { runLocalFrame(lease) }
            }, handler)
            localInput = lease
            // Finish GL changes before observers can reenter and replace/close this lease.
            bindPixels(program, textureName, matrix, vertices)
            originalCleanup?.let { (original, error) -> original.report("replacement-close", error) }
            lease
        } catch (error: Exception) {
            lease.record("open", error)
            lease.retire(deleteGl = error !is SharedEglFailure)?.let { if (it !== error) error.addSuppressed(it) }
            originalCleanup?.let { (original, cleanup) -> original.report("replacement-close", cleanup) }
            if (error is SharedEglFailure) rendererFailed(error) else lease.report("open", error)
            throw error
        }
    }

    private fun checkGl(message: String) {
        val error = GLES20.glGetError()
        // GL_CONTEXT_LOST, including when a shared context is lost during the optional pass.
        if (error == 0x0507) {
            contextLostObserved = true; originalContextCurrent = false
            throw SharedEglFailure("$message: shared GL context lost", error)
        }
        check(error == GLES20.GL_NO_ERROR) { "$message: GL error $error" }
    }

    /** Waits for pending GL work: SurfaceHolder may release the native window after this returns. */
    override fun attach(next: Surface?, generation: Long) = call {
        if (closed) return@call
        try {
            targetGeneration = generation
            if (target === next) { draw(LocalDrawCause.WINDOW_ATTACH); return@call }
            current(buffer)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE; target = null
            if (next != null) {
                window = EGL14.eglCreateWindowSurface(display, config, next, intArrayOf(EGL14.EGL_NONE), 0)
                if (window == EGL14.EGL_NO_SURFACE) {
                    if (windowLost(next)) return@call
                    error("EGL window unavailable")
                }
                target = next
                draw(LocalDrawCause.WINDOW_ATTACH)
            }
        } catch (error: SharedEglFailure) {
            if (hasBoundProducer) rendererFailed(error)
            throw error
        }
    }

    /** Geometry replacement must not announce the predecessor's texture as a fresh presentation. */
    override fun invalidate() = call { hasFrame = false; phoneLatch = null; fixtureBinding = null }
    override fun redraw() { handler.post { transaction {
        if (!closed) try { draw(LocalDrawCause.LEGACY_REDRAW) } catch (error: Exception) { rendererFailed(error) }
    } } }

    private fun runFrame() {
        if (closed) return
        try {
            current(buffer)
            texture.updateTexImage(); fixtureBinding = null
            texture.getTransformMatrix(matrix)
            phoneLatch = PhoneTextureLatchKey(outputIdentity, ++phoneLatchSequence, texture.timestamp)
            hasFrame = true
            draw(LocalDrawCause.PHONE_LATCH)
        } catch (error: Exception) { if (!closed) rendererFailed(error) }
    }

    private fun runLocalFrame(original: CameraLease) {
        if (closed || original.retired || localInput !== original) return
        try {
            current(buffer)
            try {
                original.texture!!.updateTexImage()
                original.texture!!.getTransformMatrix(original.matrix)
                checkGl("Local texture latch failed")
                original.latch = LocalTextureLatch(++original.sequence, original.texture!!.timestamp)
            } catch (error: Exception) {
                if (error is SharedEglFailure) throw error
                original.record("latch", error)
                original.retire()?.let { if (it !== error) error.addSuppressed(it) }
                try { draw(LocalDrawCause.CAMERA_REMOVE) }
                finally { original.report("latch", error) }
                return
            }
            // Without a fixture binding this latch cannot change the window image.
            if (fixtureBinding != null) draw(LocalDrawCause.CAMERA_LATCH)
        } catch (error: Exception) { if (!closed) rendererFailed(error) }
    }

    private fun bindPixels(shader: Int, name: Int, transform: FloatArray, data: java.nio.FloatBuffer) {
        GLES20.glUseProgram(shader)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(shader, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(shader, "transform"), 1, false, transform, 0)
        val position = GLES20.glGetAttribLocation(shader, "position")
        val coordinate = GLES20.glGetAttribLocation(shader, "coordinate")
        data.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, data)
        data.position(2); GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, data)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coordinate)
    }

    private fun restorePhoneState(size: IntArray) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, size[0], size[1])
        bindPixels(program, textureName, matrix, vertices)
    }

    private fun drawPhone(size: IntArray) {
        restorePhoneState(size)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        try { checkGl("Video GL draw failed") } catch (error: Exception) {
            if (error is SharedEglFailure) throw error
            throw SharedEglFailure(error.message ?: "Video GL draw failed").also { it.addSuppressed(error) }
        }
    }

    private data class LocalDraw(val lease: CameraLease, val latch: LocalTextureLatch,
        val binding: FixturePhoneBinding)

    private fun drawLocal(original: CameraLease, binding: FixturePhoneBinding, size: IntArray): LocalDraw? {
        val layout = binding.layout ?: return null
        val latch = original.latch ?: return null
        val config = original.configuration
        if (original.retired || localInput !== original || binding.latch !== phoneLatch ||
            layout.image != binding.image || layout.cameraUse != config.source.use ||
            config.source.use.projectionEpoch != binding.image.projectionEpoch) return null
        val cells = layout.replacementScissors(size[0], size[1])
        if (cells.isEmpty()) return null
        val preview = layout.preview
        val geometry = binding.image.geometry
        val w = preview.width * size[0] / geometry.width
        val h = preview.height * size[1] / geometry.height
        val data = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // One full-preview quad, reused for every clip cell. UV never restarts at a control edge.
        for ((u, v) in listOf(0.0 to 1.0, 1.0 to 1.0, 0.0 to 0.0, 1.0 to 0.0)) {
            val x = preview.left + u * preview.width
            val y = preview.top + v * preview.height
            val camera = config.sampling.sample(u, v, w, h)
            data.put((2 * x / geometry.width - 1).toFloat())
            data.put((1 - 2 * y / geometry.height).toFloat())
            // CameraSampling is top-left source UV. Convert once to pre-SurfaceTexture GL UV;
            // the shader then applies this camera frame's separate SurfaceTexture matrix.
            data.put(camera.x.toFloat()); data.put((1 - camera.y).toFloat())
        }
        data.position(0)
        var primary: Exception? = null
        try {
            bindPixels(original.program, original.textureName, original.matrix, data)
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            for (cell in cells) {
                GLES20.glScissor(cell.x, cell.y, cell.width, cell.height)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                checkGl("Local GL draw failed")
            }
        } catch (error: Exception) { primary = error; throw error }
        finally {
            try {
                GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(original.program, "position"))
                GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(original.program, "coordinate"))
                restorePhoneState(size)
            } catch (restoreError: Exception) {
                if (primary != null) primary.addSuppressed(restoreError) else throw restoreError
            }
        }
        return LocalDraw(original, latch, binding)
    }

    private fun draw(cause: LocalDrawCause) {
        val owner = target ?: return
        if (!hasFrame) return
        val generation = targetGeneration
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            originalContextCurrent = false
            val code = EGL14.eglGetError()
            if (windowLost(code = code)) return
            throw SharedEglFailure("Video window makeCurrent failed", code)
        }
        originalContextCurrent = true
        val size = IntArray(2)
        if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0) ||
            !EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1)) {
            val code = EGL14.eglGetError()
            if (windowLost(code = code)) return
            throw SharedEglFailure("Video window geometry unavailable", code)
        }
        drawPhone(size)
        val original = localInput
        val binding = fixtureBinding
        var local: LocalDraw? = null
        var localError: Exception? = null
        if (original != null && binding != null) try {
            local = drawLocal(original, binding, size)
        } catch (error: Exception) {
            if (error is SharedEglFailure) throw error
            localError = error
            original.record("draw", error)
            original.retire()?.let { if (it !== error) error.addSuppressed(it) }
            // Erase any partial optional pass, restoring the complete Phone base before swap.
            drawPhone(size)
        }
        if (!EGL14.eglSwapBuffers(display, window)) {
            val code = EGL14.eglGetError()
            val lost = windowLost(code = code)
            localError?.let { original?.report("draw", it) }
            if (lost) return
            throw SharedEglFailure("Video window presentation failed", code)
        }
        val swapReturn = System.nanoTime()
        // All GL restoration is complete BEFORE callbacks, which may reenter/close this output.
        var primary: Exception? = null
        if (cause.reportsLegacyPhone) try { presented(owner, generation) } catch (error: Exception) { primary = error }
        local?.let {
            try { it.lease.observer?.presented(LocalPresentationReceipt(it.lease, it.lease.configuration,
                it.latch, it.binding, owner, generation, cause, swapReturn)) }
            catch (error: Throwable) { it.lease.report("observer",
                error as? Exception ?: RuntimeException("Local presentation observer failed", error)) }
        }
        localError?.let { original?.report("draw", it) }
        if (primary != null) throw primary
    }

    private fun windowLost(failedTarget: Surface? = target, code: Int = EGL14.eglGetError()): Boolean {
        if (code != EGL14.EGL_BAD_SURFACE && code != EGL14.EGL_BAD_NATIVE_WINDOW) return false
        current(buffer)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE; target = null
        failed(PresentationSurfaceLost(failedTarget, targetGeneration))
        return true
    }

    private fun program(): Int {
        fun shader(type: Int, source: String): Int {
            val value = GLES20.glCreateShader(type)
            try {
                check(value != 0) { "GL shader unavailable" }
                GLES20.glShaderSource(value, source); GLES20.glCompileShader(value)
                val status = IntArray(1); GLES20.glGetShaderiv(value, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetShaderInfoLog(value) }
                return value
            } catch (error: Exception) { if (value != 0) GLES20.glDeleteShader(value); throw error }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 position; attribute vec4 coordinate; uniform mat4 transform; varying vec2 uv; void main(){gl_Position=position;uv=(transform*coordinate).xy;}")
        val fragment = try { shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}")
        } catch (error: Exception) { GLES20.glDeleteShader(vertex); throw error }
        val value = GLES20.glCreateProgram()
        try {
            check(value != 0) { "GL program unavailable" }
            GLES20.glAttachShader(value, vertex); GLES20.glAttachShader(value, fragment); GLES20.glLinkProgram(value)
            val status = IntArray(1); GLES20.glGetProgramiv(value, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(value) }
            return value
        } catch (error: Exception) { if (value != 0) GLES20.glDeleteProgram(value); throw error }
        finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
    }

    private fun recordOutputFailure(operation: String, error: Throwable) {
        synchronized(outputLock) {
            if (outputFailures.none { it.operation == operation && it.error === error })
                outputFailures.add(LocalReleaseFailure(operation, error))
            releaseResult = releaseResult?.copy(failures = outputFailures.toList())
        }
    }

    private fun outputStep(name: String, acquired: Boolean = true, action: () -> Unit) {
        val attempt = synchronized(outputLock) {
            if (outputSteps.containsKey(name)) false else {
                outputSteps[name] = LocalReleaseStep(LocalReleaseDisposition.NOT_ATTEMPTED)
                acquired
            }
        }
        if (!attempt) return
        val outcome = try { action(); LocalReleaseStep(LocalReleaseDisposition.RETURNED) }
            catch (error: Throwable) {
                recordOutputFailure(name, error)
                LocalReleaseStep(LocalReleaseDisposition.FAILED, error)
            }
        synchronized(outputLock) { outputSteps[name] = outcome }
    }

    private fun requestParentClose() {
        if (parentCloseStarted) return
        closed = true
        parentCloseStarted = true
        phoneLatch = null; fixtureBinding = null
        parentRetiring = true
        try {
            val active = localInput
            var contextError: Exception? = null
            if (active != null) try { current(buffer) } catch (error: Exception) { contextError = error }
            for (original in inputs.toList()) {
                if (original === active && contextError != null) original.record("parent-close", contextError)
                val cleanup = original.retire(deleteGl = contextError == null)
                val error = if (original === active) contextError ?: cleanup else cleanup
                if (error != null) {
                    if (cleanup != null && cleanup !== error) error.addSuppressed(cleanup)
                    original.report("parent-close", error)
                }
            }
            contextError?.let { original ->
                recordOutputFailure("local-make-current", original)
                try { rendererFailed(original) } catch (observerError: Throwable) {
                    if (observerError !== original) original.addSuppressed(observerError)
                    recordOutputFailure("failure-observer", observerError)
                }
            }
        } finally { parentRetiring = false }
        maybeFinalizeParent()
    }

    private fun maybeFinalizeParent() {
        if (!parentCloseStarted || parentRetiring || parentFinalized || inputs.any { !it.finished() }) return
        parentFinalized = true
        outputStep("surfaceRelease", ::surface.isInitialized) { surface.release() }
        outputStep("surfaceTextureRelease", ::texture.isInitialized) { texture.release() }
        var ready = false
        outputStep("makeCurrent", context != EGL14.EGL_NO_CONTEXT && buffer != EGL14.EGL_NO_SURFACE && (!contextLostObserved || !hasBoundProducer)) {
            current(buffer); ready = true
        }
        outputStep("programDelete", ready && program != 0) { GLES20.glDeleteProgram(program) }
        outputStep("textureDelete", ready && textureName != 0) { GLES20.glDeleteTextures(1, intArrayOf(textureName), 0) }
        outputStep("eglUnbind") {
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
                "EGL unbind failed"
            }
            originalContextCurrent = false
        }
        outputStep("windowDestroy", window != EGL14.EGL_NO_SURFACE) { check(EGL14.eglDestroySurface(display, window)) { "EGL window destruction failed" } }
        outputStep("bufferDestroy", buffer != EGL14.EGL_NO_SURFACE) { check(EGL14.eglDestroySurface(display, buffer)) { "EGL buffer destruction failed" } }
        outputStep("contextDestroy", context != EGL14.EGL_NO_CONTEXT) { check(EGL14.eglDestroyContext(display, context)) { "EGL context destruction failed" } }
        outputStep("displayTerminate") { check(EGL14.eglTerminate(display)) { "EGL termination failed" } }
        outputStep("eglReleaseThread") { check(EGL14.eglReleaseThread()) { "EGL releaseThread failed" } }
        quitWorker()
    }

    private fun quitWorker() {
        outputStep("quitSafely") { check(thread.quitSafely()) { "Output quitSafely was not accepted" } }
        val quitReturned = synchronized(outputLock) {
            outputSteps["quitSafely"]?.disposition == LocalReleaseDisposition.RETURNED
        }
        val start = synchronized(outputLock) {
            if (joinObserverStarted) false else { joinObserverStarted = true; true }
        }
        if (!start) return
        // Joining never runs in a caller Executor: execute() may run inline or reject.
        try { Thread({
            if (!quitReturned) {
                finishOutputJoin(LocalReleaseStep(LocalReleaseDisposition.NOT_ATTEMPTED), thread.isAlive)
                return@Thread
            }
            try {
                thread.join()
                finishOutputJoin(LocalReleaseStep(LocalReleaseDisposition.RETURNED), thread.isAlive)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                recordOutputFailure("worker-join", error)
                finishOutputJoin(LocalReleaseStep(LocalReleaseDisposition.FAILED, error), thread.isAlive)
            }
        }, "eyeprojector-video-output-join").apply { isDaemon = true; start() } }
        catch (error: Throwable) {
            recordOutputFailure("worker-join-start", error)
            finishOutputJoin(LocalReleaseStep(LocalReleaseDisposition.NOT_ATTEMPTED, error), thread.isAlive)
        }
    }

    private fun finishOutputJoin(join: LocalReleaseStep, alive: Boolean) {
        val completed: Pair<LocalOutputReleaseResult, List<Pair<Executor, (LocalOutputReleaseResult) -> Unit>>> = synchronized(outputLock) {
            if (releaseResult != null) return
            val result = LocalOutputReleaseResult(outputIdentity, inputs.mapNotNull { it.releaseResult },
                outputSteps.toMap(), join, alive, outputFailures.toList())
            releaseResult = result
            val callbacks = outputObservers.toList(); outputObservers.clear()
            result to callbacks
        }
        for ((executor, callback) in completed.second) dispatchOutput(executor, callback, completed.first)
    }

    private fun dispatchOutput(executor: Executor, callback: (LocalOutputReleaseResult) -> Unit,
        result: LocalOutputReleaseResult) {
        try { executor.execute {
            try { callback(result) } catch (error: Throwable) { recordOutputFailure("completion-observer", error) }
        } } catch (error: Throwable) { recordOutputFailure("completion-dispatch", error) }
    }

    private fun outputSubmissionFailed(error: Throwable) {
        recordOutputFailure("retirement-post", error)
    }

    override fun closeAsync(completionExecutor: Executor, onRelease: (LocalOutputReleaseResult) -> Unit) {
        val submission = synchronized(outputLock) {
            val completed = releaseResult
            if (completed == null) outputObservers.add(completionExecutor to onRelease)
            val submit = completed == null && !parentSubmissionClaimed
            if (submit) {
                parentSubmissionClaimed = true
                closed = true // Private original finalizers remain admitted.
            }
            completed to submit
        }
        val result = submission.first
        if (result != null) { dispatchOutput(completionExecutor, onRelease, result); return }
        if (!submission.second) return
        try {
            if (!handler.post { transaction { requestParentClose() } })
                outputSubmissionFailed(IllegalStateException("Original output retirement lane stopped"))
        } catch (error: Throwable) { outputSubmissionFailed(error) }
    }

    override fun close() {
        val submit = synchronized(outputLock) {
            if (parentSubmissionClaimed) false else {
                parentSubmissionClaimed = true
                closed = true
                true
            }
        }
        if (!submit) return
        var legacy = false
        try { call {
            requestParentClose()
            // Admission is now closed and retirement crossed every bind-versus-retire lock.
            legacy = !hasBoundProducer
        } } catch (error: Throwable) {
            recordOutputFailure("close-submission-or-retirement", error)
            throw error
        } finally {
            if (legacy) {
                // Preserve synchronous legacy no-camera/unbound close, including actual join.
                quitWorker()
                if (Thread.currentThread() !== thread) {
                    thread.join()
                    finishOutputJoin(LocalReleaseStep(LocalReleaseDisposition.RETURNED), thread.isAlive)
                }
            }
        }
    }
}
