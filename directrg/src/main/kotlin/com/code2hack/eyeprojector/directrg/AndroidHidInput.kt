package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.Step
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.observed

/** External HID ingress; attachment changes retire transient work and repair invalid assignments. */
internal class AndroidHidInput(
    context: Context,
    private val input: HidKeyInput,
    private val received: (HidKeyInput.Event) -> Unit = {},
) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private var registered = false
    fun start() {
        if (registered) return
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        registered = true
        input.clearInvalidBindings(manager.inputDeviceIds.map { manager.getInputDevice(it) }.filterNotNull()
            .filterNot(::eligible).map(::identity).toSet())
    }
    fun stop() {
        input.cancel()
        if (!registered) return
        registered = false
        manager.unregisterInputDeviceListener(this)
    }
    fun accept(event: KeyEvent, trace: HidDecisionTrace.Event? = null): Boolean {
        val phase = when {
            event.isCanceled -> HidKeyInput.Phase.CANCEL
            event.action == KeyEvent.ACTION_DOWN -> HidKeyInput.Phase.DOWN
            event.action == KeyEvent.ACTION_UP -> HidKeyInput.Phase.UP
            else -> HidKeyInput.Phase.CANCEL
        }
        if (observed(trace, Step.ADAPTER_RETIRED, input.consumeRetired(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime))) return true
        val device = event.device
        if (device == null) {
            trace?.record(Step.ADAPTER_DEVICE_MISSING, HidDecisionTrace.Fact.YES)
            return observed(trace, Step.ADAPTER_DETACHED,
                input.consumeDetached(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime))
        }
        trace?.record(Step.ADAPTER_DEVICE_MISSING, HidDecisionTrace.Fact.NO)
        if (observed(trace, Step.ADAPTER_CHANGED, input.consumeChangedCapture(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime,
                device.descriptor, device.vendorId, device.productId))) return true
        // A previously owned external edge must retire its captured route, never become native UP.
        // Fresh excluded input falls through to the Activity's unchanged native routing.
        if (!eligible(device, trace)) {
            trace?.record(Step.ADAPTER_INELIGIBLE, HidDecisionTrace.Fact.YES)
            return observed(trace, Step.ADAPTER_DETACHED,
                input.consumeDetached(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime))
        }
        val binding = HidBinding(HidButton(identity(device), event.keyCode, event.scanCode), device.name, KeyEvent.keyCodeToString(event.keyCode))
        // FLAG_LONG_PRESS is another DOWN of the same physical hold, never a CANCEL.
        val actual = HidKeyInput.Event(event.deviceId, binding, phase, event.downTime, event.eventTime, event.repeatCount)
        if (actual.phase == HidKeyInput.Phase.DOWN && actual.repeat == 0) {
            try { received(actual) } catch (_: Throwable) { /* Feedback cannot alter key handling. */ }
        }
        return observed(trace, Step.ADAPTER_INPUT, input.accept(actual, trace))
    }
    // Both built-in names are observed in Owner-authorized input capture379; the pad is also
    // the existing nativePadKey source. Never admit them merely because a flag says external.
    private fun eligible(device: InputDevice): Boolean = eligible(device, null)

    private fun eligible(device: InputDevice, trace: HidDecisionTrace.Event?): Boolean =
        observed(trace, Step.DEVICE_EXTERNAL, device.isExternal) &&
        !observed(trace, Step.DEVICE_VIRTUAL, device.isVirtual) &&
        observed(trace, Step.DEVICE_NOT_PAD, device.name != "ROKID,PSOC-TP-R") &&
        observed(trace, Step.DEVICE_NOT_POWER, device.name != "qpnp_pon")

    private fun identity(device: InputDevice) = HidDeviceIdentity(device.descriptor, device.vendorId, device.productId)

    private fun repair(deviceId: Int) {
        manager.getInputDevice(deviceId)?.takeUnless(::eligible)?.let {
            input.clearInvalidBindings(setOf(identity(it)))
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = repair(deviceId)
    override fun onInputDeviceChanged(deviceId: Int) { input.cancel(); repair(deviceId) }
    override fun onInputDeviceRemoved(deviceId: Int) = input.cancelDevice(deviceId)
}
