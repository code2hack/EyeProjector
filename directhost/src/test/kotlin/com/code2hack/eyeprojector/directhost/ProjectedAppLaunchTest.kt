package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProjectedAppLaunchTest {
    private open class Adapter : ProjectionLaunchAdapter {
        val calls = mutableListOf<Triple<Int, String, Boolean>>()
        var foreground: String? = null
        var closes = 0
        override fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult {
            calls.add(Triple(displayId, packageName, home))
            foreground = packageName
            return ProjectionCommandResult(0, "Status: ok\nActivity: $packageName/.Main\n")
        }
        override fun foreground(displayId: Int) = foreground
        override fun close() { closes++ }
    }

    @Test fun currentBackendLogIdentifiesDisplayWithoutPhysicalOrOldDisplayFallback() {
        assertNull(ProjectionLaunchCommands.displayId("Display 0\n[server] DEBUG: id=9\n"))
        assertNull(ProjectionLaunchCommands.displayId("[server] INFO: New display: 480x640/160 (id="))
        assertEquals(47, ProjectionLaunchCommands.displayId("[server] INFO: Device: phone\n[server] INFO: New display: 480x640/160 (id=47)\n"))
        assertEquals(82, ProjectionLaunchCommands.displayId("[server] INFO: New display: 640x480/160 (id=82)\r\n"))
    }

    @Test fun nativeAppCommandUsesNumericNewTaskAndPreservesPackageLaunchFlags() {
        val app = ProjectionLaunchCommands.launch(47, "example.native.app", false)
        assertEquals(listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--display", "47",
            "-f", "0x10000000", "--activity-reset-task-if-needed", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-p", "example.native.app"), app)
        assertFalse(app.contains("-S")); assertFalse(app.contains("--activity-clear-task"))
    }

    @Test fun homeCommandUsesNumericNewTaskAndPreservesFixedComponentAndContext() {
        val home = ProjectionLaunchCommands.launch(47, "ignored.by.fixed.home", true, ProjectionContext("owner", "actual-token"))
        assertEquals(listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--display", "47",
            "-f", "0x10000000", "--activity-clear-top", "-n", ProjectionLaunchCommands.HOME_COMPONENT,
            "--es", ProjectionContext.OWNER_EXTRA, "owner",
            "--es", ProjectionContext.TOKEN_EXTRA, "actual-token"), home)
        assertFalse(home.contains("ignored.by.fixed.home"))
    }

    @Test fun commandResultsAndDisplayObservationRemainSeparate() {
        val reused = ProjectionCommandResult(0, "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\nActivity: example.app/.Main\n")
        assertEquals(ProjectionLaunchStatus.STARTED, ProjectionLaunchCommands.launchStatus(reused))
        assertEquals("example.app/.Main", ProjectionLaunchCommands.component(reused))
        assertEquals(ProjectionLaunchStatus.FAILED, ProjectionLaunchCommands.launchStatus(ProjectionCommandResult(0, "Error: Activity class does not exist\n")))
        assertEquals(ProjectionLaunchStatus.TIMED_OUT, ProjectionLaunchCommands.launchStatus(ProjectionCommandResult(0, "Status: timeout\n")))
        assertEquals(ProjectionLaunchStatus.TIMED_OUT, ProjectionLaunchCommands.launchStatus(ProjectionCommandResult(null, "Status: ok\n", true)))
        val dump = """
            Display #0 (activities from top to bottom):
                mResumedActivity: ActivityRecord{ab u0 physical.launcher/.Home t1}
            Display #47 (activities from top to bottom):
                Resumed activities in task display areas (from top to bottom):
                    Resumed: ActivityRecord{cd u0 example.actual/.Main t8}
            Display #52 (activities from top to bottom):
                Resumed: ActivityRecord{ef u0 unrelated.app/.Main t9}
            ResumedActivity: ActivityRecord{ab u0 physical.launcher/.Home t1}
        """.trimIndent()
        assertEquals("example.actual", ProjectionLaunchCommands.foreground(dump, 47))
        assertNull(ProjectionLaunchCommands.foreground(dump, 48))
        assertNull(ProjectionLaunchCommands.foreground("ResumedActivity: ActivityRecord{ab u0 physical.launcher/.Home t1}", 47))
        val state = ProjectionDisplayState(8, "generation:child", 47, "requested.app", ProjectionLaunchStatus.STARTED, 0,
            "requested.app/.Main", "example.actual", ProjectionContext("owner", "actual-token"))
        assertEquals(state, ProjectionDisplayState.from(state.bytes()))
        assertThrows(IllegalArgumentException::class.java) { ProjectionDisplayState.from(state.bytes() + 1.toByte()) }
    }

    @Test fun initialHomePhoneGridAndNavigationShareCurrentDisplayAndCancelHeldContactBeforeLaunch() {
        val geometry = Geometry(480, 640)
        val controls = mutableListOf<ByteArray>()
        val touch = PhoneTouchDispatcher { controls.add(it) }
        touch.geometry(geometry)
        val adapter = object : Adapter() {
            override fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult {
                assertFalse(touch.holding)
                return super.launch(displayId, packageName, home)
            }
        }
        ProjectedAppLaunch("one:child1", adapter, beforeLaunch = { touch.cancel() }).use { launcher ->
            assertEquals(ProjectionLaunchStatus.UNAVAILABLE, launcher.launch(1, "example.app").status)
            launcher.initialize(47)
            launcher.initialize(99)
            assertEquals(ProjectionLaunchCommands.HOME_PACKAGE, launcher.snapshot(true).foregroundPackage)
            touch.touch(Touch(Touch.Phase.DOWN, 1, Tap(120, 220, geometry)))
            assertEquals("example.phone", launcher.launch(2, "example.phone").foregroundPackage)
            assertEquals(3, controls.last()[1].toInt())
            assertEquals("example.grid", launcher.launch(3, "example.grid").foregroundPackage)
            launcher.home()
            assertEquals(ProjectionLaunchCommands.HOME_PACKAGE, launcher.snapshot(true).foregroundPackage)
            assertEquals(listOf(47, 47, 47, 47), adapter.calls.map { it.first })
            assertEquals(listOf(true, false, false, true), adapter.calls.map { it.third })
        }
        assertEquals(1, adapter.closes)
    }

    @Test fun stopCancelsQueuedRequestsAndLateResultCannotChangeClosedOrFreshGeneration() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val adapter = object : Adapter() {
            override fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult {
                if (!home) {
                    entered.countDown()
                    // Simulate an operation completing even after command interruption.
                    while (finish.count > 0) try { finish.await() } catch (_: InterruptedException) { }
                }
                return super.launch(displayId, packageName, home)
            }
            override fun close() { super.close(); finish.countDown() }
        }
        val launcher = ProjectedAppLaunch("old:child", adapter) {}
        val callers = Executors.newFixedThreadPool(2)
        try {
            launcher.initialize(47); launcher.snapshot(true)
            val first = callers.submit<ProjectionDisplayState> { launcher.launch(11, "example.first") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = callers.submit<ProjectionDisplayState> { launcher.launch(12, "example.queued") }
            launcher.close()
            val result = first.get(2, TimeUnit.SECONDS)
            assertEquals(11, result.requestId); assertEquals("example.first", result.requestedPackage)
            assertEquals(ProjectionLaunchStatus.CLOSED, result.status)
            assertEquals(ProjectionLaunchStatus.CLOSED, second.get(2, TimeUnit.SECONDS).status)
            assertEquals(ProjectionLaunchStatus.CLOSED, launcher.snapshot().status)
            assertNull(launcher.snapshot().foregroundPackage)
            assertTrue(adapter.calls.none { it.second == "example.queued" })
            launcher.initialize(88)
            assertEquals(47, launcher.snapshot().displayId)
            ProjectedAppLaunch("fresh:child", Adapter()) {}.use { fresh ->
                fresh.initialize(88)
                assertEquals(88, fresh.snapshot(true).displayId)
                assertNotEquals(launcher.snapshot().generation, fresh.snapshot().generation)
            }
        } finally { finish.countDown(); launcher.close(); callers.shutdownNow() }
    }

    @Test fun failedHelperReleaseIsRetriedAndConcurrentCloseWaitsForCompletion() {
        var attempts = 0
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val adapter = object : Adapter() {
            override fun close() {
                attempts++
                if (attempts == 1) error("synthetic reap failure")
                entered.countDown(); check(finish.await(2, TimeUnit.SECONDS)); super.close()
            }
        }
        val launcher = ProjectedAppLaunch("retry:child", adapter) {}
        assertThrows(IllegalStateException::class.java) { launcher.close() }
        val callers = Executors.newFixedThreadPool(2)
        try {
            val first = callers.submit { launcher.close() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = callers.submit { launcher.close() }
            assertFalse(second.isDone)
            finish.countDown()
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS)
            assertEquals(2, attempts); assertEquals(1, adapter.closes)
        } finally { finish.countDown(); callers.shutdownNow() }
    }

    @Test fun canceledRequestWaitingForControlLockCannotSubmitALateLaunch() {
        val gate = Any(); val entered = CountDownLatch(1); val callerDone = CountDownLatch(1)
        val block = AtomicBoolean()
        val adapter = Adapter()
        ProjectedAppLaunch("cancel:child", adapter, beforeLaunch = {
            if (block.get()) { entered.countDown(); synchronized(gate) { } }
        }).use { launcher ->
            launcher.initialize(47); launcher.snapshot(true)
            block.set(true)
            synchronized(gate) {
                val caller = Thread { try { launcher.launch(88, "example.canceled") } finally { callerDone.countDown() } }
                caller.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                caller.interrupt() // Same Future.cancel(true) path as the bounded operation deadline.
                assertTrue(callerDone.await(2, TimeUnit.SECONDS))
            }
            launcher.snapshot(true)
            assertEquals(listOf(ProjectionLaunchCommands.HOME_PACKAGE), adapter.calls.map { it.second })
        }
        var starts = 0
        OwnedProjectionCommands { starts++; error("Canceled command must not start") }.use { commands ->
            Thread.currentThread().interrupt()
            try { assertNull(commands.run(listOf("canceled-test-operation"), 50).exit) }
            finally { Thread.interrupted() }
        }
        assertEquals(0, starts)
    }

    @Test fun actualOwnedProcessDrainsFullPipeAndReapsOnTimeoutAndClose() {
        var process: Process? = null
        OwnedProjectionCommands { process = ProcessBuilder("python3", "-c", "print('x'*200000); print('Status: ok')").start(); process!! }.use {
            val result = it.run(listOf("fixed-test-operation"), 3000)
            assertEquals(0, result.exit); assertFalse(result.timedOut)
            assertTrue(result.output.endsWith("Status: ok\n")); assertFalse(process!!.isAlive)
        }
        OwnedProjectionCommands { process = ProcessBuilder("python3", "-c", "import time; time.sleep(60)").start(); process!! }.use {
            assertTrue(it.run(listOf("fixed-test-operation"), 50).timedOut)
            assertFalse(process!!.isAlive)
        }
        val started = CountDownLatch(1)
        val runner = OwnedProjectionCommands {
            process = ProcessBuilder("python3", "-c", "import time; time.sleep(60)").start()
            started.countDown(); process!!
        }
        val caller = Executors.newSingleThreadExecutor()
        try {
            val pending = caller.submit<ProjectionCommandResult> { runner.run(listOf("fixed-test-operation"), 4000) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            runner.close()
            pending.get(2, TimeUnit.SECONDS)
            assertFalse(process!!.isAlive)
            assertNull(runner.run(listOf("closed-test-operation"), 50).exit)
        } finally { runner.close(); caller.shutdownNow() }
    }
}
