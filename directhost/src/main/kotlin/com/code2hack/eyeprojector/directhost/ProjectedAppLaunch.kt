package com.code2hack.eyeprojector.directhost

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ProjectionCommandResult(val exit: Int?, val output: String, val timedOut: Boolean = false)

/** Fixed launch and observation operations, owned and closed by the existing Session. */
internal interface ProjectionLaunchAdapter : Closeable {
    fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult
    fun foreground(displayId: Int): String?
}

internal object ProjectionLaunchCommands {
    const val HOME_PACKAGE = "com.code2hack.eyeprojector.directhost"
    const val HOME_COMPONENT = "$HOME_PACKAGE/.ProjectedHomeActivity"

    fun launch(displayId: Int, packageName: String, home: Boolean, context: ProjectionContext = ProjectionContext()): List<String> =
        listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--display", displayId.toString(), "--activity-new-task") +
            if (home) listOf("--activity-clear-top", "-n", HOME_COMPONENT,
                "--es", ProjectionContext.OWNER_EXTRA, context.ownerEpoch,
                "--es", ProjectionContext.TOKEN_EXTRA, context.projectionToken)
            else listOf("--activity-reset-task-if-needed", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", "-p", packageName)

    /** Pinned NewDisplayCapture prints this before the first video session metadata. */
    fun displayId(log: String): Int? = Regex(
        "(?m)^\\[server] INFO: New display: [0-9]+x[0-9]+/[0-9]+ \\(id=([0-9]+)\\)\\r?$",
    ).find(log)?.groupValues?.get(1)?.toIntOrNull()

    fun launchStatus(result: ProjectionCommandResult): ProjectionLaunchStatus = when {
        result.timedOut || result.output.lineSequence().any { it.trim() == "Status: timeout" } -> ProjectionLaunchStatus.TIMED_OUT
        result.exit == 0 && result.output.lineSequence().any { it.trim() == "Status: ok" } &&
            result.output.lineSequence().none { it.trimStart().startsWith("Error:") || it.contains("Exception") } -> ProjectionLaunchStatus.STARTED
        else -> ProjectionLaunchStatus.FAILED
    }

    fun component(result: ProjectionCommandResult): String? = result.output.lineSequence()
        .map { it.trim() }.firstOrNull { it.startsWith("Activity: ") }?.removePrefix("Activity: ")

    /** Android's activities dump lists each display separately, in top-to-bottom task order. */
    fun foreground(dump: String, displayId: Int): String? {
        var currentDisplay: Int? = null
        var resumedSection = false
        val display = Regex("^\\s*Display #([0-9]+) \\(activities from top to bottom\\):.*$")
        val resumed = Regex("^\\s*Resumed: ActivityRecord\\{[^}]* u[0-9]+ ([^ /]+)/[^ }]+.*$")
        for (line in dump.lineSequence()) {
            display.matchEntire(line)?.let { currentDisplay = it.groupValues[1].toIntOrNull(); resumedSection = false }
            if (line.trim() == "Resumed activities in task display areas (from top to bottom):") resumedSection = true
            if (currentDisplay == displayId && resumedSection) resumed.matchEntire(line)?.let { return it.groupValues[1] }
        }
        return null
    }
}

/** Serializes app/Home requests without holding Session output, scale or control locks. */
internal class ProjectedAppLaunch(
    generation: String,
    private val adapter: ProjectionLaunchAdapter,
    context: ProjectionContext = ProjectionContext(),
    private val beforeLaunch: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val admission = Any()
    private val release = OwnedRelease()
    private val state = AtomicReference(ProjectionDisplayState(generation = generation, context = context))
    private val executor = Executors.newSingleThreadExecutor()

    fun initialize(displayId: Int) = synchronized(admission) {
        if (closed.get()) return@synchronized
        val previous = state.get()
        if (previous.displayId != null) return@synchronized
        state.set(previous.copy(displayId = displayId, status = ProjectionLaunchStatus.READY))
        executor.execute { execute(0, ProjectionLaunchCommands.HOME_PACKAGE, true) }
    }

    fun home() = synchronized(admission) {
        if (!closed.get()) executor.execute { execute(0, ProjectionLaunchCommands.HOME_PACKAGE, true) }
    }

    fun launch(requestId: Long, packageName: String): ProjectionDisplayState {
        val future = synchronized(admission) {
            if (closed.get()) return unavailable(requestId, packageName)
            executor.submit<ProjectionDisplayState> { execute(requestId, packageName, false) }
        }
        return await(future, requestId, packageName)
    }

    fun snapshot(observe: Boolean = false): ProjectionDisplayState {
        if (!observe || closed.get() || state.get().displayId == null) return state.get()
        val future = synchronized(admission) {
            if (closed.get()) return state.get()
            executor.submit<ProjectionDisplayState> {
                val previous = state.get()
                val foreground = adapter.foreground(previous.displayId!!)
                state.updateAndGet { current -> if (closed.get()) current else previous.copy(foregroundPackage = foreground) }
                state.get()
            }
        }
        return await(future, state.get().requestId, state.get().requestedPackage.orEmpty())
    }

    private fun execute(requestId: Long, packageName: String, home: Boolean): ProjectionDisplayState {
        val display = state.get().displayId
        if (closed.get() || display == null) return unavailable(requestId, packageName)
        val result = try {
            beforeLaunch()
            if (closed.get() || Thread.currentThread().isInterrupted) return unavailable(requestId, packageName)
            adapter.launch(display, packageName, home)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return unavailable(requestId, packageName)
        } catch (_: Exception) { ProjectionCommandResult(null, "") }
        val foreground = if (!closed.get()) runCatching { adapter.foreground(display) }.getOrNull() else null
        state.updateAndGet { current -> if (closed.get()) current else current.copy(requestId = requestId, requestedPackage = packageName,
            status = ProjectionLaunchCommands.launchStatus(result), commandExit = result.exit,
            launchedComponent = ProjectionLaunchCommands.component(result), foregroundPackage = foreground) }
        return if (closed.get()) unavailable(requestId, packageName) else state.get()
    }

    private fun unavailable(requestId: Long, packageName: String) = state.get().copy(
        requestId = requestId, requestedPackage = packageName,
        status = if (closed.get()) ProjectionLaunchStatus.CLOSED else ProjectionLaunchStatus.UNAVAILABLE,
        commandExit = null, launchedComponent = null, foregroundPackage = null)

    private fun await(future: Future<ProjectionDisplayState>, requestId: Long, packageName: String): ProjectionDisplayState = try {
        future.get(12, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        unavailable(requestId, packageName).copy(status = ProjectionLaunchStatus.TIMED_OUT)
    } catch (_: InterruptedException) {
        future.cancel(true)
        Thread.currentThread().interrupt()
        unavailable(requestId, packageName)
    } catch (_: Exception) { unavailable(requestId, packageName) }

    override fun close() {
        synchronized(admission) {
            closed.set(true)
            state.updateAndGet { it.copy(status = ProjectionLaunchStatus.CLOSED, foregroundPackage = null) }
            executor.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
        }
        release.close { adapter.close() }
    }
}
