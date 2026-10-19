package com.code2hack.eyeprojector.directhost

import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionJarTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "qualified stock server fixture".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun upstreamSelfUnlinkLeavesMasterForNextSession() {
        val master = temporary.newFile("master.jar").apply { writeBytes(bytes) }
        val first = temporary.root.resolve("session-one.jar")
        ShellMain.copySessionJar(master, first, digest)
        assertArrayEquals(bytes, first.readBytes())
        assertTrue(first.delete()) // Stock CleanUp.unlinkSelf() consumes this path.
        val second = temporary.root.resolve("session-two.jar")
        ShellMain.copySessionJar(master, second, digest)
        assertArrayEquals(bytes, second.readBytes())
        assertArrayEquals(bytes, master.readBytes())
    }

    @Test fun wrongBytesAreRejectedBeforeLaunch() {
        val master = temporary.newFile("wrong.jar").apply { writeText("changed server") }
        assertThrows(IllegalArgumentException::class.java) {
            ShellMain.copySessionJar(master, temporary.root.resolve("rejected.jar"), digest)
        }
    }

    @Test fun existingSessionPathIsNeverOverwritten() {
        val master = temporary.newFile("master.jar").apply { writeBytes(bytes) }
        val prior = temporary.newFile("already-used.jar").apply { writeText("retained prior bytes") }
        assertThrows(FileAlreadyExistsException::class.java) { ShellMain.copySessionJar(master, prior, digest) }
        assertEquals("retained prior bytes", prior.readText())
    }
}
