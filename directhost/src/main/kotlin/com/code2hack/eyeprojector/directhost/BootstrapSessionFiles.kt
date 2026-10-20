package com.code2hack.eyeprojector.directhost

import java.io.File

/** Fixed private-session payload. The Phone bind endpoint is never inferred from its peer. */
internal data class BootstrapSessionFiles(
    val identity: ByteArray,
    val password: ByteArray,
    val legacyPeer: ByteArray?,
    val endpoint: ByteArray,
    val pairedPeer: ByteArray?,
) {
    fun copyInto(directory: File, makePrivate: (File) -> Unit) {
        val trust = if (pairedPeer != null) "paired-peer.properties" to pairedPeer else "peer.crt" to requireNotNull(legacyPeer)
        listOf("identity.p12" to identity, "password" to password, "endpoint.properties" to endpoint, trust)
            .forEach { (name, bytes) -> File(directory, name).apply { writeBytes(bytes); makePrivate(this) } }
    }

    companion object {
        fun read(directory: File): BootstrapSessionFiles? {
            val identity = File(directory, "identity.p12")
            val password = File(directory, "password")
            val endpoint = File(directory, "endpoint.properties")
            val paired = File(directory, "paired-peer.properties")
            val legacy = File(directory, "peer.crt")
            if (!identity.isFile || !password.isFile || !endpoint.isFile || (!paired.isFile && !legacy.isFile)) return null
            return BootstrapSessionFiles(identity.readBytes(), password.readBytes(),
                if (paired.isFile) null else legacy.readBytes(), endpoint.readBytes(),
                if (paired.isFile) paired.readBytes() else null)
        }
    }
}
