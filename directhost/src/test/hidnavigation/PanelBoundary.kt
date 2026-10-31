package com.code2hack.eyeprojector.directhost.hid

import com.code2hack.eyeprojector.protocol.PhoneHidAction
import java.io.Closeable

// Runtime/interception boundaries for compiling the complete actual Phone panel.
// These fixtures do not simulate raw HID delivery, capture or the pin service.
internal class PanelController(val bindings: PhoneHidBindings) {
    var selected: PhoneHidAction? = null
    val saving = 0
    val capturing = false
    val receivedText = ""
    fun openPage() { selected = null }
    fun closePage() { selected = null }
    fun select(action: PhoneHidAction) { selected = action }
}
internal class PhoneHidPinRuntime {
    data class Device(val key: String, val name: String, val vendorId: Int = 1, val productId: Int = 2,
        val collectionCount: Int = 1, val hasTouch: Boolean = false)
    data class State(val intercepted: Boolean = true, val savedKey: String? = "original pin",
        val savedName: String? = "Original peripheral", val devices: List<Device> = emptyList(),
        val collectionCount: Int = 1, val phase: String = "ACTIVE", val error: String? = null)
    var state = State()
    val pinnedKey get() = state.savedKey
    val selectedKey get() = state.savedKey
    val canSelectDevice = false
    val canPinSelected = false
    var mutations = 0
    fun pinSelected() { mutations++ }
    fun unpinAndReset() { mutations++ }
    fun select(key: String) { mutations++ }
    companion object {
        fun bindingMatchesDevice(binding: PhoneHidBinding, key: String) = binding.button.device.descriptor == key
    }
}
internal class PhoneHidRuntime(val controller: PanelController, val pin: PhoneHidPinRuntime) {
    fun observe(callback: () -> Unit) = Closeable {}
    fun refresh() = Unit
}
