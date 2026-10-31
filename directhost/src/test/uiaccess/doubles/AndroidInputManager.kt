package android.hardware.input
import android.os.Handler
import android.view.InputDevice
class InputManager {
    var listeners = 0
    fun registerInputDeviceListener(listener: InputDeviceListener, handler: Handler) { listeners++ }
    fun unregisterInputDeviceListener(listener: InputDeviceListener) { listeners-- }
    fun getInputDevice(id: Int): InputDevice? = null
    interface InputDeviceListener {
        fun onInputDeviceAdded(deviceId: Int)
        fun onInputDeviceChanged(deviceId: Int)
        fun onInputDeviceRemoved(deviceId: Int)
    }
}
