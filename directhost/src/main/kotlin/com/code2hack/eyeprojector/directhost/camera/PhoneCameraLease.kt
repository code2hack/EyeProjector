package com.code2hack.eyeprojector.directhost.camera

import android.view.Surface
import java.util.IdentityHashMap

/** A local writer identity, never a stream ID, app UID or equal-valued session tuple. */
public class PhoneInputProducerIdentity

/** The exact writer declares its use ended; this is not GL/codec cleanup or a thread join. */
public class PhoneInputUseStopped(
    public val input: PhoneCameraLease,
    public val producer: PhoneInputProducerIdentity,
)

public data class PhoneInputSnapshot(
    val input: PhoneCameraLease,
    val closeRequested: Boolean,
    val surfaceReleased: Boolean,
    val resourcesClear: Boolean,
    val aliasAmbiguous: Boolean,
    val surfaceAbsent: Boolean,
    val producerBound: Boolean,
    val producerUseStopped: Boolean,
    val releaseFailure: Throwable?,
    val worker: PhoneWorkerSnapshot,
    val stopRequested: Boolean,
    val stopCallReturned: Boolean,
    val stopFailure: Throwable?,
    val dispatchFailure: Throwable?,
    val surfaceReleaseAttempted: Boolean,
    val aliasAfterRelease: Boolean,
    val releaseClaimedAtArrival: Boolean,
    val unboundAdmissionClosed: Boolean,
)

/**
 * One original callback input. The constructor is called while ownership.lock is held;
 * the supplied Surface identity map and every stored owner use that SAME ownership.
 * Only a bound original producer can borrow this wrapper, and it remains bound after its fence.
 */
public class PhoneCameraLease internal constructor(
    public val registration: OwnedPhoneRegistration,
    private val callbackIdentity: Any,
    public val streamId: Int,
    public val width: Int,
    public val height: Int,
    public val pixelFormat: Int,
    surface: Surface?,
    private val ownership: PhoneCameraOwnership,
    surfaces: IdentityHashMap<Surface, PhoneSurfaceOwner>,
    public val observedAtNanos: Long,
) {
    private val physical: PhoneSurfaceOwner
    private val aliasAfterRelease: Boolean
    private val releaseClaimedAtArrival: Boolean
    private var closing = false
    private var producer: PhoneInputProducerIdentity? = null
    private var stop: (() -> Unit)? = null
    private var useStopped = false
    private var stopClaimed = false
    private var stopReturned = false
    private var stopFailure: Throwable? = null

    init {
        val previous = if (surface == null) null else surfaces[surface]
        physical = previous ?: PhoneSurfaceOwner(surface, ownership).also {
            if (surface != null) surfaces[surface] = it
        }
        aliasAfterRelease = physical.releaseReturned
        releaseClaimedAtArrival = physical.releaseClaimed
        physical.enrollLocked(this)
    }

    /** Exclusive original claim only; no Surface/native work or producer callback occurs here. */
    public fun bindProducer(original: PhoneInputProducerIdentity, requestStop: () -> Unit): Boolean =
        synchronized(ownership.lock) {
            if (closing || producer != null || physical.surface == null || physical.aliasAmbiguous ||
                physical.releaseClaimed) return@synchronized false
            producer = original
            stop = requestStop
            ownership.changedLocked()
            true
        }

    public fun surfaceFor(original: PhoneInputProducerIdentity): Surface? = synchronized(ownership.lock) {
        if (producer !== original || closing || useStopped || physical.aliasAmbiguous || physical.releaseClaimed)
            null else physical.surface
    }

    public fun producerStopped(original: PhoneInputProducerIdentity, fact: PhoneInputUseStopped): Boolean {
        val schedule = synchronized(ownership.lock) {
            if (producer !== original || fact.input !== this || fact.producer !== original) return false
            if (useStopped) return true
            useStopped = true
            ownership.changedLocked()
            closing
        }
        if (schedule) physical.schedule()
        return true
    }

    /** Submission only. In particular no producer port or Surface.release runs on this caller. */
    public fun requestClose() {
        val first = synchronized(ownership.lock) {
            if (closing) false else { closing = true; ownership.changedLocked(); true }
        }
        if (first) physical.schedule()
    }

    public fun snapshot(): PhoneInputSnapshot = synchronized(ownership.lock) { snapshotLocked() }

    internal fun snapshotLocked(): PhoneInputSnapshot {
        val absent = physical.surface == null
        val overlappedRelease = aliasAfterRelease || releaseClaimedAtArrival
        val releasedForThisInput = physical.releaseReturned && !overlappedRelease
        val safe = producer == null || useStopped
        return PhoneInputSnapshot(this, closing, releasedForThisInput,
            closing && safe && !overlappedRelease && (absent || releasedForThisInput),
            physical.aliasAmbiguous, absent, producer != null, useStopped,
            physical.releaseFailure, physical.worker.snapshot(), stopClaimed, stopReturned,
            stopFailure, physical.dispatchFailure, physical.releaseClaimed,
            aliasAfterRelease, releaseClaimedAtArrival, closing && producer == null)
    }

    internal fun closeAndUseSafeLocked(): Boolean = closing && (producer == null || useStopped)

    internal fun claimStopLocked(): (() -> Unit)? {
        if (!closing || producer == null || useStopped || stopClaimed) return null
        stopClaimed = true
        ownership.changedLocked()
        return stop
    }

    internal fun stopOutcomeLocked(error: Throwable?) {
        if (error == null) stopReturned = true
        else if (stopFailure == null) stopFailure = error
        ownership.changedLocked()
    }
}

