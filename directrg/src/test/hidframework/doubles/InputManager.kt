package android.hardware.input

import android.os.Handler
import android.view.InputDevice

/** Observable registration and explicit delivery; no Android service or hardware is involved. */
class InputManager {
    interface InputDeviceListener {
        fun onInputDeviceAdded(deviceId: Int)
        fun onInputDeviceChanged(deviceId: Int)
        fun onInputDeviceRemoved(deviceId: Int)
    }
    private val listeners = linkedSetOf<InputDeviceListener>()
    val devices = linkedMapOf<Int, InputDevice>()
    val inputDeviceIds get() = devices.keys.toIntArray()
    var deviceReads = 0; private set
    fun getInputDevice(id: Int): InputDevice? { deviceReads++; return devices[id] }
    fun install(device: InputDevice) { devices[device.id] = device }
    var registrations = 0; private set
    var unregistrations = 0; private set
    var lastHandler: Handler? = null; private set
    val listenerCount get() = listeners.size
    fun registerInputDeviceListener(listener: InputDeviceListener, handler: Handler) {
        check(listeners.add(listener))
        registrations++
        lastHandler = handler
    }
    fun unregisterInputDeviceListener(listener: InputDeviceListener) {
        check(listeners.remove(listener))
        unregistrations++
    }
    fun add(id: Int) = listeners.toList().forEach { it.onInputDeviceAdded(id) }
    fun change(id: Int) = listeners.toList().forEach { it.onInputDeviceChanged(id) }
    fun remove(id: Int) = listeners.toList().forEach { it.onInputDeviceRemoved(id) }
}
