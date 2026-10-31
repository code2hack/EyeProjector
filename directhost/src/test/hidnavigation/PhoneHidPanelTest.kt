package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.widget.Button
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import org.junit.Assert.*
import org.junit.Test

class PhoneHidPanelTest {
    @Test fun sixRowsSelectTheirExactRolesWithoutChangingThePinOrSavedFunction() {
        var record: String? = null
        var writes = 0
        val bindings = PhoneHidBindings({ record }) { record = it; writes++; true }
        val original = PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity("original pin", 1, 2), 100, 200), "Remote", "Original Function")
        bindings.assign(PhoneHidAction.FUNCTION, original)
        val originalRecord = record
        val input = PanelController(bindings)
        val pin = PhoneHidPinRuntime()
        val runtime = PhoneHidRuntime(input, pin)
        val panel = PhoneHidPanel(Context(), runtime) {}
        val roles = listOf(PhoneHidAction.TAP, PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP,
            PhoneHidAction.BACK_HOME, PhoneHidAction.LOCK, PhoneHidAction.FUNCTION)
        val rows = panel.children.filterIsInstance<Button>().filter { it.text?.contains(':') == true }
        assertEquals(listOf("Tap: Unassigned for this device", "Scroll down: Unassigned for this device",
            "Scroll up: Unassigned for this device", "Back/Home: Unassigned for this device",
            "Lock: Unassigned for this device", "Function button: Remote: Original Function"), rows.map { it.text.toString() })
        panel.open()
        roles.forEachIndexed { index, role ->
            assertTrue(rows[index].isEnabled)
            assertTrue(rows[index].performClick())
            assertEquals(role, input.selected)
            panel.refresh()
            assertEquals(listOf(index), rows.indices.filter { rows[it].isSelected })
        }
        panel.close(); panel.open()
        pin.state = pin.state.copy(intercepted = false, phase = "DISCONNECTED")
        panel.refresh()
        assertTrue(rows.none { it.isEnabled })
        pin.state = pin.state.copy(intercepted = true, phase = "ACTIVE")
        panel.refresh()
        assertTrue(rows.all { it.isEnabled })
        assertEquals(0, pin.mutations)
        assertEquals("original pin", pin.state.savedKey)
        assertEquals(originalRecord, record)
        assertEquals(1, writes)
        assertEquals(original, bindings.binding(PhoneHidAction.FUNCTION))
        assertNull(bindings.binding(PhoneHidAction.BACK_HOME))
        assertNull(bindings.binding(PhoneHidAction.LOCK))
    }
}
