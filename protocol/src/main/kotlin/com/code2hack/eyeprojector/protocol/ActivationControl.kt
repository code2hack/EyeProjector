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
/** A wake revision on the authenticated retained stream. No HID edge or local token crosses it. */
object StreamWake {
    const val MIN_BYTES = 15
    const val MAX_BYTES = 397

    fun encode(directive: ActivationDirective): ByteArray = bytes {
        require(directive.desired && directive.wakeRevision > 0)
        require(directive.activationId.length in 1..64 && directive.ownerEpoch.length in 1..64)
        writeUTF(directive.activationId)
        writeUTF(directive.ownerEpoch)
        writeLong(directive.wakeRevision)
        writeByte(directive.goal.ordinal)
    }

    fun decode(payload: ByteArray): ActivationDirective {
        require(payload.size in MIN_BYTES..MAX_BYTES)
        val input = java.io.DataInputStream(payload.inputStream())
        val activationId = input.readUTF()
        val ownerEpoch = input.readUTF()
        val revision = input.readLong()
        val goal = ActivationGoal.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown wake goal")
        require(input.available() == 0 && activationId.length in 1..64 && ownerEpoch.length in 1..64 && revision > 0)
        return ActivationDirective(activationId, true, revision, ownerEpoch, goal)
    }
}

/** Captured Receiver-side request identity; local-only Intent data, never a host projection token. */
data class ReceiverDemand(val activationId: String, val ownerEpoch: String, val wakeRevision: Long,
    val incarnation: ReceiverIncarnation, val goal: ActivationGoal)
data class ActivationReply(val directive: ActivationDirective? = null, val trigger: TriggerReceipt? = null, val cancelAcknowledged: String? = null)
