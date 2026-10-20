package com.code2hack.eyeprojector.protocol

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoverySocketTest {
    private fun probe(mode: String) {
        val classes = listOf(LanDiscoverySocketProbe::class.java, LanDiscovery::class.java, Unit::class.java)
        val classpath = classes.map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val output = File.createTempFile("discovery-$mode-", ".log")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m", "-cp", classpath, LanDiscoverySocketProbe::class.java.name, mode)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("Host discovery probe exceeded10s", process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
            assertTrue(output.readText().contains("PASS $mode"))
        } finally { process.destroyForcibly(); output.delete() }
    }

    @Test fun failedQueryReplyDoesNotSuppressTheFollowingValidPeer() = probe("reply-fails")
    @Test fun actualReceiveSocketFailureStillStopsTheReceiveLoop() = probe("receive-fails")
    @Test fun actualParserRoleAndIdentityFiltersAreCountedWithoutPayloads() = probe("mixed-input")
}
