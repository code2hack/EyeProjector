package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class SessionPresentationTest {
    @Test fun panelAndLocalBackPreserveLiveSessionContext() {
        val presentation = SessionPresentation()
        presentation.streaming()
        assertTrue(presentation.projectedInput)
        presentation.showPanel()
        assertTrue(presentation.live)
        assertFalse(presentation.projectedInput)
        presentation.streaming() // Telemetry or decoder reconfiguration does not dismiss an open panel.
        assertTrue(presentation.panelVisible)
        assertTrue(presentation.back())
        assertTrue(presentation.projectedInput)
    }

    @Test fun terminalReleaseRetiresPanelReturnHistory() {
        val presentation = SessionPresentation()
        presentation.streaming(); presentation.showPanel(); presentation.disconnected()
        assertFalse(presentation.back())
        assertFalse(presentation.projectedInput)
        presentation.streaming()
        assertTrue(presentation.projectedInput)
    }
}
