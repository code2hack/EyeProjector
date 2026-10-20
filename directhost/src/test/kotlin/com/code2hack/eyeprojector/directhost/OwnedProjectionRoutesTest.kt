package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Test

class OwnedProjectionRoutesTest {
    @Test fun stoppedOwnerCannotPublishAndOldCleanupCannotEraseReplacementEvenWithSameEpochText() {
        val routes = OwnedProjectionRoutes<Any>()
        val old = routes.begin("epoch")
        routes.invalidate()
        assertFalse(routes.publish(old, Any()))
        val fresh = routes.begin("epoch")
        val replacement = Any()
        assertTrue(routes.publish(fresh, replacement))
        routes.retire(old)
        assertSame(replacement, routes.capture("epoch")!!.target)
        assertFalse(routes.publish(old, Any()))
        assertNull(routes.capture("other"))
        assertNull(routes.capture(""))
        val captured = routes.capture("epoch")!!
        assertSame(replacement, routes.invalidate()!!.target)
        assertNull(routes.capture("epoch"))
        assertSame(replacement, captured.target)
    }
}
