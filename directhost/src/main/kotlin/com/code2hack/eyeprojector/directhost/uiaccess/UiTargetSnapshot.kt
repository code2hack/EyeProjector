package com.code2hack.eyeprojector.directhost.uiaccess

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.protocol.Geometry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Screen coordinates on the source display, including the window's existing inset/IME offset. */
data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val hasArea: Boolean get() = right > left && bottom > top
}

/** Supplied by the captured managed projection, never inferred from the physical/default display. */
data class ManagedUiSource(
    val context: ProjectionContext,
    val displayGeneration: String,
    val displayId: Int,
    val displayUniqueId: String?,
    val foregroundPackage: String?,
    /** An actual current Activity observation, when available; never the last launch result. */
    val foregroundActivity: String?,
    val geometry: UiSourceGeometry,
) {
    fun bytes(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(1)
            out.writeUTF(context.ownerEpoch); out.writeUTF(context.projectionToken)
            out.writeUTF(displayGeneration); out.writeInt(displayId)
            out.nullable(displayUniqueId); out.nullable(foregroundPackage); out.nullable(foregroundActivity)
            out.writeInt(geometry.logical.width); out.writeInt(geometry.logical.height)
            out.writeInt(geometry.physical.width); out.writeInt(geometry.physical.height)
            out.writeInt(geometry.densityDpi); out.writeInt(geometry.rotation); out.writeBoolean(geometry.scalingDisabled)
            out.writeInt(geometry.video.width); out.writeInt(geometry.video.height)
        }
    }.toByteArray()

    companion object {
        fun from(payload: ByteArray): ManagedUiSource = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 1)
            val value = ManagedUiSource(ProjectionContext(input.readUTF(), input.readUTF()), input.readUTF(),
                input.readInt(), input.nullable(), input.nullable(), input.nullable(),
                UiSourceGeometry(Geometry(input.readInt(), input.readInt()), Geometry(input.readInt(), input.readInt()),
                    input.readInt(), input.readInt(), input.readBoolean(), Geometry(input.readInt(), input.readInt())))
            require(input.available() == 0)
            value
        }

        private fun DataOutputStream.nullable(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
        private fun DataInputStream.nullable(): String? = if (readBoolean()) readUTF() else null
    }
}

/** Copied under the managed display's existing input lock; contains no live display/window handles. */
data class ManagedUiMapping(val displayId: Int, val displayUniqueId: String?, val geometry: UiSourceGeometry)

/** A null window ID enumerates this source's windows without choosing one; nodes use selectedPackage. */
data class UiTargetRequest(val source: ManagedUiSource, val selectedPackage: String, val windowId: Int? = null)

data class UiWindowMetadata(
    val displayId: Int,
    val windowId: Int,
    val type: Int,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: UiBounds,
)

/** Values only. The ordinal is local to one observation, and resource IDs may be null or repeated. */
data class UiTargetObservation(
    val ordinal: Int,
    val windowId: Int,
    val packageName: String,
    val resourceId: String?,
    val className: String?,
    val bounds: UiBounds,
    val actionIds: List<Int>,
    val clickable: Boolean,
    val longClickable: Boolean,
    val visible: Boolean,
    val enabled: Boolean,
)

enum class UiWindowReadStatus { READ, OTHER_PACKAGE, NO_ROOT, WINDOW_MISMATCH, FAILED }
data class UiWindowSnapshot(
    val window: UiWindowMetadata,
    val status: UiWindowReadStatus,
    val targets: List<UiTargetObservation> = emptyList(),
    val failureClass: String? = null,
)

enum class UiTargetReadStatus { READ, ACCESS_REQUIRED, SOURCE_UNAVAILABLE, SOURCE_CHANGED, DISPLAY_ABSENT, WINDOW_ABSENT, FAILED }
data class UiTargetSnapshot(
    val request: UiTargetRequest?,
    val status: UiTargetReadStatus,
    val startedUptimeMs: Long,
    val finishedUptimeMs: Long,
    val windows: List<UiWindowSnapshot> = emptyList(),
    val failureClass: String? = null,
)
