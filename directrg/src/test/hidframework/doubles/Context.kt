package android.content

import android.content.res.Resources
import android.hardware.input.InputManager

/** Only the service lookup and density used by the production adapter/panel are modeled. */
open class Context(val inputManager: InputManager = InputManager()) {
    val resources = Resources()
    fun <T> getSystemService(type: Class<T>): T {
        check(type == InputManager::class.java)
        return type.cast(inputManager)
    }
}
