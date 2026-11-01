package android.hardware.input
class InputManager {
    interface InputDeviceListener {
        fun onInputDeviceAdded(id:Int)
        fun onInputDeviceRemoved(id:Int)
        fun onInputDeviceChanged(id:Int)
    }
    val listeners=mutableSetOf<InputDeviceListener>()
    fun registerInputDeviceListener(listener:InputDeviceListener,handler:android.os.Handler?){listeners.add(listener)}
    fun unregisterInputDeviceListener(listener:InputDeviceListener){listeners.remove(listener)}
    fun getInputDevice(id:Int)=android.view.InputDevice
}
