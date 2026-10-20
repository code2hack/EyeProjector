package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test

class StatePublisherTest {
    @Test fun callbackFailureStillDeliversQueuedTerminalWorkAndReleasesPublisher() {
        val publisher = StatePublisher()
        val delivered = mutableListOf<String>()
        val failure = IllegalStateException("application callback failed")
        try {
            publisher.publish {
                delivered.add("STREAMING")
                publisher.publish { delivered.add("STOPPED") }
                throw failure
            }
            fail("callback error must remain visible")
        } catch (error: IllegalStateException) { assertSame(failure, error) }
        publisher.publish { delivered.add("next") }
        assertEquals(listOf("STREAMING", "STOPPED", "next"), delivered)
    }
}
