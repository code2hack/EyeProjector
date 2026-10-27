package com.code2hack.eyeprojector.directhost.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.code2hack.eyeprojector.protocol.CameraEndReason
import com.code2hack.eyeprojector.protocol.CameraFact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.Executor

data class PhoneOutputTargetSnapshot(
    val original: PhoneOutputTarget,
    val admissionClosed: Boolean,
    val closeReason: CameraEndReason?,
    val bindingFinished: Boolean,
    val producerBound: Boolean,
    val borrowedInstalled: Boolean,
    val capturedCalls: Int,
    val drawReturns: Long,
    val swapReturns: Long,
    val windowCreateAttempted: Boolean,
    val windowCreated: Boolean,
    val glFinishReturned: Boolean,
    val pbufferRestored: Boolean,
    val windowDestroyReturned: Boolean,
    val knownNoNativeUse: Boolean,
    val producerUseStopped: Boolean,
    val failure: Throwable?,
)

/** Admission exists before bindProducer can invoke the captured stop closure. */
class PhoneOutputTarget(val lease: PhoneCameraLease, val producer: PhoneInputProducerIdentity) {
    internal val use = PhoneTargetUseState<Surface>()
    private var owner: PhoneCameraSurfaceFanout? = null

    fun closeAdmission(reason: CameraEndReason) = use.closeAdmission(reason)
    fun finishBinding(bound: Boolean) = use.finishBinding(bound)
    fun installBorrowed(surface: Surface): Boolean = use.installBorrowed(surface)
    fun snapshot(): PhoneOutputTargetSnapshot = synchronized(use.gate) {
        PhoneOutputTargetSnapshot(this, use.closed, use.reason, use.bindingFinished, use.bound,
            use.borrowed != null, use.calls, use.drawReturns, use.swapReturns, use.windowAttempted, use.windowCreated,
            use.finishReturned, use.pbufferRestored, use.destroyReturned,
            use.knownNoNativeUse, use.fenced, use.failure)
    }
    internal fun attach(original: PhoneCameraSurfaceFanout, wake: () -> Unit): Boolean = synchronized(use.gate) {
        if (owner != null) return@synchronized owner === original
        owner = original
        use.wake = wake
        true
    }
}

/** The same admission/fence state is exercised without pretending that host tests implement EGL. */
internal class PhoneTargetUseState<S : Any> {
    val gate = Any()
    var closed = false
    var reason: CameraEndReason? = null
    var bindingFinished = false
    var bound = false
    var borrowed: S? = null
    var calls = 0
    var drawReturns = 0L
    var swapReturns = 0L
    var windowAttempted = false
    var windowCreated = false
    var finishReturned = false
    var pbufferRestored = false
    var destroyReturned = false
    var knownNoNativeUse = false
    var fenced = false
    var failure: Throwable? = null
    private var fenceClaimed = false
    var wake: (() -> Unit)? = null

    fun closeAdmission(value: CameraEndReason) {
        val notify = synchronized(gate) {
            if (!closed) { closed = true; reason = value }
            wake
        }
        notify?.invoke()
    }
    fun finishBinding(value: Boolean) {
        val notify = synchronized(gate) {
            if (!bindingFinished) { bindingFinished = true; bound = value }
            else if (bound != value && failure == null) failure = IllegalStateException("Original binding outcome changed")
            wake
        }
        notify?.invoke()
    }
    fun installBorrowed(value: S): Boolean = synchronized(gate) {
        if (closed || !bindingFinished || !bound) false
        else if (borrowed == null || borrowed === value) { borrowed = value; true }
        else false
    }
    fun capture(): S? = synchronized(gate) {
        if (closed || !bound || !bindingFinished) null
        else borrowed?.also { calls++ }
    }
    fun returned() { synchronized(gate) { check(calls > 0); calls-- } }
    fun failed(error: Throwable) { synchronized(gate) { if (failure == null) failure = error } }
    fun claimFence(): Boolean = synchronized(gate) {
        if (!closed || !bindingFinished || !bound || calls != 0 || fenceClaimed) false
        else if (!windowAttempted || windowCreated && finishReturned && pbufferRestored && destroyReturned) {
            fenceClaimed = true
            knownNoNativeUse = !windowAttempted
            true
        } else false
    }
    fun fenceOutcome(accepted: Boolean, error: Throwable?) = synchronized(gate) {
        if (accepted) fenced = true
        else if (failure == null) failure = error ?: IllegalStateException("Original producer fence was not accepted")
    }
    fun noBoundDuty(): Boolean = synchronized(gate) { bindingFinished && !bound }
}

