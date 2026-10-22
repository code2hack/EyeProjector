package com.code2hack.eyeprojector.protocol

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Compile the actual test-APK helper against getter-only doubles: control APIs are unavailable. */
class AppNetworkDiagnosticsTest {
    companion object {
        private lateinit var classes: File
        @JvmStatic @BeforeClass fun compileActualHelper() {
            classes = Files.createTempDirectory("app-network-source-").toFile().apply { deleteOnExit() }
            val helper = File("src/androidTest/java/com/code2hack/eyeprojector/protocol/AppNetworkDiagnostics.java")
            val fixtures = File("src/testFixtures/appNetwork").walkTopDown().filter { it.extension == "java" }.toList()
            val args = listOf(File(System.getProperty("java.home"), "bin/javac").path,
                "-encoding", "UTF-8", "-source", "17", "-target", "17", "-d", classes.path) +
                (fixtures + helper).map { it.path }
            val output = File.createTempFile("app-network-compile-", ".log")
            val compiler = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
            try {
                assertTrue("JDK17 fixture compile exceeded10s", compiler.waitFor(10, TimeUnit.SECONDS))
                assertEquals(output.readText(), 0, compiler.exitValue())
            } finally { compiler.destroyForcibly(); output.delete() }
        }
    }

    private fun probe(mode: String) {
        val output = File.createTempFile("app-network-$mode-", ".log")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx128m", "-cp", classes.path, "AppNetworkProbe", mode)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
            assertTrue(output.readText().contains("PASS $mode"))
            File("build/reports/app-network/$mode.log").apply { parentFile.mkdirs(); writeText(output.readText()) }
        } finally { process.destroyForcibly(); output.delete() }
    }

    @Test fun selectedNetworksAndAllowedFieldsHaveStablePrivateSchema() = probe("mixed")
    @Test fun nullDefaultAndUnboundProcessRemainDistinctSelections() = probe("nulls")
    @Test fun getterAndMissingApiFailuresDoNotSuppressOtherObservations() = probe("errors")
    @Test fun absentServiceIsAnObservation() = probe("service-null")
    @Test fun deniedServiceIsClassOnlyObservation() = probe("service-error")
    @Test fun largeRouteListsAreReportedAsTruncatedWithoutControl() = probe("many-routes")
}
