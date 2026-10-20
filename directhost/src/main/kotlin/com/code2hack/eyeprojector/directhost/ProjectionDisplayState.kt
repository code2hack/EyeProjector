package com.code2hack.eyeprojector.directhost

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class ProjectionLaunchStatus { UNAVAILABLE, READY, STARTED, FAILED, TIMED_OUT, CLOSED }

/** Local Binder result. STARTED is an am result; only [foregroundPackage] is a display observation. */
data class ProjectionDisplayState(
    val requestId: Long = 0,
    val generation: String = "",
    val displayId: Int? = null,
    val requestedPackage: String? = null,
    val status: ProjectionLaunchStatus = ProjectionLaunchStatus.UNAVAILABLE,
    val commandExit: Int? = null,
    val launchedComponent: String? = null,
    val foregroundPackage: String? = null,
) {
    fun bytes(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use {
            it.writeInt(1); it.writeLong(requestId); it.writeUTF(generation)
            it.writeInt(displayId ?: -1); it.writeUTF(requestedPackage.orEmpty())
            it.writeUTF(status.name); it.writeInt(commandExit ?: Int.MIN_VALUE)
            it.writeUTF(launchedComponent.orEmpty()); it.writeUTF(foregroundPackage.orEmpty())
        }
    }.toByteArray()

    companion object {
        fun from(payload: ByteArray): ProjectionDisplayState = DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readInt() == 1)
            val state = ProjectionDisplayState(it.readLong(), it.readUTF(), it.readInt().takeIf { id -> id >= 0 },
                it.readUTF().ifEmpty { null }, ProjectionLaunchStatus.valueOf(it.readUTF()),
                it.readInt().takeIf { code -> code != Int.MIN_VALUE }, it.readUTF().ifEmpty { null }, it.readUTF().ifEmpty { null })
            require(it.available() == 0)
            state
        }
    }
}