data class PhoneTargetAdmission(val accepted: Boolean, val fact: CameraFact)
data class PhoneTargetRetirementSubmission(val fact: CameraFact)
data class PhoneDecoderSurfaceSnapshot(
    val original: PhoneDecoderEpoch,
    val prepared: Boolean,
    val renderAdmissionClosed: Boolean,
    val decoderUseStopped: Boolean,
    val surfaceAcquired: Boolean,
    val surfaceTextureAcquired: Boolean,
    val oesAcquired: Boolean,
    val nativeAcquisitionUnconfirmed: Boolean,
    val glFinishReturned: Boolean,
    val pbufferRestored: Boolean,
    val listenerDetached: Boolean,
    val surfaceReleaseReturned: Boolean,
    val surfaceTextureReleaseReturned: Boolean,
    val oesDeleteReturned: Boolean,
    val frameCallbacks: Long,
    /** Returned updateTexImage calls; this is not a count of distinct buffers or captured images. */
    val framesLatched: Long,
    val capturedDrawCalls: Int,
    val lastTextureTimestampNanos: Long?,
    val failure: Throwable?,
)

/** Borrowed by this exact codec epoch. Only the fanout releases this Surface wrapper. */
class OwnedPhoneDecoderSurface internal constructor(
    val original: PhoneDecoderEpoch,
    private val owner: PhoneCameraSurfaceFanout,
) {
    fun surfaceFor(epoch: PhoneDecoderEpoch): Surface? = owner.surfaceFor(this, epoch)
    fun snapshot(): PhoneDecoderSurfaceSnapshot = owner.decoderSnapshot(original)
}

data class PhoneFanoutObservation(
    val group: CapturedPhoneDemandGroup,
    val stage: String,
    val target: PhoneOutputTarget? = null,
    val decoder: PhoneDecoderEpoch? = null,
    val mapping: PhoneFrameMapping? = null,
    val failure: Throwable? = null,
    val targetSnapshot: PhoneOutputTargetSnapshot? = target?.snapshot(),
    val decoderSnapshot: PhoneDecoderSurfaceSnapshot? = null,
    val frameLatched: Boolean = false,
    val drawReturned: Boolean = false,
    val swapReturned: Boolean = false,
)
data class PhoneFanoutCloseObservation(
    val group: CapturedPhoneDemandGroup,
    val targets: List<PhoneOutputTargetSnapshot>,
    val decoders: List<PhoneDecoderSurfaceSnapshot>,
    val nativeResourcesClear: Boolean,
    val loopReturned: Boolean,
    val joined: Boolean,
    val failure: Throwable?,
    val observerFailures: List<Throwable>,
)

/**
 * One original demand group's serial EGL lane. Construction is inert. There is no draw timer:
 * only an actual SurfaceTexture callback can enqueue a texture latch and output draw/swap.
 * A stalled native call retains this lane's duty, without blocking the control/admission lock.
 */
