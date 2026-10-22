package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import org.junit.Assert.*
import org.junit.Test

class SessionAttachmentTest {
    @Test fun oldReadinessCannotReconnectAfterExplicitDisconnectOrFailure() {
        val attachment = SessionAttachment()
        val old = SessionState("old", SessionPhase.AVAILABLE)
        assertTrue(attachment.available(old))
        assertFalse(attachment.available(old))
        assertEquals("old", attachment.release())
        repeat(20) { assertFalse(attachment.available(old)) }
        assertTrue(attachment.available(old.copy(epoch = "fresh")))
        attachment.release()
        assertFalse(attachment.available(old))
    }

    @Test fun startupAndStreamingTelemetryDoNotCreateAnotherSession() {
        val attachment = SessionAttachment()
        assertFalse(attachment.available(SessionState("a", SessionPhase.STARTING)))
        assertFalse(attachment.available(SessionState("a", SessionPhase.STREAMING)))
        assertTrue(attachment.available(SessionState("a", SessionPhase.AVAILABLE)))
        assertFalse(attachment.available(SessionState("b", SessionPhase.AVAILABLE)))
    }
}
