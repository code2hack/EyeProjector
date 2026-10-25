package com.code2hack.eyeprojector.directhost.notifications

import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.ProjectionDisplayState
import com.code2hack.eyeprojector.directhost.activation.*

internal class NotificationSelection(val observation: NotificationIngressObservation, val facade: PhoneSessionController) {
    val packageName = observation.event.record.packageName
    val permit = LaunchCommitPermit() // Exactly one per G, across every original-D binding/rearm.
    var handle: ActivationHandle? = null
}

/** Public opaque transport; activation identities stay module-private and immutable per S. */
class NotificationLaunchCarrier internal constructor(
    internal val selection: NotificationSelection,
    internal val ready: ActivationReady<ProjectionContext>,
    internal val submission: Any,
) {
    internal val packageName = selection.packageName
    internal val facade = selection.facade
    internal val permit = selection.permit
}

internal enum class NotificationCommitment { COMMITTED, NOT_COMMITTED, ADAPTER_FAULT }
internal enum class NotificationRejection { SELECTION_RETIRED, ACTIVATION_CHANGED }
internal enum class NotificationFaultStage { ENQUEUE, PRE_CALL, BINDER, DECODE, FEEDBACK, COORDINATOR }

/** A null display result is never the commitment decision. No raw exception/message payload. */
class NotificationDispatchOutcome internal constructor(
    internal val commitment: NotificationCommitment,
    internal val result: ProjectionDisplayState? = null,
    internal val rejection: NotificationRejection? = null,
    internal val faultStage: NotificationFaultStage? = null,
    internal val faultCode: String? = null,
) {
    internal fun feedbackFailure() = if (faultStage != null) this else
        NotificationDispatchOutcome(commitment, result, rejection, NotificationFaultStage.FEEDBACK, "CALLBACK")
    companion object {
        internal fun rejected(carrier: NotificationLaunchCarrier) = NotificationDispatchOutcome(NotificationCommitment.NOT_COMMITTED,
            rejection = if (carrier.permit.state == LaunchCommitPermit.State.RETIRED) NotificationRejection.SELECTION_RETIRED else NotificationRejection.ACTIVATION_CHANGED)
        internal fun fault(carrier: NotificationLaunchCarrier, stage: NotificationFaultStage, code: String) =
            NotificationDispatchOutcome(if (carrier.permit.state == LaunchCommitPermit.State.COMMITTED) NotificationCommitment.COMMITTED else NotificationCommitment.ADAPTER_FAULT,
                faultStage = stage, faultCode = code)
    }
}

/** Emitted only after the carrier's actual coordinator Work has relinquished its lane. */
class NotificationLaunchSettlement internal constructor(internal val carrier: NotificationLaunchCarrier, internal val outcome: NotificationDispatchOutcome)
