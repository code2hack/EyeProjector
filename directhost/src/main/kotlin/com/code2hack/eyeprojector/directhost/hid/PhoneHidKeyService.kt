package com.code2hack.eyeprojector.directhost.hid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.code2hack.eyeprojector.protocol.PhoneHidPhase
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetAccess

/** Legacy Android key ingress. The raw pin owner bypasses this mapping entirely. */
class PhoneHidKeyService : AccessibilityService(), InputManager.InputDeviceListener {
    private var runtime: PhoneHidRuntime? = null
    private var manager: InputManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        UiTargetAccess.connected(this)
        val source = PhoneHidRuntime.get(this)
        runtime = source
        if (manager == null) {
            manager = getSystemService(InputManager::class.java).also {
                it.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
            }
        }
        source.refresh()
        source.accessibilityConnection(true)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val source = runtime ?: return false
        // No Android ID/name/vendor guess may impersonate the raw pinned physical group.
        // Its collections are intercepted below Android; unrelated keys keep their normal route.
        if (source.pin.ownsRoute) return false
        val input = source.controller
        val phase = when {
            event.isCanceled -> PhoneHidPhase.CANCEL
            event.action == KeyEvent.ACTION_DOWN -> PhoneHidPhase.DOWN
            event.action == KeyEvent.ACTION_UP -> PhoneHidPhase.UP
            else -> return false
        }
        if (input.consumeRetired(event.deviceId, event.keyCode, event.scanCode, event.downTime, phase)) return true
        if (input.consumeWake(event.deviceId, event.keyCode, event.scanCode, event.downTime, phase)) return true
        if (input.consumePassed(event.deviceId, event.keyCode, event.scanCode, event.downTime, phase)) return false
        val device = event.device ?: return input.consumeUnavailable(event.deviceId, event.keyCode,
            event.scanCode, event.downTime, phase, event.repeatCount)
        val identity = PhoneHidDeviceIdentity(device.descriptor, device.vendorId, device.productId)
        if (input.consumeChanged(event.deviceId, event.keyCode, event.scanCode, event.downTime, phase, identity)) return true
        // These native Rokid names are observed canonical exclusions, not a guessed Phone descriptor.
        if (!eligible(device))
            return input.consumeUnavailable(event.deviceId, event.keyCode, event.scanCode, event.downTime, phase, event.repeatCount)
        val binding = PhoneHidBinding(PhoneHidButton(identity, event.keyCode, event.scanCode),
            device.name, KeyEvent.keyCodeToString(event.keyCode))
        return input.accept(PhoneHidController.Event(event.deviceId, binding, phase,
            event.downTime, event.eventTime, event.repeatCount))
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = manager?.getInputDevice(deviceId) ?: return
        if (eligible(device) && runtime?.pin?.ownsRoute == false) runtime?.controller?.externalAttached()
    }
    private fun eligible(device: InputDevice) = device.isExternal && !device.isVirtual &&
        device.name != "ROKID,PSOC-TP-R" && device.name != "qpnp_pon"
    override fun onInputDeviceChanged(deviceId: Int) { runtime?.controller?.deviceRemoved(deviceId) }
    override fun onInputDeviceRemoved(deviceId: Int) { runtime?.controller?.deviceRemoved(deviceId) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {
        if (runtime?.pin?.ownsRoute == false) runtime?.controller?.cancelInput()
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    private fun disconnect() {
        UiTargetAccess.disconnected(this)
        val source = runtime
        runtime = null // Retire service ownership before cancellation/observation callbacks.
        manager?.unregisterInputDeviceListener(this)
        manager = null
        source?.accessibilityConnection(false)
        source?.refresh()
    }
}
