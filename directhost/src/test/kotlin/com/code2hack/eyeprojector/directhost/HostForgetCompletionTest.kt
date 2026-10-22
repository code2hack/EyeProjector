package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Test

class HostForgetCompletionTest {
    @Test fun callbackWaitsForRevocationAndUiDispatchAndRunsOnce() {
        val ui = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val forget = HostForgetCompletion(ui::add)
        forget.request {
            assertTrue(forget.pending())
            assertFalse(forget.awaitingTeardown())
            events.add("trust deleted on UI")
        }
        assertTrue(forget.pending()); assertTrue(forget.awaitingTeardown())
        assertTrue(ui.isEmpty()); assertTrue(events.isEmpty())
        forget.revoked(); forget.revoked()
        assertEquals(1, ui.size)
        assertTrue(forget.pending()); assertTrue(events.isEmpty())
        ui.removeAt(0).invoke()
        assertEquals(listOf("trust deleted on UI"), events)
        assertFalse(forget.pending()); assertFalse(forget.awaitingTeardown())
        forget.revoked(); assertTrue(ui.isEmpty())
    }

    @Test fun repeatedForgetCoalescesWhileTeardownIsUnavailable() {
        val ui = mutableListOf<() -> Unit>()
        var completions = 0
        val forget = HostForgetCompletion(ui::add)
        forget.request { completions++ }
        repeat(20) { forget.request { fail("repeated Forget must coalesce") } }
        assertEquals(0, completions)
        assertTrue(forget.awaitingTeardown())
        forget.revoked(); ui.single().invoke()
        assertEquals(1, completions)
    }

    @Test fun callbackFailureReleasesPendingOperationForFreshUserRequest() {
        val ui = mutableListOf<() -> Unit>()
        val forget = HostForgetCompletion(ui::add)
        forget.request { throw IllegalStateException("UI deletion failed") }
        forget.revoked()
        assertTrue(runCatching { ui.removeAt(0).invoke() }.isFailure)
        assertFalse(forget.pending())
        var retried = false
        forget.request { retried = true }; forget.revoked(); ui.single().invoke()
        assertTrue(retried)
    }
}
