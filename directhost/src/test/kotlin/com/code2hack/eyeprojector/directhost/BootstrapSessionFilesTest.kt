package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapSessionFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = temporary.newFolder().apply {
        resolve("identity.p12").writeBytes(byteArrayOf(1, 2, 3))
        resolve("password").writeText("fixture-password\n")
        resolve("endpoint.properties").writeText("host=192.168.42.1\nport=27183\n")
    }

    @Test fun pairedTrustExportsWithoutLegacyCertificateAndKeepsPhoneBindEndpoint() {
        val source = fixture()
        val paired = "host=192.168.42.2\nprojectionPort=27183\ncertificate=fixture\n"
        source.resolve("paired-peer.properties").writeText(paired)
        val snapshot = requireNotNull(BootstrapSessionFiles.read(source))
        val shell = temporary.newFolder()
        val privateFiles = mutableListOf<String>()
        snapshot.copyInto(shell) { privateFiles.add(it.name) }
        assertEquals(paired, shell.resolve("paired-peer.properties").readText())
        assertEquals("host=192.168.42.1\nport=27183\n", shell.resolve("endpoint.properties").readText())
        assertFalse(shell.resolve("peer.crt").exists())
        assertEquals(setOf("identity.p12", "password", "endpoint.properties", "paired-peer.properties"), privateFiles.toSet())
        assertArrayEquals(source.resolve("identity.p12").readBytes(), shell.resolve("identity.p12").readBytes())
    }

    @Test fun pairedFileIsPreferredEvenWhenLegacyFileExists() {
        val source = fixture()
        source.resolve("paired-peer.properties").writeText("paired fixture")
        source.resolve("peer.crt").writeText("old fixture")
        val snapshot = requireNotNull(BootstrapSessionFiles.read(source))
        assertNull(snapshot.legacyPeer)
        assertEquals("paired fixture", snapshot.pairedPeer!!.decodeToString())
    }

    @Test fun emptyPairedFileIsPreservedForBackendFailureRatherThanFallingBack() {
        val source = fixture()
        source.resolve("paired-peer.properties").writeBytes(byteArrayOf())
        source.resolve("peer.crt").writeText("old fixture")
        val shell = temporary.newFolder()
        requireNotNull(BootstrapSessionFiles.read(source)).copyInto(shell) {}
        assertTrue(shell.resolve("paired-peer.properties").isFile)
        assertEquals(0L, shell.resolve("paired-peer.properties").length())
        assertFalse(shell.resolve("peer.crt").exists())
    }

    @Test fun legacyFourFileFixtureRemainsSupported() {
        val source = fixture()
        source.resolve("peer.crt").writeText("legacy fixture")
        val snapshot = requireNotNull(BootstrapSessionFiles.read(source))
        assertNull(snapshot.pairedPeer)
        val shell = temporary.newFolder()
        snapshot.copyInto(shell) {}
        assertEquals("legacy fixture", shell.resolve("peer.crt").readText())
        assertFalse(shell.resolve("paired-peer.properties").exists())
        assertEquals("fixture-password\n", shell.resolve("password").readText())
    }

    @Test fun missingTrustOrBindEndpointIsMissingSetup() {
        val source = fixture()
        assertNull(BootstrapSessionFiles.read(source))
        source.resolve("paired-peer.properties").writeText("paired fixture")
        assertTrue(source.resolve("endpoint.properties").delete())
        assertNull(BootstrapSessionFiles.read(source))
    }
}
