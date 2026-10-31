package com.code2hack.eyeprojector.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

enum class AnchorFeedback { CLICK_SUBMITTED, NO_ANCHOR, UNAVAILABLE, HOLD_MODE, RETIRED, INPUT_REJECTED,
    HOLD_SUBMITTED, RELEASE_SUBMITTED }
enum class AnchorHoldState { HELD, RELEASED, CANCELED }
enum class AnchorChoice { CLICK, HOLD, CANCEL }
enum class AnchorRegistrationResult { SAVED_CLICK, SAVED_HOLD, CANCELED, UNAVAILABLE }
data class AnchorHighlight(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() } && right > left && bottom > top) }
}

/** One original Session's transaction. Highlight uses VIDEO pixels; no UI text/node crosses wire. */
sealed class AnchorMessage(open val transactionId: Long, open val intent: FunctionIntent) {
    data class Feedback(override val transactionId: Long, override val intent: FunctionIntent,
        val status: AnchorFeedback) : AnchorMessage(transactionId, intent)
    data class Offer(override val transactionId: Long, override val intent: FunctionIntent,
        val highlight: AnchorHighlight) : AnchorMessage(transactionId, intent)
    data class Open(override val transactionId: Long, override val intent: FunctionIntent,
        val chooser: ReceiverUsability) : AnchorMessage(transactionId, intent)
    data class Choice(override val transactionId: Long, override val intent: FunctionIntent,
        val choice: AnchorChoice) : AnchorMessage(transactionId, intent)
    data class Result(override val transactionId: Long, override val intent: FunctionIntent,
        val result: AnchorRegistrationResult) : AnchorMessage(transactionId, intent)
    /** Original contact facts only; revision orders presentation, never input admission. */
    data class HeldState(override val transactionId: Long, override val intent: FunctionIntent,
        val revision: Long, val state: AnchorHoldState) : AnchorMessage(transactionId, intent) {
        init { require(revision >= 0) }
    }

    fun bytes(): ByteArray = bytes {
        writeByte(when (this@AnchorMessage) { is Feedback -> 0; is Offer -> 1; is Open -> 2; is Choice -> 3; is Result -> 4; is HeldState -> 5 })
        require(transactionId >= 0); writeLong(transactionId)
        val original = intent.bytes(); writeInt(original.size); write(original)
        when (val message = this@AnchorMessage) {
            is Feedback -> writeByte(message.status.ordinal)
            is Offer -> with(message.highlight) { writeDouble(left); writeDouble(top); writeDouble(right); writeDouble(bottom) }
            is Open -> { val fact = message.chooser.bytes(); writeInt(fact.size); write(fact) }
            is Choice -> writeByte(message.choice.ordinal)
            is Result -> writeByte(message.result.ordinal)
            is HeldState -> { writeLong(message.revision); writeByte(message.state.ordinal) }
        }
    }
    companion object {
        const val MIN_PAYLOAD_SIZE = 14 + FunctionIntent.MIN_PAYLOAD_SIZE
        const val MAX_PAYLOAD_SIZE = 17 + FunctionIntent.MAX_PAYLOAD_SIZE + ReceiverUsability.MAX_PAYLOAD_SIZE
        fun from(payload: ByteArray): AnchorMessage = DataInputStream(payload.inputStream()).use { input ->
            val kind = input.readUnsignedByte(); val id = input.readLong(); require(id >= 0)
            val size = input.readInt()
            require(size in FunctionIntent.MIN_PAYLOAD_SIZE..FunctionIntent.MAX_PAYLOAD_SIZE && size <= input.available())
            val intent = FunctionIntent.from(ByteArray(size).also(input::readFully))
            val value = when (kind) {
                0 -> Feedback(id, intent, AnchorFeedback.entries[input.readUnsignedByte()])
                1 -> Offer(id, intent, AnchorHighlight(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble()))
                2 -> {
                    val length = input.readInt()
                    require(length in ReceiverUsability.MIN_PAYLOAD_SIZE..ReceiverUsability.MAX_PAYLOAD_SIZE && length <= input.available())
                    Open(id, intent, ReceiverUsability.from(ByteArray(length).also(input::readFully)))
                }
                3 -> Choice(id, intent, AnchorChoice.entries[input.readUnsignedByte()])
                4 -> Result(id, intent, AnchorRegistrationResult.entries[input.readUnsignedByte()])
                5 -> HeldState(id, intent, input.readLong(), AnchorHoldState.entries[input.readUnsignedByte()])
                else -> error("Unknown Anchor message")
            }
            require(input.available() == 0)
            value
        }
    }
}
