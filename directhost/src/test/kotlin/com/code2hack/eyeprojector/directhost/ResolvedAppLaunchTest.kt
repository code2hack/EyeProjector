package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.FilterInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual adapter/owner; the process-start seam supplies local children, never Android commands. */
private class LaunchProcesses : Closeable {
    val calls = CopyOnWriteArrayList<List<String>>()
    val children = CopyOnWriteArrayList<Process>()
    val context = ProjectionContext("original-owner", "original-token")
    var resolverOutput = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\nexample.launcher/.NonDefaultActivity\n"
    var resolverExit = 0
    var resolverDelay = 0.0
    var nativeDelay = 0.0
    var nativeExit = 0
    var resolverWrap: (Process) -> Process = { it }
    var childStarted: (Boolean, Process) -> Unit = { _, _ -> }
    val adapter = PlatformProjectionLaunch(OwnedProjectionCommands { argv ->
        calls.add(argv)
        val resolver = argv.getOrNull(1) == "package"
        val home = argv.contains(ProjectionLaunchCommands.HOME_COMPONENT)
        val explicit = argv.indexOf("-n").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }
        val supported = home || explicit == "example.launcher/example.launcher.NonDefaultActivity"
        val output = if (resolver) resolverOutput else if (supported) "Status: ok\nActivity: ${explicit}\nComplete\n"
            else "Error: Activity not started, unable to resolve Intent\n"
        val exit = if (resolver) resolverExit else if (supported) nativeExit else 1
        val child = ProcessBuilder("python3", "-c",
            "import sys,time; time.sleep(float(sys.argv[3])); sys.stdout.write(sys.argv[1]); sys.exit(int(sys.argv[2]))",
            output, exit.toString(), (if (resolver) resolverDelay else nativeDelay).toString()).start()
        children.add(child)
        childStarted(resolver, child)
        if (resolver) resolverWrap(child) else child
    }, context)
    override fun close() = adapter.close()
}

class ResolvedAppLaunchRegressionTest {
    @Test fun nativeAdapterResolvesCurrentUserLauncherThenExplicitlyStartsThatComponent() {
        LaunchProcesses().use { f ->
            val result = f.adapter.launch(148, "example.launcher", false)
            assertEquals(0, result.exit)
            assertEquals(ProjectionLaunchStatus.STARTED, ProjectionLaunchCommands.launchStatus(result))
            assertEquals(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", "current",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", "example.launcher"), f.calls[0])
            assertEquals(listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--display", "148", "-f", "0x10000000",
                "--activity-reset-task-if-needed", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "-p", "example.launcher", "-n", "example.launcher/example.launcher.NonDefaultActivity"), f.calls[1])
            assertEquals(2, f.calls.size)
            assertTrue(f.children.none { it.isAlive })
        }
    }

    @Test fun homeRetainsItsExactSingleCommandAndContextWithoutResolvingAPackage() {
        LaunchProcesses().use { f ->
            val result = f.adapter.launch(148, "ignored.for.home", true)
            assertEquals(ProjectionLaunchStatus.STARTED, ProjectionLaunchCommands.launchStatus(result))
            assertEquals(listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--display", "148", "-f", "0x10000000",
                "--activity-clear-top", "-n", ProjectionLaunchCommands.HOME_COMPONENT,
                "--es", ProjectionContext.OWNER_EXTRA, "original-owner",
                "--es", ProjectionContext.TOKEN_EXTRA, "original-token"), f.calls.single())
            assertTrue(f.children.none { it.isAlive })
        }
    }
}

/** Delegates to a real owned child; only its caller-side stream cleanup has a test hook. */
private class OnResolverCleanup(private val child: Process, private val action: () -> Unit) : Process() {
    private val caller = Thread.currentThread()
    private val called = AtomicBoolean()
    override fun getInputStream() = object : FilterInputStream(child.inputStream) {
        override fun close() {
            super.close()
            if (Thread.currentThread() === caller && called.compareAndSet(false, true)) action()
        }
    }
    override fun getOutputStream() = child.outputStream
    override fun getErrorStream() = child.errorStream
    override fun waitFor() = child.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit) = child.waitFor(timeout, unit)
    override fun exitValue() = child.exitValue()
    override fun isAlive() = child.isAlive
    override fun destroy() = child.destroy()
    override fun destroyForcibly(): Process { child.destroyForcibly(); return this }
}

class ResolvedAppLaunchAdapterTest {
    @Test fun briefComponentParsingNormalizesRelativeNamesAndPreservesActualClassNamespace() {
        for ((raw, expected) in listOf(
            "example.launcher/.NonDefaultActivity" to "example.launcher/example.launcher.NonDefaultActivity",
            "example.launcher/example.launcher.NonDefaultActivity" to "example.launcher/example.launcher.NonDefaultActivity",
            "example.launcher/other.namespace.Container\$Launcher" to "example.launcher/other.namespace.Container\$Launcher")) {
            assertEquals(expected, ProjectionLaunchCommands.resolvedComponent(ProjectionCommandResult(0,
                "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\r\n$raw\r\n"), "example.launcher"))
        }
    }

