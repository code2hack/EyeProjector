package com.code2hack.eyeprojector.directhost.anchor.target

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Saved static metadata, independent of preferences and Click/Hold mode. No live node, ordinal,
 * window ID, display, session, drawing path or coordinate survives here. Resource ID, public
 * node uniqueId or a semantic-control label fingerprint identifies the selected control.
 * A named ancestor is context only; it never replaces the selected control.
 *
 * A null Activity means it was unobserved, not that the last launched Activity was current.
 * Matching then has package/current-window scope only; it cannot establish Activity identity.
 */
data class AnchorTargetDescriptor(
    val packageName: String,
    val activityName: String?,
    val windowType: Int,
    val resourceId: String?,
    val className: String?,
    val uniqueId: String? = null,
    val labelSource: String? = null,
    val labelFingerprint: String? = null,
    val ancestorResourceId: String? = null,
    val ancestorClassName: String? = null,
) {
    init {
        require(packageName.isNotBlank())
        require(resourceId != null || uniqueId != null || labelFingerprint != null)
        require(resourceId == null || resourceId.isNotBlank())
        require(uniqueId == null || uniqueId.isNotBlank())
        require((labelSource == null) == (labelFingerprint == null))
        require(labelSource == null || labelSource.isNotBlank())
        require(labelFingerprint == null || labelFingerprint.isNotBlank())
        require(ancestorResourceId == null || ancestorResourceId.isNotBlank())
        require(ancestorClassName == null || ancestorClassName.isNotBlank())
        require(activityName == null || activityName.isNotBlank())
        require(className == null || className.isNotBlank())
    }
}

/** Versioned value encoding. Storage ownership and activation mode belong to the caller. */
object AnchorTargetDescriptorCodec {
    fun encode(value: AnchorTargetDescriptor): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(2)
            out.text(value.packageName)
            out.optional(value.activityName)
            out.writeInt(value.windowType)
            out.optional(value.resourceId)
            out.optional(value.className)
            out.optional(value.uniqueId)
            out.optional(value.labelSource)
            out.optional(value.labelFingerprint)
            out.optional(value.ancestorResourceId)
            out.optional(value.ancestorClassName)
        }
    }.toByteArray()

    fun decode(payload: ByteArray): AnchorTargetDescriptor = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val version = input.readInt()
        require(version == 1 || version == 2) { "Unknown target descriptor version" }
        val value = if (version == 1)
            AnchorTargetDescriptor(input.text(), input.optional(), input.readInt(), input.text(), input.optional())
        else AnchorTargetDescriptor(input.text(), input.optional(), input.readInt(), input.optional(), input.optional(),
            input.optional(), input.optional(), input.optional(), input.optional(), input.optional())
        require(input.available() == 0) { "Trailing target descriptor bytes" }
        value
    }

    private fun DataOutputStream.text(value: String) {
        val encoded = value.toByteArray(UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.text(): String {
        val length = readInt()
        require(length >= 0 && length <= available()) { "Incomplete target descriptor string" }
        val encoded = ByteArray(length)
        readFully(encoded)
        return UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded)).toString()
    }

    private fun DataOutputStream.optional(value: String?) {
        writeBoolean(value != null)
        if (value != null) text(value)
    }

    private fun DataInputStream.optional(): String? = when (readUnsignedByte()) {
        0 -> null
        1 -> text()
        else -> throw IllegalArgumentException("Invalid target descriptor presence flag")
    }
}
