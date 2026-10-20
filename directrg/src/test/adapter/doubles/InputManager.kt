package android.hardware.input

import android.view.InputDevice

/** Delivers removal through the registered production listener, including start/stop behavior. */
class InputManager {
    interface InputDeviceListener {
        fun onInputDeviceAdded(id: Int)
        fun onInputDeviceRemoved(id: Int)
        fun onInputDeviceChanged(id: Int)
    }
    private val listeners = mutableSetOf<InputDeviceListener>()
    private val devices = mutableMapOf(7 to InputDevice())

    fun registerInputDeviceListener(listener: InputDeviceListener, handler: Any?) { listeners.add(listener) }
    fun unregisterInputDeviceListener(listener: InputDeviceListener) { listeners.remove(listener) }
    fun getInputDevice(id: Int) = devices[id]
    fun removeDevice(id: Int) {
        devices.remove(id)
        listeners.toList().forEach { it.onInputDeviceRemoved(id) }
    }
}
