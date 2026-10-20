package com.code2hack.eyeprojector.directhost

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Atomically retain admission, cancellation intent and retired identities across Phone process death. */
internal class SessionAttemptStore(private val file: File) {
    fun read(): SessionController.Correlation {
        if (!file.exists()) return SessionController.Correlation()
        val data = Properties().apply { file.inputStream().use(::load) }
        val attempts = (0 until data.getProperty("count").toInt()).associate { index ->
            data.getProperty("attempt.$index.id") to SessionController.Binding(
                data.getProperty("attempt.$index.baseline"), data.getProperty("attempt.$index.owner"),
                data.getProperty("attempt.$index.canceled", "false").toBoolean(),
                data.getProperty("attempt.$index.unresolved", "false").toBoolean(),
                data.getProperty("attempt.$index.retired", "false").toBoolean())
        }
        return SessionController.Correlation(data.getProperty("owner"), data.getProperty("baseline"), attempts,
            data.getProperty("stopOwner"), data.getProperty("stopUnresolved", "false").toBoolean())
    }

    fun write(value: SessionController.Correlation) {
        val data = Properties().apply {
            value.owner?.let { setProperty("owner", it) }
            value.baseline?.let { setProperty("baseline", it) }
            value.stopOwner?.let { setProperty("stopOwner", it) }
            setProperty("stopUnresolved", value.stopUnresolved.toString())
            setProperty("count", value.attempts.size.toString())
            value.attempts.entries.forEachIndexed { index, (id, binding) ->
                setProperty("attempt.$index.id", id)
                setProperty("attempt.$index.baseline", binding.baseline)
                binding.owner?.let { setProperty("attempt.$index.owner", it) }
                setProperty("attempt.$index.canceled", binding.canceled.toString())
                setProperty("attempt.$index.unresolved", binding.unresolved.toString())
                setProperty("attempt.$index.retired", binding.retired.toString())
            }
        }
        val temporary = Files.createTempFile(file.parentFile.toPath(), "session-attempts-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { data.store(it, null) }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
