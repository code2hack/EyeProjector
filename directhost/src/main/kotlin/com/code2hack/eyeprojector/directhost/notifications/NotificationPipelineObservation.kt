package com.code2hack.eyeprojector.directhost.notifications

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.activation.ActivationHandle
import com.code2hack.eyeprojector.directhost.activation.ActivationReady
import com.code2hack.eyeprojector.directhost.activation.LaunchCommitPermit

internal enum class NotificationPipelineStage {
    DEMAND_ADMITTED, READY_OBSERVED, SUBMISSION_STARTED, SETTLED, OWNER_DETACHED,
}

/**
 * Captured original work, never current-authority lookup or a new admission capability.
 * Identity references are for in-memory correlation only. A SETTLED event requires both
 * original submission invocation return and its actual matching lane-settled receipt.
 * COMMITTED then describes the typed final claim; foreground/null/fault remain separate.
 */
internal class NotificationPipelineObservation(
    val stage: NotificationPipelineStage,
    val ingress: NotificationIngressObservation,
    val selection: Any,
    val demand: ActivationHandle,
    val arm: Any? = null,
    val ready: ActivationReady<ProjectionContext>? = null,
    val carrier: NotificationLaunchCarrier? = null,
    val outcome: NotificationDispatchOutcome? = null,
    val permitState: LaunchCommitPermit.State,
) {
    override fun toString() = "NotificationPipelineObservation(stage=$stage; metadata omitted)"
}
