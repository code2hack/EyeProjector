package com.code2hack.eyeprojector.directrg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal enum class HidAction { TAP, SCROLL_DOWN, SCROLL_UP, FUNCTION }

/** Actual InputDevice facts. Android's transient device ID is deliberately not persisted. */
internal data class HidDeviceIdentity(val descriptor: String, val vendorId: Int, val productId: Int)

internal data class HidButton(val device: HidDeviceIdentity, val keyCode: Int, val scanCode: Int)

/** Names are display metadata; they do not participate in physical button matching. */
internal data class HidBinding(val button: HidButton, val deviceName: String, val keyLabel: String)

/**
 * All assignments occupy one versioned record, so moving a button is one persistence operation.
 * Empty descriptors are retained as reported by Android; two such peripherals with identical
 * vendor/product/key/scan facts cannot be distinguished by this identity and share an assignment.
 * A malformed record loads no assignments, reports an error and stays untouched until assignment or reset.
 */
internal class HidBindings(read: () -> String?, private val write: (String) -> Boolean) {
    private var bindings: Map<HidAction, HidBinding> = emptyMap()
    var error: String? = null
        private set

    init {
        try {
            read()?.let { bindings = decode(it) }
        } catch (_: Exception) {
            error = "Saved HID bindings could not be read. Assign a button or reset all bindings to replace them."
        }
    }

    fun binding(action: HidAction): HidBinding? = bindings[action]

    fun action(button: HidButton): HidAction? = bindings.entries.firstOrNull { it.value.button == button }?.key

    fun assign(action: HidAction, binding: HidBinding): Boolean {
        val next = bindings.filterValues { it.button != binding.button }.toMutableMap()
        next[action] = binding
        return save(next)
    }

    fun resetAll(): Boolean = save(emptyMap())

    /** Remove only exact observed device identities; an unrelated observation never rewrites storage. */
    fun clearDevices(devices: Set<HidDeviceIdentity>): Boolean {
        val next = bindings.filterValues { it.button.device !in devices }
        if (next.size == bindings.size) return true
        return save(next)
    }

    private fun save(next: Map<HidAction, HidBinding>): Boolean {
        val saved = try {
            write(encode(next))
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            error = "HID bindings could not be saved. Try again."
            return false
        }
        bindings = next
        error = null
        return true
    }

    private fun encode(values: Map<HidAction, HidBinding>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(values.size)
            for (action in HidAction.entries) {
                val binding = values[action] ?: continue
                output.writeString(action.name)
                output.writeString(binding.button.device.descriptor)
                output.writeInt(binding.button.device.vendorId)
                output.writeInt(binding.button.device.productId)
                output.writeInt(binding.button.keyCode)
                output.writeInt(binding.button.scanCode)
                output.writeString(binding.deviceName)
                output.writeString(binding.keyLabel)
            }
        }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decode(record: String): Map<HidAction, HidBinding> {
        require(record.startsWith(PREFIX))
        val bytes = Base64.getDecoder().decode(record.substring(PREFIX.length))
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()
            require(count in 0..HidAction.entries.size)
            val result = mutableMapOf<HidAction, HidBinding>()
            repeat(count) {
                val action = HidAction.valueOf(input.readString())
                val device = HidDeviceIdentity(input.readString(), input.readInt(), input.readInt())
                val button = HidButton(device, input.readInt(), input.readInt())
                val binding = HidBinding(button, input.readString(), input.readString())
                require(action !in result && result.values.none { it.button == button })
                result[action] = binding
            }
            require(input.available() == 0)
            result
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size >= 0 && size <= available())
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private companion object {
        const val PREFIX = "eyeprojector.hid-bindings.1:"
    }
}
