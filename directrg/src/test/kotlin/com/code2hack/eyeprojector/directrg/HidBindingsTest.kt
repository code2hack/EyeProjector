package com.code2hack.eyeprojector.directrg

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class HidBindingsTest {
    private val device = HidDeviceIdentity("android:actual-device-descriptor", 123, 456)
    private fun binding(keyCode: Int = 23, scanCode: Int = 28, identity: HidDeviceIdentity = device) =
        HidBinding(HidButton(identity, keyCode, scanCode), "Peripheral", "Button $keyCode")

    private class Storage(var record: String? = null) {
        var writes = 0
        var writable = true
        fun open() = HidBindings({ record }) { value ->
            writes++
            if (writable) { record = value; true } else false
        }
    }

    @Test fun unconfiguredStoreHasNoDefaultAssignmentsAndDoesNotWrite() {
        val storage = Storage()
        val model = storage.open()
        HidAction.entries.forEach { assertNull(model.binding(it)) }
        assertNull(model.action(binding().button))
        assertNull(model.error)
        assertEquals(0, storage.writes)
    }

    @Test fun everyActionAndActualMetadataSurviveRestartInOneRecord() {
        val storage = Storage()
        val first = storage.open()
        val expected = HidAction.entries.associateWith { action ->
            binding(action.ordinal + 20, action.ordinal + 80).copy(
                deviceName = "Remote \uD83D\uDD18\t\n\u0000",
                keyLabel = "Key ${action.name}:日本語",
            )
        }
        expected.forEach { (action, value) -> assertTrue(first.assign(action, value)) }
        val loaded = storage.open()
        expected.forEach { (action, value) ->
            assertEquals(value, loaded.binding(action))
            assertEquals(action, loaded.action(value.button))
        }
        assertNull(loaded.error)
        assertEquals(4, storage.writes)
    }

    @Test fun assigningAnAlreadyBoundPhysicalButtonMovesItAndReplacesTheTarget() {
        val storage = Storage()
        val model = storage.open()
        val first = binding()
        val second = binding(24, 29)
        assertTrue(model.assign(HidAction.TAP, first))
        assertTrue(model.assign(HidAction.FUNCTION, second))
        assertTrue(model.assign(HidAction.FUNCTION, first.copy(deviceName = "Renamed peripheral")))
        val loaded = storage.open()
        assertNull(loaded.binding(HidAction.TAP))
        assertEquals(HidAction.FUNCTION, loaded.action(first.button))
        assertNull(loaded.action(second.button))
        assertEquals("Renamed peripheral", loaded.binding(HidAction.FUNCTION)?.deviceName)
    }

    @Test fun identicalKeyCodesOnDifferentDevicesAndDifferentScanCodesStaySeparate() {
        val model = Storage().open()
        val values = listOf(
            binding(),
            binding(identity = device.copy(descriptor = "another actual descriptor")),
            binding(identity = device.copy(productId = 457)),
            binding(scanCode = 29),
        )
        HidAction.entries.zip(values).forEach { (action, value) -> assertTrue(model.assign(action, value)) }
        HidAction.entries.zip(values).forEach { (action, value) -> assertEquals(action, model.action(value.button)) }
        assertNull(model.action(binding(keyCode = 24).button))
        assertNull(model.action(binding(identity = device.copy(vendorId = 124)).button))
    }

    @Test fun reconnectUsesPersistentDeviceFactsWithoutARuntimeDeviceIdOrName() {
        val storage = Storage()
        val captured = binding()
        assertTrue(storage.open().assign(HidAction.TAP, captured))
        // Reconstruct the identity as a fresh InputDevice observation after reconnect/restart.
        val reconnected = HidButton(HidDeviceIdentity(device.descriptor, device.vendorId, device.productId), 23, 28)
        assertEquals(HidAction.TAP, storage.open().action(reconnected))
    }

    @Test fun absentDescriptorIsPreservedAndItsAmbiguityIsNotHiddenByAGuessedIdentity() {
        val storage = Storage()
        val captured = binding(identity = HidDeviceIdentity("", 0, 0))
        assertTrue(storage.open().assign(HidAction.SCROLL_DOWN, captured))
        val loaded = storage.open()
        assertEquals("", loaded.binding(HidAction.SCROLL_DOWN)?.button?.device?.descriptor)
        assertEquals(HidAction.SCROLL_DOWN, loaded.action(captured.button.copy()))
    }

    @Test fun aFailedMoveRetainsBothEffectiveAssignmentsAndThePersistedRecordUntilRetry() {
        val storage = Storage()
        val model = storage.open()
        val first = binding()
        val second = binding(24, 29)
        assertTrue(model.assign(HidAction.TAP, first))
        assertTrue(model.assign(HidAction.FUNCTION, second))
        val saved = storage.record
        storage.writable = false
        assertFalse(model.assign(HidAction.FUNCTION, first))
        assertNotNull(model.error)
        assertEquals(saved, storage.record)
        assertEquals(first, model.binding(HidAction.TAP))
        assertEquals(second, model.binding(HidAction.FUNCTION))
        assertEquals(first, storage.open().binding(HidAction.TAP))
        storage.writable = true
        assertTrue(model.assign(HidAction.FUNCTION, first))
        assertNull(model.error)
        assertNull(storage.open().binding(HidAction.TAP))
        assertEquals(first, storage.open().binding(HidAction.FUNCTION))
    }

    @Test fun aThrowingPersistenceOperationAlsoRetainsEffectiveAssignments() {
        var throws = false
        val model = HidBindings({ null }) { if (throws) error("Storage unavailable") else true }
        assertTrue(model.assign(HidAction.TAP, binding()))
        throws = true
        assertFalse(model.assign(HidAction.FUNCTION, binding()))
        assertEquals(binding(), model.binding(HidAction.TAP))
        assertNull(model.binding(HidAction.FUNCTION))
        assertNotNull(model.error)
    }

    @Test fun corruptRecordsLoadNoPartialAssignmentsAndAreNotSilentlyOverwritten() {
        val valid = wireRecord(listOf("TAP" to binding()))
        val duplicateAction = wireRecord(listOf("TAP" to binding(), "TAP" to binding(24)))
        val duplicateButton = wireRecord(listOf("TAP" to binding(), "FUNCTION" to binding()))
        val payload = Base64.getDecoder().decode(valid.substringAfter(':'))
        val records = listOf(
            "",
            "eyeprojector.hid-bindings.2:" + valid.substringAfter(':'),
            "eyeprojector.hid-bindings.1:not base64!",
            "eyeprojector.hid-bindings.1:" + Base64.getEncoder().encodeToString(payload.copyOf(payload.size - 1)),
            "eyeprojector.hid-bindings.1:" + Base64.getEncoder().encodeToString(payload + byteArrayOf(0)),
            duplicateAction,
            duplicateButton,
            wireRecord(listOf("GUESS_FROM_VENDOR" to binding())),
        )
        records.forEach { record ->
            val storage = Storage(record)
            val loaded = storage.open()
            assertNotNull(loaded.error)
            HidAction.entries.forEach { assertNull(loaded.binding(it)) }
            assertEquals(record, storage.record)
            assertEquals(0, storage.writes)
            assertTrue(loaded.assign(HidAction.TAP, binding()))
            assertNull(loaded.error)
            assertEquals(binding(), storage.open().binding(HidAction.TAP))
        }
    }

    @Test fun throwingReadReportsUnavailableBindingsWithoutWriting() {
        var writes = 0
        val model = HidBindings({ error("Storage unavailable") }) { writes++; true }
        assertNotNull(model.error)
        assertNull(model.binding(HidAction.TAP))
        assertEquals(0, writes)
    }

    private fun wireRecord(entries: List<Pair<String, HidBinding>>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun string(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            output.writeInt(entries.size)
            entries.forEach { (action, binding) ->
                string(action)
                string(binding.button.device.descriptor)
                output.writeInt(binding.button.device.vendorId)
                output.writeInt(binding.button.device.productId)
                output.writeInt(binding.button.keyCode)
                output.writeInt(binding.button.scanCode)
                string(binding.deviceName)
                string(binding.keyLabel)
            }
        }
        return "eyeprojector.hid-bindings.1:" + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
