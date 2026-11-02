package com.code2hack.eyeprojector.directhost.anchor.preferences

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Private per-package complete records, following the existing activation/session atomic-write route. */
class FileAppAnchorStorage(private val directory: File) : AppAnchorStorage {
    override fun read(packageName: String): AppAnchor? {
        val file = file(packageName)
        if (!file.exists()) return null
        return try { AppAnchorCodec.decode(file.readBytes()) }
        catch (failure: IllegalArgumentException) { throw IOException("Unreadable Anchor preference", failure) }
    }

    override fun write(packageName: String, anchor: AppAnchor?) {
        val file = file(packageName)
        if (anchor == null) {
            Files.deleteIfExists(file.toPath())
            return
        }
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), "anchor-", ".pending")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(AppAnchorCodec.encode(anchor))
                stream.fd.sync()
            }
            Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun file(packageName: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.anchor")
    }
}