class PhoneCameraSurfaceFanout(
    val originalGroup: CapturedPhoneDemandGroup,
    private val framing: PhoneCameraFraming,
    private val observations: (PhoneFanoutObservation) -> Unit,
) {
    private val gate = Object()
    private class TargetRecord(val original: PhoneOutputTarget) {
        var window: EGLSurface? = null // GL-lane only; never the lease's Surface release owner.
        var retirementAttempted = false
    }
    private class DecoderRecord(val original: PhoneDecoderEpoch) {
        lateinit var borrowed: OwnedPhoneDecoderSurface
        val native = DecoderTexture()
        var publishedSurface: Surface? = null
        var prepared = false
        var renderClosed = false
        var useStopped = false
        var listenerDetached = false
        var surfaceAcquired = false
        var textureAcquired = false
        var oesAcquired = false
        var acquisitionUnconfirmed = false
        var finishReturned = false
        var pbufferRestored = false
        var surfaceReleased = false
        var textureReleased = false
        var oesDeleted = false
        var cleanupAttempted = false
        var format: PhoneDecodedFormat? = null
        var callbacks = 0L
        var latched = 0L
        var drawCalls = 0
        var timestamp: Long? = null
        var failure: Throwable? = null
    }
    private val targets = IdentityHashMap<PhoneOutputTarget, TargetRecord>()
    private val decoders = IdentityHashMap<PhoneDecoderEpoch, DecoderRecord>()
    private val retiredDecoderAdmissions = IdentityHashMap<PhoneDecoderEpoch, Boolean>()
    private var activeDecoder: DecoderRecord? = null
    private val queue = ArrayDeque<() -> Unit>()
    private var started = false
    private var handler: Handler? = null
    private var drainPosted = false
    private var closing = false
    private var exitRequested = false
    private var loopReturned = false
    private var joined = false
    private var primaryFailure: Throwable? = null
    private val observerFailures = ArrayList<Throwable>()
    private val gl = RealPhoneGl()
    private var glCloseAttempted = false
    private var glClosed = false
    private val lane = object : HandlerThread("phone-camera-gl-${System.identityHashCode(this)}") {
        override fun onLooperPrepared() {
            val actualLooper = Looper.myLooper() ?: error("Original GL thread has no prepared Looper")
            synchronized(gate) { handler = Handler(actualLooper) }
            scheduleDrain()
        }
        override fun run() {
            try { super.run() }
            catch (error: Throwable) { recordFailure(error) }
            finally { synchronized(gate) { loopReturned = true; gate.notifyAll() } }
        }
    }.apply { isDaemon = false } // A retained pending native duty must not silently disappear with daemon exit.

    fun addTarget(original: PhoneOutputTarget): PhoneTargetAdmission {
        val enrolled = synchronized(gate) {
            if (targets.containsKey(original)) return PhoneTargetAdmission(true, CameraFact.STALE_OR_DUPLICATE)
            if (exitRequested || original.lease.registration !== originalGroup.registration ||
                !original.attach(this) { post { retireOnLane(original) } }) false
            else { targets[original] = TargetRecord(original); true }
        }
        if (!enrolled) return PhoneTargetAdmission(false, CameraFact.REJECTED)
        if (synchronized(gate) { closing }) original.closeAdmission(CameraEndReason.ORIGINAL_RETIRED)
        val posted = post { installTargetOnLane(original) }
        if (!posted) recordFailure(IllegalStateException("Enrolled target's native lane is unavailable"))
        return PhoneTargetAdmission(posted, if (posted) CameraFact.PENDING else CameraFact.FAILED)
    }

    fun retireTarget(original: PhoneOutputTarget, reason: CameraEndReason): PhoneTargetRetirementSubmission {
        original.closeAdmission(reason)
        val known = synchronized(gate) { targets.containsKey(original) }
        if (!known) return PhoneTargetRetirementSubmission(CameraFact.UNAVAILABLE)
        return PhoneTargetRetirementSubmission(if (post { retireOnLane(original) }) CameraFact.PENDING else CameraFact.FAILED)
    }

    fun prepareDecoderSurface(original: PhoneDecoderEpoch, completion: (OwnedPhoneDecoderSurface) -> Unit) {
        val record = synchronized(gate) {
            decoders[original]?.let { return@synchronized it }
            DecoderRecord(original).also {
                it.borrowed = OwnedPhoneDecoderSurface(original, this)
                it.renderClosed = closing || retiredDecoderAdmissions.containsKey(original) || original.group !== originalGroup
                decoders[original] = it
                if (!it.renderClosed) { activeDecoder?.renderClosed = true; activeDecoder = it }
            }
        }
        if (!post {
                if (!synchronized(gate) { record.renderClosed || record.prepared || record.failure != null }) {
                    try {
                        emit("DECODER_SURFACE_PREPARATION_STARTED", decoder = record)
                        gl.initialize()
                        gl.prepareDecoder(record.native, original.description.request.requestedWidth,
                            original.description.request.requestedHeight,
                            synchronized(gate) { handler ?: error("Original GL callback Handler absent") }) { onFrame(record) }
                        synchronized(gate) {
                            record.surfaceAcquired = record.native.surface != null
                            record.textureAcquired = record.native.texture != null
                            record.oesAcquired = record.native.oes != 0
                            record.acquisitionUnconfirmed = record.native.acquisitionUnconfirmed
                            record.publishedSurface = record.native.surface
                            record.prepared = true
                        }
                        emit("DECODER_SURFACE_PREPARED", decoder = record)
                    } catch (error: Throwable) { decoderFailure(record, error) }
                    finally {
                        synchronized(gate) {
                            record.surfaceAcquired = record.native.surface != null
                            record.textureAcquired = record.native.texture != null
                            record.oesAcquired = record.native.oes != 0
                            record.acquisitionUnconfirmed = record.native.acquisitionUnconfirmed
                        }
                    }
                }
                try { completion(record.borrowed) } catch (error: Throwable) { recordFailure(error) }
            }) {
            decoderFailure(record, IllegalStateException("Decoder surface preparation was not admitted"))
            try { completion(record.borrowed) } catch (error: Throwable) { recordFailure(error) }
        }
    }

    fun updateFormat(original: PhoneDecoderEpoch, format: PhoneDecodedFormat) {
        post {
            val record = synchronized(gate) { decoders[original] }
            if (record != null && original.group === originalGroup) {
                synchronized(gate) { record.format = format }
                emit("ACTUAL_CODEC_OUTPUT_FORMAT", decoder = record)
            }
        }
    }

    /** Logical retirement closes new render claims even when it precedes surface preparation. */
    fun closeDecoderAdmission(original: PhoneDecoderEpoch) {
        synchronized(gate) {
            retiredDecoderAdmissions[original] = true
            decoders[original]?.let {
                it.renderClosed = true
                if (activeDecoder === it) activeDecoder = null
            }
        }
        // No native call, wait or Surface-use fence: an already captured draw retains its duty.
    }

    fun decoderStopped(original: PhoneDecoderEpoch, fact: PhoneDecoderUseStopped) {
        val record = synchronized(gate) {
            val value = decoders[original]
            if (value == null || fact.original !== original || original.group !== originalGroup ||
                !(fact.codecReleaseReturned || fact.noConfigureAttempted)) null
            else value.also { it.useStopped = true; it.renderClosed = true }
        } ?: return
        if (!post { cleanupDecoderOnLane(record); maybeFinishOnLane() })
            decoderFailure(record, IllegalStateException("Exact stopped decoder's GL cleanup remains unadmitted"))
    }

    internal fun surfaceFor(wrapper: OwnedPhoneDecoderSurface, epoch: PhoneDecoderEpoch): Surface? = synchronized(gate) {
        val record = decoders[epoch]
        if (wrapper.original !== epoch || record == null || record.borrowed !== wrapper || record.useStopped ||
            record.renderClosed || !record.prepared) null else record.publishedSurface
    }

    internal fun decoderSnapshot(original: PhoneDecoderEpoch): PhoneDecoderSurfaceSnapshot = synchronized(gate) {
        snapshotDecoder(decoders[original] ?: error("Unknown original decoder surface"))
    }
    private fun snapshotDecoder(r: DecoderRecord) = PhoneDecoderSurfaceSnapshot(r.original, r.prepared,
        r.renderClosed, r.useStopped, r.surfaceAcquired, r.textureAcquired, r.oesAcquired,
        r.acquisitionUnconfirmed,
        r.finishReturned, r.pbufferRestored,
        r.listenerDetached, r.surfaceReleased, r.textureReleased,
        r.oesDeleted, r.callbacks, r.latched, r.drawCalls, r.timestamp, r.failure)

    /** Close only admission here; native calls and actual joins never run through the supplied executor. */
    fun closeAsync(executor: Executor, completion: (PhoneFanoutCloseObservation) -> Unit) {
        val originals = synchronized(gate) {
            closing = true
            decoders.values.forEach { it.renderClosed = true }
            targets.keys.toList()
        }
        originals.forEach { it.closeAdmission(CameraEndReason.ORIGINAL_RETIRED) }
        post {
            originals.forEach { retireOnLane(it) }
            synchronized(gate) { decoders.values.toList() }.forEach { cleanupDecoderOnLane(it) }
            maybeFinishOnLane()
        }
        // Even Executor { it.run() } cannot place this wait/join on the control or GL caller.
        try {
            Thread({
                try {
                    synchronized(gate) { while (!exitRequested && !loopReturned) gate.wait() }
                    lane.join()
                    synchronized(gate) { joined = !lane.isAlive }
                    val value = closeSnapshot()
                    try { executor.execute { try { completion(value) } catch (error: Throwable) { observerFailure(error) } } }
                    catch (error: Throwable) { observerFailure(error) }
                } catch (error: InterruptedException) { Thread.currentThread().interrupt(); recordFailure(error) }
            }, "${lane.name}-close-join").apply { isDaemon = true; start() }
        } catch (error: Throwable) { recordFailure(error) }
    }

    fun snapshot(): PhoneFanoutCloseObservation = closeSnapshot()
    private fun closeSnapshot(): PhoneFanoutCloseObservation = synchronized(gate) {
        PhoneFanoutCloseObservation(originalGroup, targets.keys.map { it.snapshot() },
            decoders.values.map(::snapshotDecoder), glClosed && targets.values.all(::targetClear) &&
                decoders.values.all(::decoderClear), loopReturned, joined, primaryFailure, observerFailures.toList())
    }

    private fun installTargetOnLane(original: PhoneOutputTarget) {
        val record = synchronized(gate) { targets[original] } ?: return
        val borrowed = original.use.capture()
        if (borrowed != null) {
            try {
                gl.initialize()
                synchronized(original.use.gate) { original.use.windowAttempted = true }
                emit("ORIGINAL_WINDOW_CREATE_STARTED", target = original)
                record.window = gl.createWindow(borrowed)
                synchronized(original.use.gate) { original.use.windowCreated = true }
                emit("ORIGINAL_WINDOW_CREATED", target = original)
            } catch (error: Throwable) { targetFailure(original, error) }
            finally { original.use.returned() }
        }
        if (original.snapshot().admissionClosed) retireOnLane(original)
    }

    private fun onFrame(record: DecoderRecord) {
        synchronized(gate) { record.callbacks++ }
        post { latchOnLane(record) }
    }

    private fun latchOnLane(record: DecoderRecord) {
        val allowed = synchronized(gate) {
            !closing && activeDecoder === record && !record.renderClosed && !record.useStopped && record.prepared
        }
        if (!allowed) return
        try {
            gl.restorePbuffer()
            emit("ACTUAL_TEXTURE_LATCH_STARTED", decoder = record)
            val actual = gl.latch(record.native)
            synchronized(gate) { record.latched++; record.timestamp = actual.timestampNanos }
            emit("ACTUAL_TEXTURE_LATCH_RETURN", decoder = record, latched = true)
            // No guessed format: a real latched frame can precede the actual codec-format callback.
            val format = synchronized(gate) { record.format } ?: return
            for (target in synchronized(gate) { targets.values.toList() }) {
                val window = target.window ?: continue
                target.original.use.capture() ?: continue
                var decoderCaptured = false
                try {
                    val stillOriginal = synchronized(gate) {
                        if (closing || activeDecoder !== record || record.renderClosed || record.useStopped) false
                        else { record.drawCalls++; decoderCaptured = true; true }
                    }
                    if (!stillOriginal) continue
                    // Owner observed the current real Glasses feed 90 degrees clockwise.
                    val mapping = framing.observe(format, actual.matrix, target.original.lease.width,
                        target.original.lease.height, ownerCalibratedCcwQuarterTurns = 1)
                    emit("ORIGINAL_TARGET_DRAW_STARTED", target.original, record, mapping, latched = true)
                    gl.draw(window, record.native, mapping, target.original.lease.width, target.original.lease.height)
                    synchronized(target.original.use.gate) { target.original.use.drawReturns++ }
                    emit("ORIGINAL_TARGET_DRAW_RETURN", target.original, record, mapping, latched = true, drawn = true)
                    emit("ORIGINAL_TARGET_SWAP_STARTED", target.original, record, mapping, latched = true, drawn = true)
                    gl.swap(window, actual.timestampNanos)
                    synchronized(target.original.use.gate) { target.original.use.swapReturns++ }
                    emit("ORIGINAL_TARGET_SWAP_RETURN", target.original, record, mapping, latched = true, drawn = true, swapped = true)
                } catch (error: Throwable) { targetFailure(target.original, error) }
                finally {
                    if (decoderCaptured) synchronized(gate) { record.drawCalls-- }
                    target.original.use.returned()
                }
                if (target.original.snapshot().admissionClosed) retireOnLane(target.original)
            }
            gl.restorePbuffer()
        } catch (error: Throwable) { decoderFailure(record, error) }
    }

    private fun retireOnLane(original: PhoneOutputTarget) {
        val record = synchronized(gate) { targets[original] } ?: return
        if (!original.snapshot().admissionClosed) return
        val window = record.window
        if (window != null && !record.retirementAttempted) {
            record.retirementAttempted = true
            emit("ORIGINAL_TARGET_NATIVE_RETIREMENT_STARTED", target = original)
            // GL lane serialization has retired every earlier captured native call.
            try { gl.finish(); synchronized(original.use.gate) { original.use.finishReturned = true } }
            catch (error: Throwable) { targetFailure(original, error) }
            try { gl.restorePbuffer(); synchronized(original.use.gate) { original.use.pbufferRestored = true } }
            catch (error: Throwable) { targetFailure(original, error) }
            try {
                gl.destroyWindow(window)
                record.window = null
                synchronized(original.use.gate) { original.use.destroyReturned = true }
            } catch (error: Throwable) { targetFailure(original, error) }
        }
        if (original.use.claimFence()) {
            var accepted = false
            var failure: Throwable? = null
            try { accepted = original.lease.producerStopped(original.producer, PhoneInputUseStopped(original.lease, original.producer)) }
            catch (error: Throwable) { failure = error }
            original.use.fenceOutcome(accepted, failure)
            emit(if (accepted) "ORIGINAL_PRODUCER_USE_STOPPED" else "ORIGINAL_PRODUCER_FENCE_FAILED", target = original,
                failure = failure ?: original.snapshot().failure)
        }
        maybeFinishOnLane()
    }

    private fun cleanupDecoderOnLane(record: DecoderRecord) {
        if (!synchronized(gate) { record.useStopped } || record.cleanupAttempted) return
        record.cleanupAttempted = true
        if (record.native.surface != null || record.native.texture != null || record.native.oes != 0) {
            try {
                emit("EXACT_STOPPED_DECODER_GL_RETIREMENT_STARTED", decoder = record)
                gl.restorePbuffer()
                synchronized(gate) { record.pbufferRestored = true }
                gl.finish()
                synchronized(gate) { record.finishReturned = true }
            } catch (error: Throwable) {
                decoderFailure(record, error)
                return // Keep the original resources when GL's captured use has no observed completion.
            }
        }
        // Each absence/return is distinct; a failure does not turn a later step into a fabricated return.
        try {
            if (record.native.texture != null) {
                record.native.texture!!.setOnFrameAvailableListener(null)
                synchronized(gate) { record.listenerDetached = true }
            }
        } catch (error: Throwable) { decoderFailure(record, error) }
        try {
            if (record.native.surface != null) {
                record.native.surface!!.release()
                record.native.surface = null
                synchronized(gate) { record.publishedSurface = null; record.surfaceReleased = true }
            }
        } catch (error: Throwable) { decoderFailure(record, error) }
        try {
            if (record.native.texture != null) {
                record.native.texture!!.release()
                record.native.texture = null
                synchronized(gate) { record.textureReleased = true }
            }
        } catch (error: Throwable) { decoderFailure(record, error) }
        try {
            if (record.native.oes != 0) {
                gl.restorePbuffer(); gl.deleteTexture(record.native.oes); record.native.oes = 0
                synchronized(gate) { record.oesDeleted = true }
            }
        } catch (error: Throwable) { decoderFailure(record, error) }
        emit("EXACT_DECODER_GL_CLEANUP_OBSERVATION", decoder = record)
    }

    private fun targetClear(record: TargetRecord): Boolean =
        record.original.use.noBoundDuty() || record.original.snapshot().producerUseStopped
    private fun decoderClear(record: DecoderRecord): Boolean = record.useStopped && !record.acquisitionUnconfirmed &&
        (!(record.surfaceAcquired || record.textureAcquired || record.oesAcquired) || record.finishReturned && record.pbufferRestored) &&
        (!record.textureAcquired || record.listenerDetached && record.textureReleased) &&
        (!record.surfaceAcquired || record.surfaceReleased) && (!record.oesAcquired || record.oesDeleted)

    private fun maybeFinishOnLane() {
        val eligible = synchronized(gate) { closing && targets.values.all(::targetClear) && decoders.values.all(::decoderClear) }
        if (!eligible || glCloseAttempted) return
        glCloseAttempted = true
        emit("OWNED_EGL_CLEANUP_STARTED")
        try { gl.close(); synchronized(gate) { glClosed = true } }
        catch (error: Throwable) { recordFailure(error); emit("OWNED_EGL_CLEANUP_FAILED", failure = error) }
        // A failed native cleanup remains an owned pending duty, rather than an invented clean/join fact.
        if (synchronized(gate) { glClosed }) synchronized(gate) { exitRequested = true; gate.notifyAll() }
    }

    private fun post(action: () -> Unit): Boolean {
        val launch = synchronized(gate) {
            if (exitRequested || loopReturned) return false
            queue.addLast(action)
            val first = !started
            started = true
            first
        }
        if (launch) try { lane.start() } catch (error: Throwable) { recordFailure(error) }
        else scheduleDrain()
        return true
    }
    private fun scheduleDrain() {
        val originalHandler = synchronized(gate) {
            val ready = handler
            if (ready == null || drainPosted || loopReturned) null
            else { drainPosted = true; ready }
        } ?: return
        try {
            if (!originalHandler.post { drainOnLane() }) {
                synchronized(gate) { drainPosted = false }
                recordFailure(IllegalStateException("Original GL Handler rejected its owned drain"))
            }
        } catch (error: Throwable) {
            synchronized(gate) { drainPosted = false }
            recordFailure(error)
        }
    }
    private fun drainOnLane() {
        try {
            while (true) {
                val action = synchronized(gate) {
                    if (queue.isEmpty()) { drainPosted = false; null } else queue.removeFirst()
                }
                if (action == null) {
                    if (synchronized(gate) { exitRequested } && !lane.quitSafely())
                        recordFailure(IllegalStateException("Original GL quitSafely was not accepted"))
                    return
                }
                try { action() } catch (error: Throwable) { recordFailure(error) }
            }
        } catch (error: Throwable) { recordFailure(error) }
    }
    private fun targetFailure(target: PhoneOutputTarget, error: Throwable) {
        target.use.failed(error)
        target.closeAdmission(CameraEndReason.LOCAL_RELEASE_FAILURE)
        recordFailure(error)
        emit("ORIGINAL_TARGET_NATIVE_FAILURE", target = target, failure = error)
    }
    private fun decoderFailure(record: DecoderRecord, error: Throwable) {
        synchronized(gate) { if (record.failure == null) record.failure = error }
        recordFailure(error)
        emit("ORIGINAL_DECODER_GL_FAILURE", decoder = record, failure = error)
    }
    private fun recordFailure(error: Throwable) { synchronized(gate) { if (primaryFailure == null) primaryFailure = error } }
    private fun observerFailure(error: Throwable) { synchronized(gate) { observerFailures.add(error) } }
    private fun emit(stage: String, target: PhoneOutputTarget? = null, decoder: DecoderRecord? = null,
        mapping: PhoneFrameMapping? = null, failure: Throwable? = null, latched: Boolean = false,
        drawn: Boolean = false, swapped: Boolean = false) {
        val value = PhoneFanoutObservation(originalGroup, stage, target, decoder?.original, mapping, failure,
            target?.snapshot(), decoder?.let { synchronized(gate) { snapshotDecoder(it) } }, latched, drawn, swapped)
        try { observations(value) } catch (error: Throwable) { observerFailure(error) }
    }
}

