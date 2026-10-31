package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AnchorMessageTest {
    private val original = FunctionIntent(FunctionIntentKind.REGISTER, FunctionSource.GLASSES_NATIVE, 3, 4,
        FunctionIntentContext(ReceiverUsability("owner", ReceiverIncarnation("glasses", 1), 2, 5, ReceiverUsabilityMode.PROJECTED),
            HeadPointerState(5, 6, true, true, 120f, 230f, Fit(20, 30, 480, 640), Geometry(480, 640))))
    @Test fun matchedAnchorWireRoundTripsTypedOriginalTransactions() {
        val values = listOf<AnchorMessage>(AnchorMessage.Feedback(1, original, AnchorFeedback.NO_ANCHOR),
            AnchorMessage.Offer(2, original, AnchorHighlight(100.0, 200.0, 140.0, 240.0)),
            AnchorMessage.Open(2, original, original.context.usability.copy(revision = 3, inputRevision = 6, mode = ReceiverUsabilityMode.LOCAL_PANEL)),
            AnchorMessage.Choice(2, original, AnchorChoice.CLICK), AnchorMessage.Result(2, original, AnchorRegistrationResult.SAVED_CLICK))
        val buffer = ByteArrayOutputStream(); val out = DataOutputStream(buffer)
        Wire.writeWelcome(out); values.forEach { Wire.writeFrame(out, Frame(Wire.ANCHOR, payload = it.bytes())) }
        val input = DataInputStream(buffer.toByteArray().inputStream()); Wire.readWelcome(input)
        assertEquals(11, Wire.VERSION)
        assertEquals(values, values.map { AnchorMessage.from(Wire.readFrame(input).payload) })
        assertEquals(0, input.available())
    }
    @Test fun truncatedAndTrailingMessagesCannotBecomeValidTransactions() {
        val bytes = AnchorMessage.Choice(2, original, AnchorChoice.HOLD).bytes()
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0))) {
            assertThrows(Exception::class.java) { AnchorMessage.from(bad) }
        }
    }
    @Test fun heldStatesRetainOriginalContactTransactionAndOrderingRevision() {
        for ((index, state) in AnchorHoldState.entries.withIndex()) {
            val value = AnchorMessage.HeldState(41, original, 90L + index, state)
            val decoded = AnchorMessage.from(value.bytes()) as AnchorMessage.HeldState
            assertEquals(value, decoded)
            assertEquals(41L, decoded.transactionId)
            assertEquals(original, decoded.intent)
            assertEquals(90L + index, decoded.revision)
            assertEquals(state, decoded.state)
        }
    }
}
