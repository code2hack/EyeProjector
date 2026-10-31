package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.protocol.PhoneHidAction
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PhoneHidBindingsTest {
    private val device = PhoneHidDeviceIdentity("actual-phone-peripheral-descriptor", 123, 456)
    private fun binding(keyCode: Int = 23, scanCode: Int = 28, identity: PhoneHidDeviceIdentity = device) =
        PhoneHidBinding(PhoneHidButton(identity, keyCode, scanCode), "Peripheral", "Button $keyCode")

    private class Storage(var record: String? = null) {
        var writes = 0
        var writable = true
        var throwing = false
        fun open() = PhoneHidBindings({ record }) { value ->
            writes++
            if (throwing) error("Storage unavailable")
            if (writable) { record = value; true } else false
        }
    }

    @Test fun unconfiguredPhoneStoreHasNoDefaultAssignmentsAndDoesNotWrite() {
        val storage = Storage()
        val model = storage.open()
        PhoneHidAction.entries.forEach { assertNull(model.binding(it)) }
        assertNull(model.action(binding().button))
        assertNull(model.error)
        assertEquals(0, storage.writes)
    }

    @Test fun legacyFourRoleRecordKeepsFunctionAndLeavesNavigationUnassignedWithoutWriting() {
        val legacy = listOf("TAP", "SCROLL_DOWN", "SCROLL_UP", "FUNCTION").mapIndexed { id, name ->
            name to binding(20 + id, 80 + id)
        }
        val record = wireRecord(legacy)
        val storage = Storage(record)
        val loaded = storage.open()
        legacy.forEachIndexed { id, (name, value) ->
            val action = PhoneHidAction.valueOf(name)
            assertEquals(id, action.ordinal)
            assertEquals(value, loaded.binding(action))
            assertEquals(action, loaded.action(value.button))
        }
        assertNull(loaded.binding(PhoneHidAction.BACK_HOME))
        assertNull(loaded.binding(PhoneHidAction.LOCK))
        assertEquals(0, storage.writes)
        assertEquals(record, storage.record)
        assertNull(loaded.error)
    }

    @Test fun explicitlyLearningNavigationAfterLegacyReloadRetainsAllUnrelatedAssignments() {
        val legacy = PhoneHidAction.entries.take(4).mapIndexed { id, action ->
            action.name to binding(20 + id, 80 + id)
        }
        val storage = Storage(wireRecord(legacy))
        val first = storage.open()
        assertEquals(0, storage.writes)
        val backHome = binding(30, 90)
        val lock = binding(31, 91)
        assertTrue(first.assign(PhoneHidAction.BACK_HOME, backHome))
        val reconnect = storage.open()
        assertTrue(reconnect.assign(PhoneHidAction.LOCK, lock))
        val reopened = storage.open()
        legacy.forEach { (name, value) -> assertEquals(value, reopened.binding(PhoneHidAction.valueOf(name))) }
        assertEquals(backHome, reopened.binding(PhoneHidAction.BACK_HOME))
        assertEquals(lock, reopened.binding(PhoneHidAction.LOCK))
        assertEquals(2, storage.writes)
    }

    @Test fun allActionsAndActualMetadataSurviveRestartInOneVersionedRecord() {
        val storage = Storage()
        val model = storage.open()
        val expected = PhoneHidAction.entries.associateWith { action ->
            binding(action.ordinal + 20, action.ordinal + 80).copy(
                deviceName = "Peripheral \uD83D\uDD18\t\n\u0000",
                keyLabel = "${action.name}:日本語",
            )
        }
        expected.forEach { (action, value) -> assertTrue(model.assign(action, value)) }
        assertEquals(expected.size, storage.writes)
        assertEquals(wireRecord(expected.map { it.key.name to it.value }), storage.record)
        val loaded = storage.open()
        expected.forEach { (action, value) ->
            assertEquals(value, loaded.binding(action))
            assertEquals(action, loaded.action(value.button))
        }
        assertNull(loaded.error)
        assertEquals(expected.size, storage.writes)
    }

    @Test fun movingOnePhysicalKeyReplacesTheTargetInOneWriteAndPreservesOtherActions() {
        val storage = Storage()
        val model = storage.open()
        val tap = binding()
        val function = binding(24, 29)
        val scroll = binding(25, 30)
        assertTrue(model.assign(PhoneHidAction.TAP, tap))
        assertTrue(model.assign(PhoneHidAction.FUNCTION, function))
        assertTrue(model.assign(PhoneHidAction.SCROLL_DOWN, scroll))
        val renamed = tap.copy(deviceName = "Renamed peripheral", keyLabel = "Renamed button")
        assertTrue(model.assign(PhoneHidAction.FUNCTION, renamed))
        assertEquals(4, storage.writes)
        val loaded = storage.open()
        assertNull(loaded.binding(PhoneHidAction.TAP))
        assertNull(loaded.action(function.button))
        assertEquals(PhoneHidAction.FUNCTION, loaded.action(tap.button))
        assertEquals(renamed, loaded.binding(PhoneHidAction.FUNCTION))
        assertEquals(scroll, loaded.binding(PhoneHidAction.SCROLL_DOWN))
    }

    @Test fun matchingRequiresEveryActualDeviceAndKeyFactButNoRuntimeAttachmentOrDisplayName() {
        val storage = Storage()
        val value = binding()
        assertTrue(storage.open().assign(PhoneHidAction.TAP, value))
        val loaded = storage.open()
        val observedAgain = PhoneHidButton(PhoneHidDeviceIdentity(device.descriptor, device.vendorId, device.productId), 23, 28)
        assertEquals(PhoneHidAction.TAP, loaded.action(observedAgain))
        val different = listOf(
            observedAgain.copy(device = device.copy(descriptor = "another descriptor")),
            observedAgain.copy(device = device.copy(vendorId = 124)),
            observedAgain.copy(device = device.copy(productId = 457)),
            observedAgain.copy(keyCode = 24),
            observedAgain.copy(scanCode = 29),
        )
        different.forEach { assertNull(loaded.action(it)) }
        assertTrue(loaded.assign(PhoneHidAction.FUNCTION, value.copy(deviceName = "New display name")))
        assertNull(loaded.binding(PhoneHidAction.TAP))
        assertEquals(PhoneHidAction.FUNCTION, loaded.action(observedAgain))
    }

    @Test fun absentDescriptorIsRetainedWithoutInventingAnIdentity() {
        val storage = Storage()
        val value = binding(identity = PhoneHidDeviceIdentity("", 0, 0))
        assertTrue(storage.open().assign(PhoneHidAction.SCROLL_UP, value))
        val loaded = storage.open()
        assertEquals(value, loaded.binding(PhoneHidAction.SCROLL_UP))
        assertEquals(PhoneHidAction.SCROLL_UP, loaded.action(value.button.copy()))
    }

    @Test fun failedMoveRetainsTheEffectiveMapAndRecordUntilASuccessfulRetry() {
        val storage = Storage()
        val model = storage.open()
        val tap = binding()
        val function = binding(24, 29)
        assertTrue(model.assign(PhoneHidAction.TAP, tap))
        assertTrue(model.assign(PhoneHidAction.FUNCTION, function))
        val saved = storage.record
        storage.writable = false
        assertFalse(model.assign(PhoneHidAction.FUNCTION, tap))
        assertEquals(3, storage.writes)
        assertNotNull(model.error)
        assertEquals(saved, storage.record)
        assertEquals(tap, model.binding(PhoneHidAction.TAP))
        assertEquals(function, model.binding(PhoneHidAction.FUNCTION))
        assertEquals(tap, storage.open().binding(PhoneHidAction.TAP))
        assertEquals(function, storage.open().binding(PhoneHidAction.FUNCTION))
        storage.writable = true
        assertTrue(model.assign(PhoneHidAction.FUNCTION, tap))
        assertEquals(4, storage.writes)
        assertNull(model.error)
        assertNull(storage.open().binding(PhoneHidAction.TAP))
        assertEquals(tap, storage.open().binding(PhoneHidAction.FUNCTION))
    }

    @Test fun resetAllRemovesEveryAssignmentInOneEmptyMapWriteAndReloadsEmpty() {
        val storage = Storage()
        val model = storage.open()
        val values = PhoneHidAction.entries.associateWith { binding(20 + it.ordinal, 80 + it.ordinal) }
        values.forEach { (action, value) -> assertTrue(model.assign(action, value)) }
        assertTrue(model.resetAll())
        assertEquals(values.size + 1, storage.writes)
        assertEquals(wireRecord(emptyList()), storage.record)
        val loaded = storage.open()
        values.forEach { (action, value) ->
            assertNull(model.binding(action))
            assertNull(model.action(value.button))
            assertNull(loaded.binding(action))
            assertNull(loaded.action(value.button))
        }
        assertNull(model.error)
        assertNull(loaded.error)
        val fresh = binding(27, 32)
        assertTrue(loaded.assign(PhoneHidAction.FUNCTION, fresh))
        assertEquals(fresh, storage.open().binding(PhoneHidAction.FUNCTION))
    }

    @Test fun failedResetAllRetainsEveryEffectiveAndPersistedAssignmentUntilRetry() {
        val storage = Storage()
        val model = storage.open()
        val values = PhoneHidAction.entries.associateWith { binding(20 + it.ordinal, 80 + it.ordinal) }
        values.forEach { (action, value) -> assertTrue(model.assign(action, value)) }
        val saved = storage.record
        storage.writable = false
        assertFalse(model.resetAll())
        assertEquals(values.size + 1, storage.writes)
        assertEquals(saved, storage.record)
        assertNotNull(model.error)
        values.forEach { (action, value) ->
            assertEquals(value, model.binding(action))
            assertEquals(value, storage.open().binding(action))
        }
        storage.writable = true
        assertTrue(model.resetAll())
        assertEquals(values.size + 2, storage.writes)
        assertEquals(wireRecord(emptyList()), storage.record)
        PhoneHidAction.entries.forEach { assertNull(model.binding(it)) }
        assertNull(model.error)
    }

    @Test fun throwingAssignAndResetRetainTheSavedRecordAndEffectiveMap() {
        for (reset in listOf(false, true)) {
            val storage = Storage()
            val model = storage.open()
            val value = binding()
            assertTrue(model.assign(PhoneHidAction.TAP, value))
            val saved = storage.record
            storage.throwing = true
            val success = if (reset) model.resetAll() else model.assign(PhoneHidAction.FUNCTION, value)
            assertFalse(success)
            assertEquals(2, storage.writes)
            assertEquals(saved, storage.record)
            assertEquals(value, model.binding(PhoneHidAction.TAP))
            assertNull(model.binding(PhoneHidAction.FUNCTION))
            assertNotNull(model.error)
            assertEquals(value, storage.open().binding(PhoneHidAction.TAP))
        }
    }

    @Test fun everyExplicitResetWritesEvenWhenPriorStorageIsAbsentEmptyOrMalformed() {
        val empty = wireRecord(emptyList())
        for (prior in listOf(null, empty, "", "malformed Phone HID record")) {
            val storage = Storage(prior)
            val model = storage.open()
            if (prior != null && prior != empty) assertNotNull(model.error)
            assertEquals(prior, storage.record)
            assertEquals(0, storage.writes)
            repeat(2) { index ->
                assertTrue(model.resetAll())
                assertEquals(index + 1, storage.writes)
                assertEquals(empty, storage.record)
                assertNull(model.error)
                val loaded = storage.open()
                PhoneHidAction.entries.forEach { assertNull(loaded.binding(it)) }
                assertNull(loaded.error)
            }
        }
    }

    @Test fun failedMalformedRecordResetPreservesTheRawRecordAndReportsFailureUntilRetry() {
        val prior = "malformed Phone HID record"
        val storage = Storage(prior)
        val model = storage.open()
        assertTrue(model.error!!.contains("reset all bindings"))
        storage.writable = false
        assertFalse(model.resetAll())
        assertEquals(1, storage.writes)
        assertEquals(prior, storage.record)
        assertEquals("HID bindings could not be saved. Try again.", model.error)
        PhoneHidAction.entries.forEach { assertNull(model.binding(it)) }
        assertNotNull(storage.open().error)
        storage.writable = true
        assertTrue(model.resetAll())
        assertEquals(2, storage.writes)
        assertEquals(wireRecord(emptyList()), storage.record)
        assertNull(model.error)
        assertNull(storage.open().error)
    }

    @Test fun corruptRecordsLoadNoPartialAssignmentsAndStayUntouchedUntilExplicitReplacement() {
        val valid = wireRecord(listOf("TAP" to binding()))
        val payload = Base64.getDecoder().decode(valid.substringAfter(':'))
        val corrupt = listOf(
            "",
            "eyeprojector.hid-bindings.2:" + valid.substringAfter(':'),
            "eyeprojector.hid-bindings.1:not base64!",
            "eyeprojector.hid-bindings.1:" + Base64.getEncoder().encodeToString(payload.copyOf(payload.size - 1)),
            "eyeprojector.hid-bindings.1:" + Base64.getEncoder().encodeToString(payload + byteArrayOf(0)),
            wireRecord(listOf("TAP" to binding(), "TAP" to binding(24, 29))),
            wireRecord(listOf("TAP" to binding(), "FUNCTION" to binding())),
            wireRecord(listOf("UNKNOWN_ACTION" to binding())),
        )
        corrupt.forEachIndexed { index, prior ->
            val storage = Storage(prior)
            val model = storage.open()
            assertNotNull(model.error)
            PhoneHidAction.entries.forEach { assertNull(model.binding(it)) }
            assertEquals(prior, storage.record)
            assertEquals(0, storage.writes)
            if (index % 2 == 0) {
                assertTrue(model.resetAll())
                assertEquals(wireRecord(emptyList()), storage.record)
            } else {
                assertTrue(model.assign(PhoneHidAction.TAP, binding()))
                assertEquals(binding(), storage.open().binding(PhoneHidAction.TAP))
            }
            assertEquals(1, storage.writes)
            assertNull(model.error)
            assertNull(storage.open().error)
        }
    }

    @Test fun throwingReadReportsTheUnavailableRecordWithoutWritingAndExplicitResetCanRepairIt() {
        var record: String? = null
        var writes = 0
        val model = PhoneHidBindings({ error("Storage unavailable") }) { record = it; writes++; true }
        assertNotNull(model.error)
        PhoneHidAction.entries.forEach { assertNull(model.binding(it)) }
        assertEquals(0, writes)
        assertTrue(model.resetAll())
        assertEquals(1, writes)
        assertEquals(wireRecord(emptyList()), record)
        assertNull(model.error)
        val loaded = PhoneHidBindings({ record }) { error("Reload must not write") }
        assertNull(loaded.error)
        PhoneHidAction.entries.forEach { assertNull(loaded.binding(it)) }
    }

    private fun wireRecord(entries: List<Pair<String, PhoneHidBinding>>): String {
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
