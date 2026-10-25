package com.code2hack.eyeprojector.protocol

enum class ActivationGoal { USABLE, PROJECTED }

/** Feature identities never replace backend epochs or the existing attempt cancellation ledger. */
data class ActivationIntent(val triggerId: String)
data class CancelTrigger(val triggerId: String, val admissionId: String)
data class CancelActivation(val activationId: String)
data class ReceiverIncarnation(val id: String, val sequence: Long)
data class PresentationReport(
    val activationId: String, val ownerEpoch: String, val wakeRevision: Long,
    val incarnation: ReceiverIncarnation, val revision: Long, val mode: ReceiverUsabilityMode,
    val detail: String = "",
) { val presented: Boolean get() = mode != ReceiverUsabilityMode.NONE }
enum class TriggerDisposition { MAPPED, CANCELED }
data class TriggerReceipt(val triggerId: String, val activationId: String?, val disposition: TriggerDisposition)
data class ActivationDirective(
    val activationId: String, val desired: Boolean, val wakeRevision: Long, val ownerEpoch: String,
    val goal: ActivationGoal = ActivationGoal.USABLE,
)
/** Captured Receiver-side request identity; local-only Intent data, never a host projection token. */
data class ReceiverDemand(val activationId: String, val ownerEpoch: String, val wakeRevision: Long,
    val incarnation: ReceiverIncarnation, val goal: ActivationGoal)
data class ActivationReply(val directive: ActivationDirective? = null, val trigger: TriggerReceipt? = null, val cancelAcknowledged: String? = null)
