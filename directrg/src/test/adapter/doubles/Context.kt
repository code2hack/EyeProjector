package android.content

import android.hardware.input.InputManager

/** Only the service used by the production NativePadContact adapter is modeled. */
open class Context {
    val inputManager = InputManager()

    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(type: Class<T>): T {
        check(type == InputManager::class.java)
        return inputManager as T
    }
}
