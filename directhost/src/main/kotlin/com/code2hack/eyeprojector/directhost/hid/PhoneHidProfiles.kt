package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.protocol.PhoneHidAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** One private record for every device. The old global preference is read-only migration input.
 * Profile handles outlive selection: an admitted writer always updates its original device.
 * Creation/loading belongs to the caller's setup lane; mutations use the single bindings writer.
 */
internal class PhoneHidProfiles(
    read: () -> String?, readLegacy: () -> String?, private val write: (String) -> Boolean,
    private val profileFor: (PhoneHidDeviceIdentity) -> String,
) {
    @Volatile private var values: Map<String, Map<PhoneHidAction, PhoneHidBinding>> = emptyMap()
    private val handles = mutableMapOf<String, PhoneHidBindings>()
    @Volatile var error: String? = null; private set
    private val unselected = PhoneHidBindings({ null }, { false })

    init {
        try {
            val record = read()
            if (record != null) values = decode(record)
            else readLegacy()?.let { legacy ->
                // Group by each recorded identity, never by whichever device is pinned now.
                values = PhoneHidBindings.decode(legacy).entries.groupBy { profileFor(it.value.button.device) }
                    .mapValues { (_, entries) -> entries.associate { it.key to it.value } }
                if (!store(values)) error = "Migrated HID profiles could not be saved. Legacy bindings are retained; retry an assignment or reset this profile."
            }
        } catch (_: Exception) {
            error = "Saved HID profiles could not be read. The original records are retained."
        }
    }

    /** Main-lane selection creates a stable handle; its write closure runs only on the writer. */
    fun bindings(profile: String?): PhoneHidBindings {
        if (profile == null) return unselected
        return handles.getOrPut(profile) {
            PhoneHidBindings({ PhoneHidBindings.encode(values[profile].orEmpty()) }) { record ->
                val next = values + (profile to PhoneHidBindings.decode(record))
                require(next.getValue(profile).values.all { profileFor(it.button.device) == profile })
                if (!store(next)) {
                    error = "HID profiles could not be saved. Try again."
                    false
                } else {
                    values = next
                    error = null
                    true
                }
            }
        }
    }

    private fun store(next: Map<String, Map<PhoneHidAction, PhoneHidBinding>>) =
        try { write(encode(next)) } catch (_: Exception) { false }

    private fun encode(profiles: Map<String, Map<PhoneHidAction, PhoneHidBinding>>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(profiles.size)
            profiles.toSortedMap().forEach { (key, entries) ->
                out.string(key); out.string(PhoneHidBindings.encode(entries))
            }
        }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decode(record: String): Map<String, Map<PhoneHidAction, PhoneHidBinding>> {
        require(record.startsWith(PREFIX))
        val bytes = Base64.getDecoder().decode(record.removePrefix(PREFIX))
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()
            require(count >= 0 && count <= input.available() / 8)
            val result = linkedMapOf<String, Map<PhoneHidAction, PhoneHidBinding>>()
            repeat(count) {
                val key = input.string()
                val entries = PhoneHidBindings.decode(input.string())
                require(key !in result && entries.values.all { profileFor(it.button.device) == key })
                result[key] = entries
            }
            require(input.available() == 0)
            result
        }
    }

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.string(): String {
        val size = readInt()
        require(size >= 0 && size <= available())
        val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private companion object { const val PREFIX = "eyeprojector.hid-profiles.1:" }
}
