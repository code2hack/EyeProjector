package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/** Android attachment changes invalidate transient work, never the persisted bindings. */
internal class AndroidHidInput(context: Context, private val input: HidKeyInput) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private var registered = false
    fun start() {
        if (registered) return
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        registered = true
    }
    fun stop() {
        input.cancel()
        if (!registered) return
        registered = false
        manager.unregisterInputDeviceListener(this)
    }
    fun accept(event: KeyEvent): Boolean {
        val phase = when {
            event.isCanceled -> HidKeyInput.Phase.CANCEL
            event.action == KeyEvent.ACTION_DOWN -> HidKeyInput.Phase.DOWN
            event.action == KeyEvent.ACTION_UP -> HidKeyInput.Phase.UP
            else -> HidKeyInput.Phase.CANCEL
        }
        if (input.consumeRetired(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime)) return true
        val device = event.device ?: return input.consumeDetached(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime)
        val identity = HidDeviceIdentity(device.descriptor, device.vendorId, device.productId)
        val binding = HidBinding(HidButton(identity, event.keyCode, event.scanCode), device.name, KeyEvent.keyCodeToString(event.keyCode))
        // FLAG_LONG_PRESS is another DOWN of the same physical hold, never a CANCEL.
        return input.accept(HidKeyInput.Event(event.deviceId, binding, phase, event.downTime, event.eventTime, event.repeatCount))
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = input.cancel()
    override fun onInputDeviceRemoved(deviceId: Int) = input.cancelDevice(deviceId)
}
