package com.code2hack.eyeprojector.directhost.anchor.execution

/** Original operation disposition, guarded by the Session's existing control lock. */
internal enum class AnchorControlDisposition { NORMAL, CONSUMED, RETIRED }

/** This records input consumption; AnchorContactOwner remains the sole contact owner. */
internal class AnchorControlAdmission {
    internal var owner: Any? = null
    internal var normalSequence: Long? = null
    var disposition = AnchorControlDisposition.NORMAL
        internal set

    val terminal: Boolean get() = disposition != AnchorControlDisposition.NORMAL

    internal fun current(originalOwner: Any, sequence: Long): Boolean = !terminal &&
        (owner == null || owner === originalOwner) && (normalSequence == null || normalSequence == sequence)

    /** Existing grammar membership, not timestamps, associates a second Back/Home press. */
    internal fun inherit(original: AnchorControlAdmission) {
        if (original.terminal) {
            owner = original.owner
            normalSequence = original.normalSequence
            disposition = original.disposition
        }
    }
}
