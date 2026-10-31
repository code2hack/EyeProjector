package com.code2hack.eyeprojector.protocol

/** Declared ABI only, receipt f74ce6c19bf2839a311386e0727e1323197b267c94ff8aa812d1c3bae98a0bfb.
 * Used only with --modeled-shared-api before the reviewed #122 source handoff. No codec behavior.
 */
enum class AnchorChoice { CLICK, HOLD, CANCEL }
enum class AnchorRegistrationResult { SAVED_CLICK, SAVED_HOLD, CANCELED, UNAVAILABLE }
data class AnchorHighlight(val left: Double, val top: Double, val right: Double, val bottom: Double)
sealed interface AnchorMessage {
    val transactionId: Long
    val intent: FunctionIntent
    data class Offer(override val transactionId: Long, override val intent: FunctionIntent,
        val highlight: AnchorHighlight) : AnchorMessage
    data class Result(override val transactionId: Long, override val intent: FunctionIntent,
        val result: AnchorRegistrationResult) : AnchorMessage
    data class Open(override val transactionId: Long, override val intent: FunctionIntent,
        val chooser: ReceiverUsability) : AnchorMessage
    data class Choice(override val transactionId: Long, override val intent: FunctionIntent,
        val choice: AnchorChoice) : AnchorMessage
}
