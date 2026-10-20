package com.code2hack.eyeprojector.directhost

import java.io.File
import java.nio.file.Files

/** A fixed per-installed-app root lets a replacement service revoke its predecessor's copies. */
internal class OwnedShellSessions(private val root: File) {
    fun create(makePrivate: (File) -> Unit): File {
        check(revoke()) { "Could not remove the preceding owned session" }
        check(root.mkdirs()) { "Could not create the owned session root" }
        makePrivate(root)
        return Files.createTempDirectory(root.toPath(), "session-").toFile().also(makePrivate)
    }

    fun revoke(): Boolean = !root.exists() || root.deleteRecursively()
    fun isRevoked(): Boolean = !root.exists()
}
