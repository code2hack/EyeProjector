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

    @Test fun clearingEachActionPreservesOtherAssignmentsAndReloadsTheClearedRecord() {
        for (cleared in HidAction.entries) {
            val storage = Storage()
            val model = storage.open()
            val original = HidAction.entries.associateWith { binding(20 + it.ordinal, 80 + it.ordinal) }
            original.forEach { (action, value) -> assertTrue(model.assign(action, value)) }
            assertTrue(model.clear(cleared))
            assertEquals(5, storage.writes)
            assertTrue(storage.record!!.startsWith("eyeprojector.hid-bindings.1:"))
            val loaded = storage.open()
            for ((action, value) in original) {
                if (action == cleared) {
                    assertNull(model.binding(action))
                    assertNull(loaded.binding(action))
                    assertNull(loaded.action(value.button))
                } else {
                    assertEquals(value, model.binding(action))
                    assertEquals(value, loaded.binding(action))
                    assertEquals(action, loaded.action(value.button))
                }
            }
            assertNull(model.error)
            assertNull(loaded.error)
        }
    }

    @Test fun clearingTheLastAssignmentPersistsAnEmptyRecordAndAllowsFreshReassignment() {
        val storage = Storage()
        val model = storage.open()
        val physical = binding()
        assertTrue(model.assign(HidAction.TAP, physical))
        assertTrue(model.clear(HidAction.TAP))
        val empty = storage.record
        val cleared = storage.open()
        HidAction.entries.forEach { assertNull(cleared.binding(it)) }
        assertNull(cleared.error)
        assertTrue(cleared.assign(HidAction.FUNCTION, physical))
        assertNotEquals(empty, storage.record)
        val reassigned = storage.open()
        assertNull(reassigned.binding(HidAction.TAP))
        assertEquals(physical, reassigned.binding(HidAction.FUNCTION))
        assertEquals(HidAction.FUNCTION, reassigned.action(physical.button))
    }

    @Test fun failedClearRetainsTheVisibleAndPersistedAssignmentUntilSuccessfulRetry() {
        val storage = Storage()
        val model = storage.open()
        val tap = binding()
        val function = binding(24, 29)
        assertTrue(model.assign(HidAction.TAP, tap))
        assertTrue(model.assign(HidAction.FUNCTION, function))
        val saved = storage.record
        storage.writable = false
        assertFalse(model.clear(HidAction.TAP))
        assertEquals(tap, model.binding(HidAction.TAP))
        assertEquals(function, model.binding(HidAction.FUNCTION))
        assertEquals(saved, storage.record)
        assertEquals(tap, storage.open().binding(HidAction.TAP))
        assertNotNull(model.error)
        storage.writable = true
        assertTrue(model.clear(HidAction.TAP))
        assertNull(model.binding(HidAction.TAP))
        assertNull(storage.open().binding(HidAction.TAP))
        assertEquals(function, storage.open().binding(HidAction.FUNCTION))
        assertNull(model.error)
        assertEquals(4, storage.writes)
    }

    @Test fun targetedLegacyNativeCleanupRetainsAllThreeJoyConBindingsAndTheirMetadata() {
        // Host fixture from the recorded v1 assignments; this is no physical-device verification.
        val joyCon = HidDeviceIdentity("970d9eedc8314011eae0438faa2afda1d6b2b5c5", 1406, 8199)
        val native = HidDeviceIdentity("e2d539294d58ad2f9f9b376dfde7ee6310670d2b", 0, 0)
        val retained = linkedMapOf(
            HidAction.TAP to HidBinding(HidButton(joyCon, 103, 311),
                "Nintendo Switch Right Joy-Con", "KEYCODE_BUTTON_R1"),
            HidAction.SCROLL_DOWN to HidBinding(HidButton(joyCon, 96, 304),
                "Nintendo Switch Right Joy-Con", "KEYCODE_BUTTON_A"),
            HidAction.SCROLL_UP to HidBinding(HidButton(joyCon, 99, 307),
                "Nintendo Switch Right Joy-Con", "KEYCODE_BUTTON_X"),
        )
        val legacy = HidBinding(HidButton(native, 83, 204), "ROKID,PSOC-TP-R", "KEYCODE_NOTIFICATION")
        val storage = Storage(wireRecord(retained.map { it.key.name to it.value } + ("FUNCTION" to legacy)))
        val model = storage.open()
        assertEquals(legacy, model.binding(HidAction.FUNCTION))
        assertTrue(model.clearDevices(setOf(native)))
        assertEquals(1, storage.writes)
        val loaded = storage.open()
        assertNull(model.binding(HidAction.FUNCTION))
        assertNull(loaded.binding(HidAction.FUNCTION))
        assertNull(loaded.action(legacy.button))
        retained.forEach { (action, value) ->
            assertEquals(value, model.binding(action))
            assertEquals(value, loaded.binding(action))
            assertEquals(action, loaded.action(value.button))
        }
        assertNull(model.error)
        assertNull(loaded.error)
    }

    @Test fun deviceCleanupRequiresEveryIdentityFactAndWritesAllMatchedRemovalsTogether() {
        val storage = Storage()
        val model = storage.open()
        val secondDevice = device.copy(descriptor = "second observed device")
        val retainedDevice = device.copy(descriptor = "retained external device")
        val first = binding()
        val second = binding(24, 29, secondDevice)
        val retained = binding(25, 30, retainedDevice)
        assertTrue(model.assign(HidAction.TAP, first))
        assertTrue(model.assign(HidAction.FUNCTION, second))
        assertTrue(model.assign(HidAction.SCROLL_UP, retained))
        val saved = storage.record
        assertTrue(model.clearDevices(setOf(device.copy(descriptor = "other descriptor"),
            device.copy(vendorId = 124), device.copy(productId = 457))))
        assertTrue(model.clearDevices(emptySet()))
        assertEquals(3, storage.writes)
        assertEquals(saved, storage.record)
        assertTrue(model.clearDevices(setOf(device, secondDevice)))
        assertEquals(4, storage.writes)
        val loaded = storage.open()
        assertNull(loaded.binding(HidAction.TAP))
        assertNull(loaded.binding(HidAction.FUNCTION))
        assertEquals(retained, loaded.binding(HidAction.SCROLL_UP))
    }

    @Test fun failedDeviceCleanupRetainsAllMappingsAndNoMatchDoesNotHideTheFailure() {
        val storage = Storage()
        val model = storage.open()
        val first = binding()
        val second = binding(24, 29)
        assertTrue(model.assign(HidAction.TAP, first))
        assertTrue(model.assign(HidAction.FUNCTION, second))
        val saved = storage.record
        storage.writable = false
        assertFalse(model.clearDevices(setOf(device)))
        val failure = model.error
        assertNotNull(failure)
        assertTrue(model.clearDevices(setOf(device.copy(descriptor = "unrelated device"))))
        assertEquals(failure, model.error)
        assertEquals(3, storage.writes)
        assertEquals(saved, storage.record)
        val loaded = storage.open()
        assertEquals(first, model.binding(HidAction.TAP))
        assertEquals(second, model.binding(HidAction.FUNCTION))
        assertEquals(first, loaded.binding(HidAction.TAP))
        assertEquals(second, loaded.binding(HidAction.FUNCTION))
        storage.writable = true
        assertTrue(model.clearDevices(setOf(device)))
        assertEquals(4, storage.writes)
        assertNull(model.error)
        HidAction.entries.forEach { assertNull(storage.open().binding(it)) }
    }

    @Test fun throwingClearOperationsRetainAssignmentsAndTheSavedRecord() {
        for (deviceCleanup in listOf(false, true)) {
            var record: String? = null
            var throws = false
            val model = HidBindings({ record }) {
                if (throws) error("Storage unavailable")
                record = it
                true
            }
            val value = binding()
            assertTrue(model.assign(HidAction.TAP, value))
            val saved = record
            throws = true
            val cleared = if (deviceCleanup) model.clearDevices(setOf(device)) else model.clear(HidAction.TAP)
            assertFalse(cleared)
            assertEquals(value, model.binding(HidAction.TAP))
            assertEquals(saved, record)
            assertEquals(value, HidBindings({ record }) { error("readback must not write") }.binding(HidAction.TAP))
            assertNotNull(model.error)
        }
    }

    @Test fun noMatchClearLeavesMalformedStorageAndItsReadErrorUntouched() {
        val storage = Storage("existing malformed HID record")
        val model = storage.open()
        val error = model.error
        assertNotNull(error)
        assertTrue(model.clear(HidAction.TAP))
        assertTrue(model.clearDevices(setOf(device)))
        assertEquals(error, model.error)
        assertEquals("existing malformed HID record", storage.record)
        assertEquals(0, storage.writes)
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
