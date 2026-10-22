package android.view

/** Descriptor/vendor/product/name and transient attachment ID are independently observable facts. */
data class InputDevice(
    val id: Int, val descriptor: String, val vendorId: Int, val productId: Int, val name: String,
    val isExternal: Boolean = true, val isVirtual: Boolean = false,
)
class KeyEvent(
    val device: InputDevice?, val action: Int, val keyCode: Int, val scanCode: Int,
    val downTime: Long, val eventTime: Long, val repeatCount: Int = 0, val flags: Int = 0,
    val deviceId: Int = device?.id ?: -1,
    val source: Int = 0x101, val metaState: Int = 0,
) {
    val isCanceled get() = flags and FLAG_CANCELED != 0
    val isLongPress get() = flags and FLAG_LONG_PRESS != 0
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MULTIPLE = 2
        const val FLAG_CANCELED = 0x20
        const val FLAG_LONG_PRESS = 0x80
        const val KEYCODE_BACK = 4
        // This host label deliberately makes adapter use of keyCodeToString observable.
        fun keyCodeToString(code: Int) = "HOST_KEYCODE_$code"
    }
}
