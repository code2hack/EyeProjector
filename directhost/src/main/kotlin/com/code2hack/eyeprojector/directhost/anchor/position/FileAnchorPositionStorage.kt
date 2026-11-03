package com.code2hack.eyeprojector.directhost.anchor.position

import com.code2hack.eyeprojector.protocol.AnchorPosition
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Same-directory atomic replacement, using the existing preference storage convention. */
class FileAnchorPositionStorage(private val directory: File) : AnchorPositionStorage {
    override fun read(packageName: String): List<AnchorPosition> {
        val file = file(packageName)
        if (!file.exists()) return emptyList()
        val value = try { AnchorPositionCodec.decode(file.readBytes()) }
        catch (failure: IllegalArgumentException) { throw IOException("Unreadable coordinate Anchor record", failure) }
        if (value.packageName != packageName) throw IOException("Coordinate Anchor record package mismatch")
        return value.positions
    }

    override fun write(packageName: String, positions: List<AnchorPosition>) {
        // Encode before creating any file; a failed encoding leaves the previous record intact.
        val payload = AnchorPositionCodec.encode(AppAnchorPositions(packageName, positions))
        Files.createDirectories(directory.toPath())
        val pending = Files.createTempFile(directory.toPath(), "positions-", ".pending")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            Files.move(pending, file(packageName).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending) }
    }

    private fun file(packageName: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.positions")
    }
}