private class DecoderTexture {
    var acquisitionUnconfirmed = false
    var oes = 0
    var texture: SurfaceTexture? = null
    var surface: Surface? = null
}
private class LatchedPhoneTexture(val matrix: FloatArray, val timestampNanos: Long)

/** Android calls only; every instance and partial acquisition remains confined to its original GL lane. */
private class RealPhoneGl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var program = 0
    private var initialized = false
    private var attempted = false
    private var acquisitionUnconfirmed = false

    fun initialize() {
        if (initialized) return
        check(!attempted) { "Original EGL initialization did not complete" }
        attempted = true
        acquisitionUnconfirmed = true
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        acquisitionUnconfirmed = false
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig failed" }
        config = configs[0] ?: error("No returned EGL config")
        acquisitionUnconfirmed = true
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        acquisitionUnconfirmed = false
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        acquisitionUnconfirmed = true
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        acquisitionUnconfirmed = false
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        restorePbuffer()
        program = makeProgram()
        initialized = true
    }
    fun prepareDecoder(resource: DecoderTexture, width: Int, height: Int, callbackHandler: Handler, onFrame: () -> Unit) {
        restorePbuffer()
        val names = IntArray(1)
        resource.acquisitionUnconfirmed = true
        try { GLES20.glGenTextures(1, names, 0); resource.acquisitionUnconfirmed = false }
        finally { resource.oes = names[0] }
        checked("glGenTextures")
        check(resource.oes != 0) { "No returned OES texture" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, resource.oes)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checked("configure OES texture")
        resource.acquisitionUnconfirmed = true
        val texture = SurfaceTexture(resource.oes).also { resource.texture = it; resource.acquisitionUnconfirmed = false }
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener({ onFrame() }, callbackHandler)
        resource.acquisitionUnconfirmed = true
        resource.surface = Surface(texture)
        resource.acquisitionUnconfirmed = false
    }
    fun createWindow(borrowed: Surface): EGLSurface {
        val result = EGL14.eglCreateWindowSurface(display, config, borrowed, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return result
    }
    fun restorePbuffer() {
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent original pbuffer failed" }
    }
    fun latch(resource: DecoderTexture): LatchedPhoneTexture {
        val texture = resource.texture ?: error("Original SurfaceTexture absent")
        texture.updateTexImage()
        val matrix = FloatArray(16)
        texture.getTransformMatrix(matrix)
        return LatchedPhoneTexture(matrix, texture.timestamp)
    }
    fun draw(window: EGLSurface, resource: DecoderTexture, mapping: PhoneFrameMapping, width: Int, height: Int) {
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent original window failed" }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinates = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val sampler = GLES20.glGetUniformLocation(program, "uTexture")
        val vertices = ByteBuffer.allocateDirect(mapping.vertexPositions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertices.put(mapping.vertexPositions).position(0)
        val uv = ByteBuffer.allocateDirect(mapping.textureCoordinates.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        uv.put(mapping.textureCoordinates).position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinates)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(coordinates, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mapping.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, resource.oes)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checked("draw original decoded texture")
    }
    fun swap(window: EGLSurface, timestampNanos: Long) {
        check(EGLExt.eglPresentationTimeANDROID(display, window, timestampNanos)) { "eglPresentationTimeANDROID failed" }
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers original window failed" }
    }
    fun finish() { GLES20.glFinish(); checked("glFinish") }
    fun destroyWindow(window: EGLSurface) {
        check(EGL14.eglDestroySurface(display, window)) { "eglDestroySurface original window failed" }
    }
    fun deleteTexture(texture: Int) { GLES20.glDeleteTextures(1, intArrayOf(texture), 0); checked("delete original OES texture") }
    fun close() {
        if (!attempted) return
        var first: Throwable? = null
        fun step(action: () -> Unit) { try { action() } catch (error: Throwable) { if (first == null) first = error } }
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
            step { restorePbuffer() }
            step { finish() }
            if (program != 0) step { GLES20.glDeleteProgram(program); checked("delete original program"); program = 0 }
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            step { check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) }
            if (pbuffer != EGL14.EGL_NO_SURFACE) step { check(EGL14.eglDestroySurface(display, pbuffer)); pbuffer = EGL14.EGL_NO_SURFACE }
            if (context != EGL14.EGL_NO_CONTEXT) step { check(EGL14.eglDestroyContext(display, context)); context = EGL14.EGL_NO_CONTEXT }
            step { check(EGL14.eglTerminate(display)); display = EGL14.EGL_NO_DISPLAY }
        }
        step { check(EGL14.eglReleaseThread()) { "eglReleaseThread failed" } }
        if (acquisitionUnconfirmed && first == null)
            first = IllegalStateException("Original EGL acquisition threw before its handle outcome was observed")
        first?.let { throw it }
    }
    private fun checked(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation GL error $error" }
    }
    private fun shader(kind: Int, source: String): Int {
        acquisitionUnconfirmed = true
        val shader = GLES20.glCreateShader(kind)
        acquisitionUnconfirmed = false
        try {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        } catch (error: Throwable) {
            try { GLES20.glDeleteShader(shader) } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }
    private fun makeProgram(): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uTextureMatrix; varying vec2 vTexCoord; void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=(uTextureMatrix*vec4(aTexCoord,0.0,1.0)).xy;}")
        var fragment = 0
        var result = 0
        var first: Throwable? = null
        try {
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uTexture; varying vec2 vTexCoord; void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}")
            acquisitionUnconfirmed = true
            result = GLES20.glCreateProgram()
            acquisitionUnconfirmed = false
            GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, fragment); GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(result) }
        } catch (error: Throwable) { first = error }
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (first == null) first = error else if (first !== error) first!!.addSuppressed(error)
            }
        }
        cleanup { GLES20.glDeleteShader(vertex) }
        if (fragment != 0) cleanup { GLES20.glDeleteShader(fragment) }
        if (first != null) {
            if (result != 0) cleanup { GLES20.glDeleteProgram(result) }
            throw first!!
        }
        return result
    }
}
