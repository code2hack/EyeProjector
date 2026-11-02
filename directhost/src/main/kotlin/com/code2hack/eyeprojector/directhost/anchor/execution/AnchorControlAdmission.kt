package com.code2hack.eyeprojector.directhost.anchor.execution

/** Original operation disposition, guarded by the Session's existing control lock. */
internal enum class AnchorControlDisposition { NORMAL, CONSUMED, RETIRED }

/** This records input consumption; AnchorContactOwner remains the sole contact owner. */
internal class AnchorControlAdmission {
    internal var owner: Any? = null
    internal var normalSequence: Long? = null
    internal var receivedSequence: Long? = null
    internal var capturedHold = false
    internal var anchorGeneration: Long? = null
    internal var receivedDuringRetirement = false
    internal var sourceBinding: Pair<java.util.concurrent.atomic.AtomicReference<*>, Any?>? = null
    internal var sourceRevision: Pair<java.util.concurrent.atomic.AtomicLong, Long>? = null
    internal var anchorContext: com.code2hack.eyeprojector.protocol.AnchorIntentContext? = null
    internal var anchorMapping: com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiMapping? = null
    internal var anchorResource: Any? = null
    @Volatile var disposition = AnchorControlDisposition.NORMAL
        internal set

    val terminal: Boolean get() = disposition != AnchorControlDisposition.NORMAL

    internal fun current(originalOwner: Any, sequence: Long): Boolean = !terminal &&
        (owner == null || owner === originalOwner) && (normalSequence == null || normalSequence == sequence) && currentSource()

    /** Original app binding/shell lifetime only; no platform or latest-target lookup. */
    private fun currentSource(): Boolean {
        val binding = sourceBinding
        val revision = sourceRevision
        return (binding == null || binding.first.get() === binding.second) &&
            (revision == null || revision.first.get() == revision.second)
    }

    /** Anchor registration/ordinary uses birth lifetime; Short deliberately retains its authoritative FIFO mode. */
    internal fun ordinary() { if (normalSequence == null) normalSequence = receivedSequence }

    /** Existing grammar membership, not timestamps, associates a second Back/Home press. */
    internal fun inherit(original: AnchorControlAdmission) {
        if (original.terminal) {
            owner = original.owner
            normalSequence = original.normalSequence
            receivedDuringRetirement = original.receivedDuringRetirement
            sourceBinding = original.sourceBinding; sourceRevision = original.sourceRevision
            disposition = original.disposition
        }
    }

    /** A summary-added grammar member retains that sequence's original receipt, not latest Hold. */
    internal fun inheritLifetime(original: AnchorControlAdmission) {
        if (terminal) return
        owner=original.owner;normalSequence=original.normalSequence;receivedSequence=original.receivedSequence;capturedHold=original.capturedHold
        anchorGeneration=original.anchorGeneration;receivedDuringRetirement=original.receivedDuringRetirement
        sourceBinding=original.sourceBinding;sourceRevision=original.sourceRevision
        anchorContext=original.anchorContext;anchorMapping=original.anchorMapping;anchorResource=original.anchorResource
        disposition=original.disposition
    }
}
