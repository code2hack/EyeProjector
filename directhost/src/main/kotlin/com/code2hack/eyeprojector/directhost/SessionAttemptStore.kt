package com.code2hack.eyeprojector.directhost

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** App-private control correlation survives a Phone UI process restart while its backend still owns A. */
internal class SessionAttemptStore(private val file: File) {
    fun read(): SessionController.Correlation {
        if (!file.exists()) return SessionController.Correlation()
        val data = Properties().apply { file.inputStream().use(::load) }
        val attempts = (0 until data.getProperty("count").toInt()).associate { index ->
            data.getProperty("attempt.$index.id") to SessionController.Binding(
                data.getProperty("attempt.$index.baseline"), data.getProperty("attempt.$index.owner"))
        }
        return SessionController.Correlation(data.getProperty("owner"), data.getProperty("baseline"), attempts)
    }

    fun write(value: SessionController.Correlation) {
        val data = Properties().apply {
            value.owner?.let { setProperty("owner", it) }
            value.baseline?.let { setProperty("baseline", it) }
            setProperty("count", value.attempts.size.toString())
            value.attempts.entries.forEachIndexed { index, (id, binding) ->
                setProperty("attempt.$index.id", id)
                setProperty("attempt.$index.baseline", binding.baseline)
                binding.owner?.let { setProperty("attempt.$index.owner", it) }
            }
        }
        val temporary = Files.createTempFile(file.parentFile.toPath(), "session-attempts-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { data.store(it, null) }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
