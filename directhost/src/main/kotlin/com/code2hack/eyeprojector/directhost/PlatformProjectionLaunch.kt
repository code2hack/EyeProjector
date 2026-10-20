package com.code2hack.eyeprojector.directhost

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** No shell script: the Session owns the actual cmd/dumpsys process and reaps it on Stop. */
internal class PlatformProjectionLaunch(
    private val commands: OwnedProjectionCommands = OwnedProjectionCommands(),
    private val context: ProjectionContext = ProjectionContext(),
) : ProjectionLaunchAdapter {
    override fun launch(displayId: Int, packageName: String, home: Boolean) =
        commands.run(ProjectionLaunchCommands.launch(displayId, packageName, home, context), 4000)

    override fun foreground(displayId: Int): String? {
        val result = commands.run(listOf("/system/bin/dumpsys", "-t", "2", "activity", "activities"), 3000)
        return if (result.exit == 0 && !result.timedOut) ProjectionLaunchCommands.foreground(result.output, displayId) else null
    }

    override fun close() = commands.close()
}

/** Internal process adapter, reachable only through the two fixed operations above. */
internal class OwnedProjectionCommands(
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var active: Process? = null

    fun run(command: List<String>, timeoutMs: Long): ProjectionCommandResult {
        val process = synchronized(lock) {
            if (closed || Thread.currentThread().isInterrupted) return ProjectionCommandResult(null, "")
            check(active == null)
            start(command).also { active = it }
        }
        val output = ByteArrayOutputStream()
        val reader = Thread({
            runCatching { process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        // Drain all output; retain only a bounded diagnostic snapshot.
                        output.write(buffer, 0, count.coerceAtMost((1024 * 1024 - output.size()).coerceAtLeast(0)))
                    }
                }
            } }
        }, "eyeprojector-launch-output").apply { isDaemon = true; start() }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) reap(process)
            reader.join(1000)
            val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
            return ProjectionCommandResult(if (process.isAlive) null else process.exitValue(), text, !finished)
        } finally {
            reap(process)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            synchronized(lock) { if (active === process) active = null }
        }
    }

    private fun reap(process: Process) {
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(1000, TimeUnit.MILLISECONDS)) { "Owned launch command release incomplete" }
        }
    }

    override fun close() {
        val process = synchronized(lock) { closed = true; active }
        if (process != null) reap(process)
    }
}
