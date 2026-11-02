package com.code2hack.eyeprojector.directhost.anchor.preferences

import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptorCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Always supplied by the user's completed choice; Android action metadata selects no default. */
enum class AnchorMode { CLICK, HOLD }

/** One complete package-level preference. Activity belongs to target matching, not the storage key. */
data class AppAnchor(val descriptor: AnchorTargetDescriptor, val mode: AnchorMode) {
    val packageName: String get() = descriptor.packageName
}

/** Descriptor and mode travel in one versioned value; there is no partially saved registration. */
object AppAnchorCodec {
    fun encode(anchor: AppAnchor): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(1)
            output.writeUTF(anchor.mode.name)
            output.write(AnchorTargetDescriptorCodec.encode(anchor.descriptor))
        }
    }.toByteArray()

    fun decode(payload: ByteArray): AppAnchor = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readInt() == 1) { "Unknown Anchor preference version" }
        val mode = AnchorMode.valueOf(input.readUTF())
        AppAnchor(AnchorTargetDescriptorCodec.decode(input.readBytes()), mode)
    }
}
