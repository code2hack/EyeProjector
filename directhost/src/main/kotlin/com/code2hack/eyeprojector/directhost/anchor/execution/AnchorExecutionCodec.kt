package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.uiaccess.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Local typed Binder values. No saved file path, live node, key or arbitrary input command. */
internal object AnchorExecutionCodec {
    fun setting(value: AnchorSettingContext): ByteArray = encode {
        writeUTF(value.instanceId); writeUTF(value.packageName); writeLong(value.revision)
    }
    fun setting(payload: ByteArray): AnchorSettingContext = decode(payload) {
        AnchorSettingContext(readUTF(), readUTF(), readLong())
    }

    fun target(value: ResolvedAnchorTarget): ByteArray = encode {
        val source = value.source.bytes(); writeInt(source.size); write(source)
        val window = value.window
        writeInt(window.displayId); writeInt(window.windowId); writeInt(window.type); writeInt(window.layer)
        writeBoolean(window.active); writeBoolean(window.focused); bounds(window.bounds)
        val target = value.target
        writeInt(target.ordinal); writeInt(target.windowId); writeUTF(target.packageName)
        optional(target.resourceId); optional(target.className); bounds(target.bounds)
        writeBoolean(target.clickable); writeBoolean(target.longClickable)
        writeBoolean(target.visible); writeBoolean(target.enabled)
    }
    fun target(payload: ByteArray): ResolvedAnchorTarget = decode(payload) {
        val size = readInt(); require(size in 0..available())
        val source = ManagedUiSource.from(ByteArray(size).also(::readFully))
        val window = UiWindowMetadata(readInt(), readInt(), readInt(), readInt(), readBoolean(), readBoolean(), bounds())
        val target = UiTargetObservation(readInt(), readInt(), readUTF(), optional(), optional(), bounds(), emptyList(),
            readBoolean(), readBoolean(), readBoolean(), readBoolean())
        ResolvedAnchorTarget(source, window, target)
    }

    private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use { out -> out.writeInt(1); block(out) }
    }.toByteArray()
    private fun <T> decode(payload: ByteArray, block: DataInputStream.() -> T): T = DataInputStream(payload.inputStream()).use {
        require(it.readInt() == 1)
        block(it).also { _ -> require(it.available() == 0) }
    }
    private fun DataOutputStream.bounds(value: UiBounds) {
        writeInt(value.left); writeInt(value.top); writeInt(value.right); writeInt(value.bottom)
    }
    private fun DataInputStream.bounds() = UiBounds(readInt(), readInt(), readInt(), readInt())
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
}
