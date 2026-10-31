package com.code2hack.eyeprojector.directhost.hid
import android.content.Context
import com.code2hack.eyeprojector.protocol.PhoneHidPhase

// Existing HID controller/runtime are modeled here; the actual service callbacks are compiled.
data class PhoneHidDeviceIdentity(val descriptor: String, val vendor: Int, val product: Int)
data class PhoneHidButton(val device: PhoneHidDeviceIdentity, val keyCode: Int, val scanCode: Int)
data class PhoneHidBinding(val button: PhoneHidButton, val name: String, val keyName: String)
class PhoneHidController {
    data class Event(val deviceId: Int, val binding: PhoneHidBinding, val phase: PhoneHidPhase,
        val downTime: Long, val eventTime: Long, val repeats: Int)
    val events = ArrayList<Event>()
    var acceptResult = false
    fun consumeRetired(deviceId: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase) = false
    fun consumeWake(deviceId: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase) = false
    fun consumePassed(deviceId: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase) = false
    fun consumeUnavailable(deviceId: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, repeats: Int) = false
    fun consumeChanged(deviceId: Int, key: Int, scan: Int, down: Long, phase: PhoneHidPhase, identity: PhoneHidDeviceIdentity) = false
    fun accept(event: Event): Boolean { events.add(event); return acceptResult }
    fun externalAttached() {}
    fun deviceRemoved(deviceId: Int) {}
    fun cancelInput() {}
}
class PhoneHidRuntime {
    class Pin { var ownsRoute = false }
    val pin = Pin()
    val controller = PhoneHidController()
    val connections = ArrayList<Boolean>()
    fun refresh() {}
    fun accessibilityConnection(connected: Boolean) { connections.add(connected) }
    companion object {
        var current = PhoneHidRuntime()
        fun get(context: Context) = current
    }
}
