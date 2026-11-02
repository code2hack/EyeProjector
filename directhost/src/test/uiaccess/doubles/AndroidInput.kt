package android.view
data class InputDevice(val isExternal: Boolean = true, val isVirtual: Boolean = false,
    val name: String = "fixture", val descriptor: String = "fixture", val vendorId: Int = 1, val productId: Int = 2)
data class KeyEvent(val action: Int = ACTION_DOWN, val deviceId: Int = 1, val keyCode: Int = 40,
    val scanCode: Int = 50, val downTime: Long = 1, val eventTime: Long = 1, val repeatCount: Int = 0,
    val isCanceled: Boolean = false, val device: InputDevice? = InputDevice()) {
    companion object { const val ACTION_DOWN = 0; const val ACTION_UP = 1; fun keyCodeToString(code: Int) = "KEY_$code" }
}
