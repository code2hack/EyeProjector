package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.protocol.PhoneHidAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Actual InputDevice facts. Android's transient device ID is deliberately not persisted. */
internal data class PhoneHidDeviceIdentity(val descriptor: String, val vendorId: Int, val productId: Int)

internal data class PhoneHidButton(val device: PhoneHidDeviceIdentity, val keyCode: Int, val scanCode: Int)

/** Names are display metadata; they do not participate in physical button matching. */
internal data class PhoneHidBinding(val button: PhoneHidButton, val deviceName: String, val keyLabel: String)

/**
 * Phone-private assignments occupy one versioned record, so moving a button is one persistence operation.
 * Empty descriptors are retained as reported by Android; two such peripherals with identical
 * vendor/product/key/scan facts cannot be distinguished by this identity and share an assignment.
 * A malformed record loads no assignments, reports an error and stays untouched until assignment or reset.
 * Assign/reset calls belong to one persistence executor. Readers see published snapshots without
 * taking a lock across the persistence callback.
 */
internal class PhoneHidBindings(read: () -> String?, private val write: (String) -> Boolean) {
    @Volatile
    private var bindings: Map<PhoneHidAction, PhoneHidBinding> = emptyMap()
    @Volatile
    var error: String? = null
        private set

    init {
        try {
            read()?.let { bindings = decode(it) }
        } catch (_: Exception) {
            error = "Saved HID bindings could not be read. Assign a button or reset all bindings to replace them."
        }
    }

    fun binding(action: PhoneHidAction): PhoneHidBinding? = bindings[action]

    fun action(button: PhoneHidButton): PhoneHidAction? = bindings.entries.firstOrNull { it.value.button == button }?.key

    fun assign(action: PhoneHidAction, binding: PhoneHidBinding): Boolean {
        val next = bindings.filterValues { it.button != binding.button }.toMutableMap()
        next[action] = binding
        return save(next)
    }

    fun resetAll(): Boolean = save(emptyMap())

    private fun save(next: Map<PhoneHidAction, PhoneHidBinding>): Boolean {
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

    private fun encode(values: Map<PhoneHidAction, PhoneHidBinding>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(values.size)
            for (action in PhoneHidAction.entries) {
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

    private fun decode(record: String): Map<PhoneHidAction, PhoneHidBinding> {
        require(record.startsWith(PREFIX))
        val bytes = Base64.getDecoder().decode(record.substring(PREFIX.length))
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()
            require(count in 0..PhoneHidAction.entries.size)
            val result = mutableMapOf<PhoneHidAction, PhoneHidBinding>()
            repeat(count) {
                val action = PhoneHidAction.valueOf(input.readString())
                val device = PhoneHidDeviceIdentity(input.readString(), input.readInt(), input.readInt())
                val button = PhoneHidButton(device, input.readInt(), input.readInt())
                val binding = PhoneHidBinding(button, input.readString(), input.readString())
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
