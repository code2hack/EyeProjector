package com.code2hack.eyeprojector.directhost.notifications

/** Passive, per-original-receipt facts only. None of these values authorize product work. */
internal enum class NotificationAcceptDisposition {
    NOT_OBSERVED, RUNTIME_SCOPE_CHANGED, REMOVED_CURRENT, FILTERED_KIND, OLD_RECEIPT,
    MISSING_TICKET, PREPARE_UNAVAILABLE, FACADE_NULL, FACADE_HANDLE, THREW,
}
internal enum class NotificationPrepareDisposition { NOT_OBSERVED, RETURNED, READ_EXCEPTION }
internal enum class NotificationInstallDisposition { NOT_OBSERVED, REJECTED, INSTALLED }
internal enum class NotificationAdmissionCheck { PACKAGE, PACKAGE_REVISION, CHOICE_REVISION, ACCESS_REVISION, APPROVAL, CHOSEN, ON }
internal enum class NotificationCheckResult { NOT_EVALUATED, MATCHED, REJECTED }
internal enum class NotificationDiagnosticFaultFamily { NONE, SECURITY, ARGUMENT, STATE, INTERRUPTED, LINKAGE, CAST, OTHER }
internal enum class NotificationHelperFaultPoint { NONE, BEFORE_APPEND, APPEND_UNCONFIRMED }

internal fun notificationDiagnosticFault(error: Throwable): NotificationDiagnosticFaultFamily = when (error) {
    is SecurityException -> NotificationDiagnosticFaultFamily.SECURITY
    is ClassCastException -> NotificationDiagnosticFaultFamily.CAST
    is LinkageError -> NotificationDiagnosticFaultFamily.LINKAGE
    is IllegalArgumentException -> NotificationDiagnosticFaultFamily.ARGUMENT
    is IllegalStateException -> NotificationDiagnosticFaultFamily.STATE
    is InterruptedException -> NotificationDiagnosticFaultFamily.INTERRUPTED
    else -> NotificationDiagnosticFaultFamily.OTHER
}

/** Immutable copy made outside policy/fast locks, not a live authority lookup. */
internal class NotificationDeliverySnapshot(
    val captured: Long, val enqueueAttempted: Long, val enqueueReturned: Long, val enqueueFaults: Long,
    val callbackEntered: Long, val callbackReturned: Long, val callbackFaults: Long, val inactiveSkipped: Long,
    val helperEntered: Long, val helperAppended: Long, val helperFaults: Long,
    val enqueueFault: NotificationDiagnosticFaultFamily, val callbackFault: NotificationDiagnosticFaultFamily,
    val helperFault: NotificationDiagnosticFaultFamily, val helperFaultPoint: NotificationHelperFaultPoint,
) {
    override fun toString() = "NotificationDeliverySnapshot(metadata omitted)"
}

internal class NotificationObservationSnapshot(
    val original: Any, val disposition: NotificationAcceptDisposition,
    val prepare: NotificationPrepareDisposition, val install: NotificationInstallDisposition,
    val prepareFault: NotificationDiagnosticFaultFamily, val acceptFault: NotificationDiagnosticFaultFamily,
    val checks: List<NotificationCheckResult>, val installedSelection: Any?, val returnedHandle: Any?,
    val delivery: NotificationDeliverySnapshot,
) {
    override fun toString() = "NotificationObservationSnapshot(metadata omitted)"
}

/**
 * Optional diagnostic storage on the existing original receipt/ticket. Writers follow the existing
 * main-owner path; install fields are assigned during that same synchronous policy-gate admission.
 * No observer, callback, lock, queue, I/O or authority read is introduced. The helper copies fields
 * on main after admission/dispatch, outside policy/fast locks. Closed registration does not replay.
 */
internal class NotificationObservationTrace {
    var original: Any? = null
    var disposition = NotificationAcceptDisposition.NOT_OBSERVED
    var prepare = NotificationPrepareDisposition.NOT_OBSERVED
    var install = NotificationInstallDisposition.NOT_OBSERVED
    var prepareFault = NotificationDiagnosticFaultFamily.NONE
    var acceptFault = NotificationDiagnosticFaultFamily.NONE
    var packageCheck = NotificationCheckResult.NOT_EVALUATED
    var packageRevision = NotificationCheckResult.NOT_EVALUATED
    var choiceRevision = NotificationCheckResult.NOT_EVALUATED
    var accessRevision = NotificationCheckResult.NOT_EVALUATED
    var approval = NotificationCheckResult.NOT_EVALUATED
    var chosen = NotificationCheckResult.NOT_EVALUATED
    var enabled = NotificationCheckResult.NOT_EVALUATED
    var installedSelection: Any? = null
    var returnedHandle: Any? = null
    var captured = 0L
    var enqueueAttempted = 0L
    var enqueueReturned = 0L
    var enqueueFaults = 0L
    var callbackEntered = 0L
    var callbackReturned = 0L
    var callbackFaults = 0L
    var inactiveSkipped = 0L
    var helperEntered = 0L
    var helperAppended = 0L
    var helperFaults = 0L
    var enqueueFault = NotificationDiagnosticFaultFamily.NONE
    var callbackFault = NotificationDiagnosticFaultFamily.NONE
    var helperFault = NotificationDiagnosticFaultFamily.NONE
    var helperFaultPoint = NotificationHelperFaultPoint.NONE

    fun snapshot(expectedOriginal: Any): NotificationObservationSnapshot? {
        if (original !== expectedOriginal) return null // Correlation only; never an admission gate.
        return NotificationObservationSnapshot(expectedOriginal, disposition, prepare, install,
            prepareFault, acceptFault,
            listOf(packageCheck, packageRevision, choiceRevision, accessRevision, approval, chosen, enabled),
            installedSelection, returnedHandle,
            NotificationDeliverySnapshot(captured, enqueueAttempted, enqueueReturned, enqueueFaults,
                callbackEntered, callbackReturned, callbackFaults, inactiveSkipped,
                helperEntered, helperAppended, helperFaults, enqueueFault, callbackFault, helperFault, helperFaultPoint))
    }

    override fun toString() = "NotificationObservationTrace(metadata omitted)"
}
