package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent

/** Android position-class touchpad MotionEvents contain real edges; aggregate keyboard keys do not. */
internal class NativePadContact(
    context: Context,
    private val position: () -> PointerPosition,
    private val emit: (PadGestureRecognizer.Event) -> Unit,
    private val lost: () -> Unit,
) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private var registered = false
    private var deviceId: Int? = null
    private var pointerId: Int? = null
    private var downTime = 0L
    var downs = 0L; private set
    var releases = 0L; private set
    var cancels = 0L; private set
    var deviceLosses = 0L; private set
    val holding get() = deviceId != null

    fun start() {
        if (registered) return
        manager.registerInputDeviceListener(this, null); registered = true
    }
    fun stop() {
        if (registered) manager.unregisterInputDeviceListener(this)
        registered = false; clear()
    }
    fun clear() { deviceId = null; pointerId = null; downTime = 0L }

    fun accept(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PadGestureRecognizer.Phase.DOWN
            MotionEvent.ACTION_MOVE -> PadGestureRecognizer.Phase.MOVE
            MotionEvent.ACTION_UP -> PadGestureRecognizer.Phase.UP
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> PadGestureRecognizer.Phase.CANCEL
            else -> return false
        }
        if (phase == PadGestureRecognizer.Phase.DOWN) {
            if (holding) { clear(); lost() }
            deviceId = event.deviceId; pointerId = event.getPointerId(0); downTime = event.downTime; downs++
        } else if (deviceId != event.deviceId || downTime != event.downTime) return true
        if (event.findPointerIndex(pointerId ?: -1) < 0) { clear(); cancels++; lost(); return true }
        val action = PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, phase, event.eventTime, downTime,
            position = position())
        if (phase == PadGestureRecognizer.Phase.UP) releases++
        if (phase == PadGestureRecognizer.Phase.CANCEL) cancels++
        if (phase == PadGestureRecognizer.Phase.UP || phase == PadGestureRecognizer.Phase.CANCEL) clear()
        emit(action)
        return true
    }

    override fun onInputDeviceAdded(id: Int) = Unit
    override fun onInputDeviceRemoved(id: Int) {
        if (deviceId == id) { deviceLosses++; clear(); lost() }
    }
    override fun onInputDeviceChanged(id: Int) {
        if (deviceId == id && manager.getInputDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHPAD) != true) {
            deviceLosses++; clear(); lost()
        }
    }
}
