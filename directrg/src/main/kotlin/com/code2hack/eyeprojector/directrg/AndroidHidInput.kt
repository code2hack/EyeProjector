package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.protocol.Touch
import com.code2hack.eyeprojector.protocol.HidNavigationGestureRecognizer
import android.os.SystemClock
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.Step
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.observed

/** External HID ingress; attachment changes retire transient work and repair invalid assignments. */
internal class AndroidHidInput(
    context: Context,
    private val input: HidKeyInput,
    private val learning: () -> Boolean = { false },
    private val admit: ((Boolean) -> Unit) -> Unit = { it(true) },
    private val unhandled: (KeyEvent, String) -> Unit = { _, _ -> },
    private val current: () -> Any? = { Unit },
    private val received: (HidKeyInput.Event) -> Unit = {},
) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private var registered = false
    private data class Raw(val attachment: Int, val key: Int, val scan: Int, val down: Long)
    private class Decision {
        private var normal: Boolean? = null
        private var requested = false
        private val pending = mutableListOf<(Boolean) -> Unit>()
        fun request(admit: ((Boolean) -> Unit) -> Unit, complete: (Boolean) -> Unit) {
            normal?.let { complete(it); return }
            pending.add(complete)
            if (requested) return
            requested = true
            admit { result ->
                if (normal != null) return@admit
                normal = result
                val original = pending.toList(); pending.clear()
                original.forEach { it(result) }
            }
        }
    }
    private data class Learning(val event: HidKeyInput.Event, val original: KeyEvent, val trace: HidDecisionTrace.Event?, val context: Any?, val decision: Decision)
    private val backHomeReceipts = HidNavigationGestureRecognizer.Receipts<Decision>()
    private var receivingDecision: Decision? = null
    private fun raw(event: KeyEvent) = Raw(event.deviceId,event.keyCode,event.scanCode,event.downTime)
    private fun raw(event: HidKeyInput.Event) = Raw(event.attachmentId,event.binding.button.keyCode,event.binding.button.scanCode,event.downTimeMs)
    private val retiredLearning = mutableSetOf<Raw>()
    private val learningInput = ReceiverContactInput<Learning>({ raw(it.event) },
        { Touch.Phase.valueOf(it.event.phase.name) }, { complete ->
            val original = receivingDecision
            if (original == null) admit(complete) else original.request(admit, complete)
        }, { original ->
            val event=original.event
            fun attached() = current() == original.context
            // Device facts belong to receipt time, even if DOWN/UP waited for the same decision.
            val identity=event.binding.button.device
            val retired=!attached() || input.consumeRetired(event.attachmentId,event.binding.button.keyCode,event.binding.button.scanCode,event.phase,event.downTimeMs) ||
                input.consumeChangedCapture(event.attachmentId,event.binding.button.keyCode,event.binding.button.scanCode,event.phase,event.downTimeMs,
                    identity.descriptor,identity.vendorId,identity.productId)
            if (!retired && !deliver(event,original.trace,::attached) && attached())
                unhandled(original.original,event.binding.deviceName)
        })

    private fun retireLearning() { retiredLearning.addAll(learningInput.retire().filterIsInstance<Raw>()) }
    fun start() {
        if (registered) return
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        registered = true
        input.clearInvalidBindings(manager.inputDeviceIds.map { manager.getInputDevice(it) }.filterNotNull()
            .filterNot(::eligible).map(::identity).toSet())
    }
    fun stop() {
        retireLearning()
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
        val raw=raw(event)
        if (raw in retiredLearning) {
            if (phase != HidKeyInput.Phase.DOWN) retiredLearning.remove(raw)
            return true
        }
        if (observed(trace, Step.ADAPTER_RETIRED, input.consumeRetired(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime))) {
            if (phase != HidKeyInput.Phase.DOWN) learningInput.retire(raw)
            return true
        }
        val device = event.device
        if (device == null) {
            trace?.record(Step.ADAPTER_DEVICE_MISSING, HidDecisionTrace.Fact.YES)
            val pending=learningInput.contains(raw)
            if (pending) {
                learningInput.retire(raw)
                if (phase == HidKeyInput.Phase.DOWN) retiredLearning.add(raw)
            }
            return observed(trace, Step.ADAPTER_DETACHED,
                input.consumeDetached(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime) || pending)
        }
        trace?.record(Step.ADAPTER_DEVICE_MISSING, HidDecisionTrace.Fact.NO)
        if (observed(trace, Step.ADAPTER_CHANGED, input.consumeChangedCapture(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime,
                device.descriptor, device.vendorId, device.productId))) {
            learningInput.retire(raw)
            return true
        }
        // A previously owned external edge must retire its captured route, never become native UP.
        // Fresh excluded input falls through to the Activity's unchanged native routing.
        if (!eligible(device, trace)) {
            trace?.record(Step.ADAPTER_INELIGIBLE, HidDecisionTrace.Fact.YES)
            val pending=learningInput.contains(raw)
            if (pending) {
                learningInput.retire(raw)
                if (phase == HidKeyInput.Phase.DOWN) retiredLearning.add(raw)
            }
            return observed(trace, Step.ADAPTER_DETACHED,
                input.consumeDetached(event.deviceId, event.keyCode, event.scanCode, phase, event.downTime) || pending)
        }
        val binding = HidBinding(HidButton(identity(device), event.keyCode, event.scanCode), device.name, KeyEvent.keyCodeToString(event.keyCode))
        // FLAG_LONG_PRESS is another DOWN of the same physical hold, never a CANCEL.
        val actual = HidKeyInput.Event(event.deviceId, binding, phase, event.downTime, event.eventTime, event.repeatCount)
        val action = input.actionFor(actual)
        if (action == HidAction.NAVIGATION && phase != HidKeyInput.Phase.DOWN) {
            if (phase == HidKeyInput.Phase.UP) backHomeReceipts.up(raw, event.eventTime) else backHomeReceipts.cancel(raw)
        }
        // Raw Function keeps its existing classifier/FIFO original lifetime; Short must reach UP.
        val operational = action != null && action != HidAction.ANCHOR
        if (learningInput.contains(raw) || actual.phase == HidKeyInput.Phase.DOWN && actual.repeat == 0 && (learning() || operational)) {
            val first=learningInput.original(raw)
            val originalContext=if(first!=null) first.context else current()
            val decision = first?.decision ?: if (action == HidAction.NAVIGATION && !learning())
                backHomeReceipts.down(raw, Triple(event.deviceId,event.keyCode,event.scanCode), Decision(), SystemClock.uptimeMillis())
                else Decision()
            val previous = receivingDecision
            receivingDecision = decision
            try { return learningInput.accept(Learning(actual,KeyEvent(event),trace,originalContext,decision)) }
            finally { receivingDecision = previous }
        }
        return deliver(actual,trace)
    }

    private fun deliver(actual: HidKeyInput.Event, trace: HidDecisionTrace.Event?, attached: (() -> Boolean)? = null): Boolean {
        if (attached?.invoke() == false) return true
        if (actual.phase == HidKeyInput.Phase.DOWN && actual.repeat == 0) {
            try { received(actual) } catch (_: Throwable) { /* Feedback cannot alter key handling. */ }
        }
        if (attached?.invoke() == false) return true
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
    override fun onInputDeviceChanged(deviceId: Int) { retireLearning(); input.cancel(); repair(deviceId) }
    override fun onInputDeviceRemoved(deviceId: Int) { retireLearning(); input.cancelDevice(deviceId) }
}
