package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnedShellSessionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun replacementServiceRevokesOnlyItsFixedOwnedCopies() {
        val root = temporary.root.resolve("assigned-app-shell-copies")
        val other = temporary.root.resolve("other-app").apply { mkdirs(); resolve("private-state").writeText("retained") }
        val original = OwnedShellSessions(root)
        val permissions = mutableListOf<String>()
        val first = original.create { permissions.add(it.name) }
        first.resolve("paired-peer.properties").writeText("old copied trust")
        val replacement = OwnedShellSessions(root)
        assertFalse(replacement.isRevoked())
        assertTrue(replacement.revoke())
        assertFalse(first.exists()); assertTrue(replacement.isRevoked())
        assertEquals("retained", other.resolve("private-state").readText())
        assertEquals(2, permissions.size)
        val fresh = replacement.create {}
        assertNotEquals(first.name, fresh.name)
        assertTrue(replacement.revoke()); assertTrue(replacement.revoke())
    }
}
