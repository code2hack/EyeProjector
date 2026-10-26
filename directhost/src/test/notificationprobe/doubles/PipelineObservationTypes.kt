package com.code2hack.eyeprojector.directhost.notifications

import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.ProjectionDisplayState
import com.code2hack.eyeprojector.directhost.activation.*

/** Constructor-compatible transport tokens only. No selection/queue/claim algorithm is copied. */
internal class NotificationSelection(val observation: NotificationIngressObservation, val facade: PhoneSessionController)
class NotificationLaunchCarrier internal constructor(internal val selection: NotificationSelection,
    internal val ready: ActivationReady<ProjectionContext>, internal val submission: Any)
internal enum class NotificationCommitment { COMMITTED, NOT_COMMITTED, ADAPTER_FAULT }
internal enum class NotificationRejection { SELECTION_RETIRED, ACTIVATION_CHANGED }
internal enum class NotificationFaultStage { ENQUEUE, PRE_CALL, BINDER, DECODE, FEEDBACK, COORDINATOR }
class NotificationDispatchOutcome internal constructor(internal val commitment: NotificationCommitment,
    internal val result: ProjectionDisplayState? = null, internal val rejection: NotificationRejection? = null,
    internal val faultStage: NotificationFaultStage? = null, internal val faultCode: String? = null)
internal enum class NotificationPipelineStage { DEMAND_ADMITTED, READY_OBSERVED, SUBMISSION_STARTED, SETTLED, OWNER_DETACHED }
internal open class NotificationPipelineObservation(val stage: NotificationPipelineStage,
    open val ingress: NotificationIngressObservation, val selection: Any, val demand: ActivationHandle, val arm: Any? = null,
    open val ready: ActivationReady<ProjectionContext>? = null, val carrier: NotificationLaunchCarrier? = null,
    val outcome: NotificationDispatchOutcome? = null, val permitState: LaunchCommitPermit.State)