    @Test fun resolverErrorsAbsenceAndMalformedOrWrongPackageResultsNeverStartNativeOrInventItsExit() {
        for ((output, exit) in listOf(
            "example.launcher/.NonDefaultActivity\n" to 7,
            "No activity found\n" to 0,
            "other.package/.NonDefaultActivity\n" to 0,
            "example.launcher/\n" to 0,
            "example.launcher/.\n" to 0,
            "example.launcher/.Bad Class\n" to 0,
            "example.launcher/.One/Two\n" to 0,
            "example.launcher/.One\nexample.launcher/.Two\n" to 0)) {
            LaunchProcesses().use { f ->
                f.resolverOutput = output; f.resolverExit = exit
                val result = f.adapter.launch(148, "example.launcher", false)
                assertNull(result.exit); assertFalse(result.timedOut)
                assertEquals(ProjectionLaunchStatus.FAILED, ProjectionLaunchCommands.launchStatus(result))
                assertNull(ProjectionLaunchCommands.component(result))
                assertEquals(1, f.calls.size); assertTrue(f.children.none { it.isAlive })
            }
        }
        assertNull(ProjectionLaunchCommands.resolvedComponent(
            ProjectionCommandResult(0, "example.launcher/.NonDefaultActivity\n", true), "example.launcher"))
    }

    @Test fun fullyQualifiedResolverAndNativeFailureRetainTheActualNativeResult() {
        LaunchProcesses().use { f ->
            f.resolverOutput = "example.launcher/example.launcher.NonDefaultActivity\n"
            f.nativeExit = 9
            val result = f.adapter.launch(148, "example.launcher", false)
            assertEquals(9, result.exit)
            assertEquals(ProjectionLaunchStatus.FAILED, ProjectionLaunchCommands.launchStatus(result))
            assertEquals("example.launcher/example.launcher.NonDefaultActivity", ProjectionLaunchCommands.component(result))
            assertFalse(result.output.contains("priority=")); assertEquals(2, f.calls.size)
            assertTrue(f.children.none { it.isAlive })
        }
    }

    @Test(timeout = 10000) fun resolverAndNativeShareOneExistingFourSecondWaitBudget() {
        LaunchProcesses().use { f ->
            f.resolverDelay = 2.2; f.nativeDelay = 2.2
            val started = System.nanoTime()
            val result = f.adapter.launch(148, "example.launcher", false)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(result.timedOut); assertEquals(2, f.calls.size)
            assertTrue("shared wait took ${elapsed}ms", elapsed < 6000)
            assertTrue(f.children.none { it.isAlive })
        }
    }

    @Test(timeout = 10000) fun resolverDrainAndReapElapsedCountBeforeNativeAdmission() {
        LaunchProcesses().use { f ->
            f.resolverWrap = { OnResolverCleanup(it) { Thread.sleep(4050) } }
            val result = f.adapter.launch(148, "example.launcher", false)
            assertTrue(result.timedOut); assertNull(result.exit)
            assertEquals(1, f.calls.size); assertTrue(f.children.none { it.isAlive })
        }
    }

    @Test(timeout = 10000) fun resolverTimeoutReapsItsChildWithoutStartingNativeOrRelabelingExit() {
        LaunchProcesses().use { f ->
            f.resolverDelay = 60.0
            val result = f.adapter.launch(148, "example.launcher", false)
            assertTrue(result.timedOut); assertNull(result.exit)
            assertEquals(1, f.calls.size); assertTrue(f.children.none { it.isAlive })
        }
    }

    @Test fun closeDuringResolverReapsChildAndCannotStartTheNextChild() {
        LaunchProcesses().use { f ->
            val entered = CountDownLatch(1)
            f.resolverDelay = 60.0; f.childStarted = { _, _ -> entered.countDown() }
            val caller = Executors.newSingleThreadExecutor()
            try {
                val pending = caller.submit<ProjectionCommandResult> { f.adapter.launch(148, "example.launcher", false) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.adapter.close()
                assertNull(pending.get(2, TimeUnit.SECONDS).exit)
                assertEquals(1, f.calls.size); assertTrue(f.children.none { it.isAlive })
                assertNull(f.adapter.launch(999, "example.launcher", false).exit)
                assertEquals(1, f.calls.size)
            } finally { caller.shutdownNow() }
        }
    }

    @Test fun closeBetweenOwnedChildrenPreventsAnyLateNativeStart() {
        LaunchProcesses().use { f ->
            val cleaned = AtomicBoolean()
            f.resolverWrap = { OnResolverCleanup(it) { cleaned.set(true); f.adapter.close() } }
            val result = f.adapter.launch(148, "example.launcher", false)
            assertTrue(cleaned.get()); assertNull(result.exit)
            assertFalse(result.timedOut); assertEquals(1, f.calls.size)
            assertTrue(f.children.none { it.isAlive })
        }
    }

    @Test fun interruptionBetweenOwnedChildrenPreventsAnyLateNativeStart() {
        LaunchProcesses().use { f ->
            val cleaned = AtomicBoolean()
            f.resolverWrap = { OnResolverCleanup(it) { cleaned.set(true); Thread.currentThread().interrupt() } }
            try {
                val result = f.adapter.launch(148, "example.launcher", false)
                assertTrue(cleaned.get()); assertTrue(Thread.currentThread().isInterrupted)
                assertNull(result.exit); assertEquals(1, f.calls.size)
                assertTrue(f.children.none { it.isAlive })
            } finally { Thread.interrupted() }
        }
    }

    @Test fun interruptionDuringResolverReapsActualChildAndCannotStartNative() {
        LaunchProcesses().use { f ->
            val entered = CountDownLatch(1); val done = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            f.resolverDelay = 60.0; f.childStarted = { _, _ -> entered.countDown() }
            val caller = Thread {
                try { f.adapter.launch(148, "example.launcher", false) }
                catch (_: InterruptedException) { interrupted.set(true) }
                finally { done.countDown() }
            }
            try {
                caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt()
                assertTrue(done.await(2, TimeUnit.SECONDS)); assertTrue(interrupted.get())
                assertEquals(1, f.calls.size); assertTrue(f.children.none { it.isAlive })
            } finally { caller.interrupt(); caller.join(2000) }
        }
    }
}
