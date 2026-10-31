package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AnchorHoldWireTest {
    private val original = FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.PHONE_HID, 2, 3,
        FunctionIntentContext(ReceiverUsability("phone", ReceiverIncarnation("glasses", 1), 4, 5,
            ReceiverUsabilityMode.PROJECTED), HeadPointerState(5, 6, true, true, 10f, 20f,
            Fit(0, 0, 480, 640), Geometry(480, 640))))

    @Test fun matchedPhoneAndGlassesWireRetainsOriginalHeldStateAndRegistrationValues() {
        val messages = AnchorHoldState.entries.mapIndexed { i, state -> AnchorMessage.HeldState(7, original, i.toLong(), state) } +
            listOf(AnchorMessage.Feedback(7, original, AnchorFeedback.HOLD_SUBMITTED),
                AnchorMessage.Feedback(8, original, AnchorFeedback.RELEASE_SUBMITTED),
                AnchorMessage.Offer(9, original, AnchorHighlight(1.0, 2.0, 3.0, 4.0)),
                AnchorMessage.Open(9, original, original.context.usability.copy(mode = ReceiverUsabilityMode.LOCAL_PANEL)),
                AnchorMessage.Choice(9, original, AnchorChoice.HOLD),
                AnchorMessage.Result(9, original, AnchorRegistrationResult.SAVED_HOLD))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).also { out ->
            Wire.writeWelcome(out)
            messages.forEach { Wire.writeFrame(out, Frame(Wire.ANCHOR, payload = it.bytes())) }
        }
        val input = DataInputStream(bytes.toByteArray().inputStream())
        Wire.readWelcome(input)
        assertEquals(11, Wire.VERSION)
        assertEquals(messages, messages.map { AnchorMessage.from(Wire.readFrame(input).payload) })
        assertEquals(0, input.available())
    }

    @Test fun wire10HandshakeCannotAdmitWire11HeldState() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).also { out -> out.writeInt(Wire.MAGIC); out.writeInt(10); out.writeByte(Wire.STREAM) }
        assertThrows(IllegalArgumentException::class.java) { Wire.readRequest(DataInputStream(bytes.toByteArray().inputStream())) }
    }
}
