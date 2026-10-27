package com.code2hack.eyeprojector.directhost.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.code2hack.eyeprojector.protocol.CameraAccessUnit
import com.code2hack.eyeprojector.protocol.CameraEndReason
import com.code2hack.eyeprojector.protocol.CameraFact
import com.code2hack.eyeprojector.protocol.CameraStreamDescription
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Issued only by the original decoder's actual cleanup or its strict never-configured path. */
class PhoneDecoderUseStopped internal constructor(val original: PhoneDecoderEpoch,
    val codecReleaseReturned: Boolean, val noConfigureAttempted: Boolean)

data class PhoneDecodeSubmission(val fact: CameraFact, val failure: String? = null)
data class PhoneDecodeStopSubmission(val fact: CameraFact)
/** Render counts are releaseOutputBuffer(render=true) returns, not latches or captured images. */
data class PhoneDecoderObservation(val original: PhoneDecoderEpoch, val stage: String,
    val actualFormat: PhoneDecodedFormat? = null, val useStopped: PhoneDecoderUseStopped? = null,
    val failure: Throwable? = null, val enrolledAccessUnits: Long = 0, val queuedAccessUnits: Long = 0,
    val queuedBytes: Long = 0, val renderedOutputs: Long = 0, val rawPtsUs: Long? = null,
    val stopRequested: Boolean = false, val codecReleaseReturned: Boolean = false,
    val loopReturned: Boolean = false, val joined: Boolean = false,
    val configureAttempted: Boolean = false, val threadEntered: Boolean = false,
    val observerFailure: String? = null, val completionFailure: String? = null)
data class PhoneDecoderCloseObservation(val original: PhoneDecoderEpoch,
    val observation: PhoneDecoderObservation, val joinNotRequired: Boolean)

/**
 * One captured role/stream/format decoder. Construction has no thread or native side effects.
 * The media reader may wait for native input acceptance; stop closes that wait independently
 * of a blocked native codec call. No timeout, polling clock or guessed frame/capture fact is used.
 */
