package com.code2hack.eyeprojector.directhost

/** Test coordination only; claiming a command says nothing about admission or app effects. */
internal class AnchorAdbPhoneCommands {
    private val claimed = mutableSetOf<String>()
    fun next(ids: List<String>): String? = ids.firstOrNull { it !in claimed }?.also { claimed.add(it) }
    val count get() = claimed.size
}

/** Exact optional normal-START observation configuration; configuring/closing calls no Start/Stop. */
internal class AnchorAdbPhoneTraceLease<T>(private val swap: (T?) -> T?) {
    var prior: T? = null; private set
    var configured: T? = null; private set
    var owned = false; private set
    var restored = false; private set
    fun configure(value: T) {
        check(!owned)
        prior = swap(value); configured = value; owned = true
    }
    fun close(): Boolean {
        if (!owned || restored) return true
        val replaced = swap(prior)
        restored = true
        return replaced == configured
    }
}

/** Exact controlled-package value/context accounting. No Android, preference fabrication or timers. */
internal class AnchorAdbPhoneAnchorLedger {
    data class Snapshot(val instanceId: String, val revision: Long, val encoded: String?, val mode: String?)
    var original: Snapshot? = null; private set
    var testValue: Snapshot? = null; private set
    var registrationWitness: String? = null; private set

    fun backup(value: Snapshot): Snapshot = original ?: value.also { original = it }

    fun accountRegisteredValue(value: Snapshot, witness: String): String {
        val prior = original ?: return "UNAVAILABLE_NO_BACKUP"
        if (value.instanceId != prior.instanceId || value.revision <= prior.revision || value.encoded == null)
            return "INCONCLUSIVE_NOT_A_NEW_REGISTERED_VALUE"
        if (testValue != null) return if (testValue == value && registrationWitness == witness) "ALREADY_ACCOUNTED"
            else "INCONCLUSIVE_TEST_VALUE_ALREADY_ACCOUNTED"
        testValue = value; registrationWitness = witness
        return "ACCOUNTED_HOST_ATTRIBUTED_REGISTRATION"
    }

    fun restore(retired: Boolean, current: Snapshot,
                compareAndRestore: ((Snapshot, Snapshot) -> Snapshot?)?): String {
        val prior = original ?: return "UNAVAILABLE_NO_BACKUP"
        if (!retired) return "INCONCLUSIVE_ORIGINAL_RETIREMENT_UNVERIFIED"
        if (current == prior) return "UNCHANGED_NO_WRITE"
        val expected = testValue ?: return "INCONCLUSIVE_NO_ACCOUNTED_TEST_VALUE"
        if (current != expected) return "INCONCLUSIVE_CONTROLLED_VALUE_REPLACED"
        val restore = compareAndRestore ?: return "UNAVAILABLE_ATOMIC_RESTORE_API"
        val restored = restore(expected, prior) ?: return "INCONCLUSIVE_COMPARE_OR_READBACK"
        return if (restored.instanceId == expected.instanceId && restored.revision > expected.revision &&
            restored.encoded == prior.encoded && restored.mode == prior.mode) "RESTORED_VALUE_REVISION_ADVANCED"
            else "INCONCLUSIVE_RESTORE_READBACK"
    }
}

/** Only an exact captured original source plus actual terminal observer metadata is a witness. */
internal data class AnchorAdbPhoneRetirement(val ownerEpoch: String, val projectionToken: String,
    val managedState: String, val traceEnabled: Boolean, val closing: Boolean, val terminated: Boolean,
    val originalObligations: Int?) {
    fun matches(owner: String, token: String): Boolean = owner.isNotBlank() && token.isNotBlank() &&
        ownerEpoch == owner && projectionToken == token &&
        managedState in setOf("OFF", "CLOSED", "CLOSED_AFTER_FAILURE", "CLOSED_NOT_CREATED") &&
        traceEnabled && closing && terminated && originalObligations == 0
}