/** One physical wrapper release owner, even when native callbacks alias the same Surface object. */
internal class PhoneSurfaceOwner(
    val surface: Surface?,
    private val ownership: PhoneCameraOwnership,
) {
    private val inputs = ArrayList<PhoneCameraLease>()
    var aliasAmbiguous = false
        private set
    var releaseClaimed = false
        private set
    var releaseReturned = false
        private set
    var releaseFailure: Throwable? = null
        private set
    var dispatchFailure: Throwable? = null
        private set
    private var drainPosted = false
    val worker = PhoneCameraWorker("phone-input-retire-${System.identityHashCode(this)}", ownership)

    fun enrollLocked(input: PhoneCameraLease) {
        if (inputs.isNotEmpty()) aliasAmbiguous = true
        inputs.add(input)
        ownership.changedLocked()
    }

    /** Independent of the original registration's possibly blocked Binder/native lane. */
    fun schedule() {
        val submit = synchronized(ownership.lock) {
            if (drainPosted) false else { drainPosted = true; ownership.changedLocked(); true }
        }
        if (!submit) return
        if (!worker.post { drain() }) synchronized(ownership.lock) {
            drainPosted = false
            if (dispatchFailure == null)
                dispatchFailure = IllegalStateException("Original input retirement lane no longer admits work")
            ownership.changedLocked()
        }
    }

    private fun drain() {
        val stops = synchronized(ownership.lock) {
            drainPosted = false
            ownership.changedLocked()
            inputs.mapNotNull { input -> input.claimStopLocked()?.let { input to it } }
        }
        var firstFailure: Throwable? = null
        for ((input, originalStop) in stops) {
            val error = try { originalStop(); null } catch (failure: Throwable) { failure }
            synchronized(ownership.lock) { input.stopOutcomeLocked(error) }
            if (firstFailure == null) firstFailure = error
        }
        val release = synchronized(ownership.lock) {
            if (surface != null && !releaseClaimed && inputs.all { it.closeAndUseSafeLocked() }) {
                releaseClaimed = true
                ownership.changedLocked()
                true
            } else false
        }
        if (release) {
            val error = try { surface!!.release(); null } catch (failure: Throwable) { failure }
            synchronized(ownership.lock) {
                if (error == null) releaseReturned = true
                else if (releaseFailure == null) releaseFailure = error
                ownership.changedLocked()
            }
            if (firstFailure == null) firstFailure = error
        }
        val finished = synchronized(ownership.lock) {
            inputs.all { it.closeAndUseSafeLocked() } && (surface == null || releaseClaimed)
        }
        if (finished) worker.closeWhenIdle()
        firstFailure?.let { throw it } // Lane preserves this first fault and still drains owned cleanup.
    }
}