class PhoneCameraDecoder(private val original: PhoneDecoderEpoch,
    private val description: CameraStreamDescription, private val destination: OwnedPhoneDecoderSurface,
    private val framing: PhoneCameraFraming = PhoneCameraFraming(),
    private val observations: (PhoneDecoderObservation) -> Unit) {
    private class Input(val value: CameraAccessUnit, val bytes: ByteArray) {
        val done = CountDownLatch(1)
        private val resolved = AtomicBoolean()
        @Volatile var result = PhoneDecodeSubmission(CameraFact.PENDING)
        fun finish(value: PhoneDecodeSubmission) {
            if (resolved.compareAndSet(false, true)) { result = value; done.countDown() }
        }
    }
    private val gate = Any()
    private val inputs = ArrayDeque<Input>()
    private var inFlight: Input? = null
    private val available = ArrayDeque<Int>() // accessed only on the original codec lane
    private val launchDone = CountDownLatch(1)
    private var originalThread: Thread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var startRequested = false
    private var startReturned = false
    private var entered = false
    private var closing = false
    private var cleanupClaimed = false
    private var configureAttempted = false
    private var codecStarted = false
    private var releaseReturned = false
    private var stopped: PhoneDecoderUseStopped? = null
    private var loopReturned = false
    private var joined = false
    private var primary: Throwable? = null
    private var observerFailure: Throwable? = null
    private var completionFailure: Throwable? = null
    private var actualFormat: PhoneDecodedFormat? = null
    private var enrolled = 0L
    private var queued = 0L
    private var queuedBytes = 0L
    private var rendered = 0L
    private var rawPtsUs: Long? = null
    private var stage = "INERT"

    fun start(): PhoneDecodeSubmission {
        synchronized(gate) {
            if (closing) return PhoneDecodeSubmission(CameraFact.RETIRED)
            if (startRequested) return PhoneDecodeSubmission(CameraFact.STALE_OR_DUPLICATE)
            startRequested = true
        }
        var thread: Thread? = null
        try {
            val owned = object : HandlerThread("eyeprojector-phone-decoder") {
                override fun run() = runOwned { super.run() }
                override fun onLooperPrepared() = prepareOwnedLooper(Looper.myLooper() ?: error("Original decoder Looper is absent"))
            }.apply { isDaemon = false }
            thread = owned
            synchronized(gate) { originalThread = owned }
            owned.start()
            synchronized(gate) { startReturned = true }
        } catch (failure: Throwable) {
            recordFailure(failure)
            requestStop(original, CameraEndReason.LOCAL_RELEASE_FAILURE)
            // This launch is terminal; no future attempt can configure the borrowed Surface.
            val failedThread = thread
            if (failedThread == null || failedThread.state == Thread.State.NEW) {
                synchronized(gate) {
                    if (!entered && !configureAttempted && stopped == null)
                        stopped = PhoneDecoderUseStopped(original, false, true)
                }
                publish("START_FAILED")
            }
        } finally { launchDone.countDown() }
        return synchronized(gate) {
            if (primary == null) PhoneDecodeSubmission(CameraFact.PENDING)
            else PhoneDecodeSubmission(CameraFact.FAILED, primary.toString())
        }
    }

    fun queue(candidate: PhoneDecoderEpoch, accessUnit: CameraAccessUnit): PhoneDecodeSubmission {
        if (!phoneDecoderIdentityMatches(original, candidate, description.formatGeneration, accessUnit.formatGeneration))
            return PhoneDecodeSubmission(CameraFact.STALE_OR_DUPLICATE)
        synchronized(gate) {
            if (closing) return PhoneDecodeSubmission(CameraFact.RETIRED)
            if (!startRequested) return PhoneDecodeSubmission(CameraFact.REJECTED, "Decoder has not started")
        }
        val bytes = try { framing.inputBytes(description, accessUnit) }
        catch (failure: Throwable) {
            recordFailure(failure); requestStop(original, CameraEndReason.PROTOCOL_FAILURE)
            return PhoneDecodeSubmission(CameraFact.FAILED, failure.toString())
        }
        val input = Input(accessUnit, bytes)
        val lane: Handler?
        val onLane: Boolean
        synchronized(gate) {
            if (closing) return PhoneDecodeSubmission(CameraFact.RETIRED)
            if (!startRequested) return PhoneDecodeSubmission(CameraFact.REJECTED, "Decoder has not started")
            inputs.addLast(input); enrolled++
            lane = handler
            onLane = originalThread === Thread.currentThread()
        }
        if (lane != null) dispatch(lane) { guarded { pump() } }
        // An observation can reenter on the codec lane. It may enroll work, never self-wait.
        if (onLane) return PhoneDecodeSubmission(CameraFact.PENDING)
        try { input.done.await() }
        catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            requestStop(original, CameraEndReason.ORIGINAL_RETIRED)
            return PhoneDecodeSubmission(CameraFact.PENDING, failure.toString())
        }
        return input.result
    }

    fun requestStop(candidate: PhoneDecoderEpoch, @Suppress("UNUSED_PARAMETER") reason: CameraEndReason): PhoneDecodeStopSubmission {
        if (candidate !== original) return PhoneDecodeStopSubmission(CameraFact.STALE_OR_DUPLICATE)
        val fresh = stopAdmission()
        val lane: Handler?
        val neverStarted: Boolean
        synchronized(gate) {
            lane = handler
            neverStarted = !startRequested
            if (neverStarted && stopped == null) stopped = PhoneDecoderUseStopped(original, false, true)
        }
        if (neverStarted) { launchDone.countDown(); publish("NEVER_CONFIGURED") }
        else if (lane != null) dispatch(lane) { cleanupOnLane() }
        return PhoneDecodeStopSubmission(if (fresh) CameraFact.PENDING else CameraFact.STALE_OR_DUPLICATE)
    }

    private fun stopAdmission(): Boolean {
        val pending: List<Input>
        val fresh: Boolean
        synchronized(gate) {
            fresh = !closing; closing = true
            pending = inputs.toList() + listOfNotNull(inFlight); inputs.clear()
        }
        pending.forEach { it.finish(PhoneDecodeSubmission(CameraFact.RETIRED)) }
        return fresh
    }

    private fun prepareOwnedLooper(looper: Looper) {
        val lane = Handler(looper)
        synchronized(gate) { handler = lane }
        guarded { initializeOnLane(lane) }
    }

    private fun runOwned(loop: () -> Unit) {
        synchronized(gate) {
            if (Thread.currentThread() !== originalThread) return
            entered = true
        }
        try {
            loop()
        } catch (failure: Throwable) { recordFailure(failure) }
        finally {
            stopAdmission()
            cleanupOnLane()
            synchronized(gate) { loopReturned = true }
            publish("LOOP_RETURNED")
        }
    }

    private fun initializeOnLane(lane: Handler) {
        if (synchronized(gate) { closing }) { cleanupOnLane(); return }
        val surface = destination.surfaceFor(original) ?: error("Original decoder output Surface is unavailable")
        // The remote actualCodecName identifies its encoder, not a compatible local decoder name.
        val created = MediaCodec.createDecoderByType(description.mime)
        synchronized(gate) { codec = created }
        created.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(callbackCodec: MediaCodec, index: Int) = guarded {
                if (!active(callbackCodec)) return@guarded
                available.addLast(index); pump()
            }
            override fun onOutputBufferAvailable(callbackCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = guarded {
                if (!active(callbackCodec)) return@guarded
                val render = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                if (render) {
                    // Schedule on the Phone clock; the remote encoder PTS is not joined to it.
                    val localRenderTimeNs = System.nanoTime()
                    callbackCodec.releaseOutputBuffer(index, localRenderTimeNs)
                } else callbackCodec.releaseOutputBuffer(index, false)
                synchronized(gate) { if (render) rendered++; rawPtsUs = info.presentationTimeUs }
                publish(if (render) "OUTPUT_RENDER_RETURNED" else "OUTPUT_RELEASE_RETURNED")
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    stopAdmission(); cleanupOnLane()
                }
            }
            override fun onOutputFormatChanged(callbackCodec: MediaCodec, format: MediaFormat) = guarded {
                if (!active(callbackCodec)) return@guarded
                val actual = framing.fromOutputFormat(format)
                synchronized(gate) { actualFormat = actual }
                publish("OUTPUT_FORMAT")
            }
            override fun onError(callbackCodec: MediaCodec, failure: MediaCodec.CodecException) = guarded {
                if (synchronized(gate) { callbackCodec !== codec || cleanupClaimed }) return@guarded
                throw failure
            }
        }, lane)
        if (synchronized(gate) { closing }) { cleanupOnLane(); return }
        val format = framing.decodeFormat(description)
        synchronized(gate) { configureAttempted = true }
        created.configure(format, surface, null, 0)
        publish("CONFIGURE_RETURNED")
        if (synchronized(gate) { closing }) { cleanupOnLane(); return }
        created.start()
        synchronized(gate) { codecStarted = true }
        publish("START_RETURNED")
        pump()
    }

    private fun active(candidate: MediaCodec): Boolean = synchronized(gate) {
        candidate === codec && !closing && !cleanupClaimed
    }

    private fun pump() {
        while (available.isNotEmpty()) {
            val input = synchronized(gate) {
                if (closing || inputs.isEmpty()) null else inputs.removeFirst().also { inFlight = it }
            } ?: return
            val index = available.removeFirst()
            try {
                val current = synchronized(gate) { codec } ?: error("Missing original codec")
                val buffer = current.getInputBuffer(index) ?: error("Missing original decoder input buffer")
                val count = writePhoneDecoderInput(buffer, input.bytes)
                current.queueInputBuffer(index, 0, count, input.value.ptsUs, input.value.codecFlags)
                synchronized(gate) { queued++; queuedBytes += count.toLong(); rawPtsUs = input.value.ptsUs }
                input.finish(PhoneDecodeSubmission(CameraFact.OBSERVED_RETURN))
                publish("INPUT_QUEUE_RETURNED")
            } catch (failure: Throwable) {
                input.finish(PhoneDecodeSubmission(CameraFact.FAILED, failure.toString()))
                throw failure
            } finally { synchronized(gate) { if (inFlight === input) inFlight = null } }
        }
    }

    private fun guarded(action: () -> Unit) {
        if (synchronized(gate) { Thread.currentThread() !== originalThread }) {
            dispatchFailed()
            return
        }
        try { action() }
        catch (failure: Throwable) { recordFailure(failure); stopAdmission(); cleanupOnLane() }
    }

    private fun cleanupOnLane() {
        val current: MediaCodec?
        val shouldStop: Boolean
        synchronized(gate) {
            if (cleanupClaimed) return
            cleanupClaimed = true
            current = codec; shouldStop = codecStarted
        }
        stopAdmission()
        if (current != null) {
            if (shouldStop) try { current.stop() } catch (failure: Throwable) { recordFailure(failure) }
            try {
                current.release()
                synchronized(gate) { releaseReturned = true }
            } catch (failure: Throwable) { recordFailure(failure) }
        }
        synchronized(gate) {
            if (stopped == null && (releaseReturned || !configureAttempted))
                stopped = PhoneDecoderUseStopped(original, releaseReturned, !configureAttempted)
        }
        // Only the observation port forwards this exact fact to the fanout. No duplicate callback.
        publish(if (synchronized(gate) { stopped != null }) "CODEC_USE_STOPPED" else "CODEC_RELEASE_PENDING")
        try { synchronized(gate) { handler }?.looper?.quitSafely() }
        catch (failure: Throwable) { recordFailure(failure) }
    }

    private fun dispatchFailed() {
        recordFailure(IllegalStateException("Original decoder Handler rejected dispatch"))
        stopAdmission()
        // No native teardown or use fence is fabricated on the submitting thread.
        publish("DISPATCH_FAILED")
    }

    private fun dispatch(lane: Handler, action: () -> Unit) {
        try { if (!lane.post { action() }) dispatchFailed() }
        catch (failure: Throwable) {
            recordFailure(failure); stopAdmission(); publish("DISPATCH_FAILED")
        }
    }

    private fun recordFailure(failure: Throwable) = synchronized(gate) {
        if (primary == null) primary = failure else if (primary !== failure) primary!!.addSuppressed(failure)
    }

    private fun snapshotLocked() = PhoneDecoderObservation(original, stage, actualFormat, stopped,
        primary, enrolled, queued, queuedBytes, rendered, rawPtsUs, closing, releaseReturned,
        loopReturned, joined, configureAttempted, entered, observerFailure?.toString(), completionFailure?.toString())
    fun snapshot(): PhoneDecoderObservation = synchronized(gate) { snapshotLocked() }
    private fun publish(value: String) {
        val observation = synchronized(gate) { stage = value; snapshotLocked() }
        try { observations(observation) }
        catch (failure: Throwable) { synchronized(gate) { if (observerFailure == null) observerFailure = failure } }
    }

    fun closeAsync(candidate: PhoneDecoderEpoch, executor: Executor, completion: (PhoneDecoderCloseObservation) -> Unit) {
        if (candidate !== original) return
        requestStop(original, CameraEndReason.ORIGINAL_RETIRED)
        try { Thread({
            try {
                launchDone.await()
                val thread = synchronized(gate) { originalThread }
                val actualStarted = thread != null && synchronized(gate) { startReturned || entered || thread.state != Thread.State.NEW }
                if (actualStarted) {
                    thread!!.join()
                    synchronized(gate) { joined = true }
                    publish("WORKER_JOINED")
                }
                val value = PhoneDecoderCloseObservation(original, snapshot(), !actualStarted)
                val once = AtomicBoolean()
                try { executor.execute {
                    if (once.compareAndSet(false, true)) try { completion(value) }
                    catch (failure: Throwable) { synchronized(gate) { if (completionFailure == null) completionFailure = failure } }
                } } catch (failure: Throwable) {
                    synchronized(gate) { if (completionFailure == null) completionFailure = failure }
                    publish("COMPLETION_REJECTED")
                }
            } catch (failure: Throwable) {
                synchronized(gate) { if (completionFailure == null) completionFailure = failure }
                publish("JOIN_PENDING")
            }
        }, "eyeprojector-phone-decoder-join").apply { isDaemon = true }.start() }
        catch (failure: Throwable) {
            synchronized(gate) { if (completionFailure == null) completionFailure = failure }
            publish("JOIN_START_FAILED")
        }
    }
}
