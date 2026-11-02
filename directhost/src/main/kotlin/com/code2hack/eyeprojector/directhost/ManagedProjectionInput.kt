package com.code2hack.eyeprojector.directhost

import android.os.SystemClock
import android.os.Process
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.code2hack.eyeprojector.directhost.uiaccess.UiPoint
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Scroll
import com.code2hack.eyeprojector.protocol.Tap
import com.code2hack.eyeprojector.protocol.Touch

/** Pointer injection into this original VDM source; keys retain the stock scrcpy route. */
internal class ManagedProjectionInput(
    private val owner: ManagedProjectionDisplay,
) : PhoneTouchDispatcher.MotionTarget {
    private class Contact(val id: Long, val destination: ManagedProjectionDisplay.InputDestination,
        val downTime: Long, var point: Pair<Float, Float>)
    private var held: Contact? = null

    // InputManagerGlobal is the real framework injector on the VDM-supported Android versions.
    // No permission/settings changes, alternate target, or physical-display fallback.
    private val managerType by lazy { Class.forName("android.hardware.input.InputManagerGlobal") }
    private val manager by lazy { managerType.getMethod("getInstance").invoke(null) }
    private val injectMethod by lazy {
        managerType.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    override fun geometry(next: Geometry) {
        owner.inputGeometry(next)
    }

    @Synchronized override fun touch(event: Touch): Boolean {
        if (event.phase == Touch.Phase.DOWN) return owner.withInputMapping(event.target.geometry) { _, mapping ->
            val destination = owner.inputDestination() ?: return@withInputMapping false
            val now = SystemClock.uptimeMillis()
            val original = Contact(event.contactId, destination, now, sourceCoordinates(event.target, mapping))
            held = original // Own an original terminal even when injection reenters or fails.
            owner.withInputDestination(destination) { id -> motion(id, original.point, event.phase.action,
                now, now, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 1f) }
        }
        val original = held?.takeIf { it.id == event.contactId }
            ?: return event.phase == Touch.Phase.CANCEL && held == null
        if (event.phase == Touch.Phase.MOVE) return owner.withInputMapping(event.target.geometry) { _, mapping ->
            val next = sourceCoordinates(event.target, mapping)
            val accepted = owner.withInputDestination(original.destination) { id ->
                motion(id, next, event.phase.action, original.downTime, SystemClock.uptimeMillis(),
                    InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 1f)
            }
            if (accepted && held === original) original.point = next
            accepted
        }
        val accepted = owner.withInputDestination(original.destination) { id ->
            motion(id, original.point, event.phase.action, original.downTime, SystemClock.uptimeMillis(),
                InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 0f)
        }
        if (accepted && held === original) held = null
        return accepted
    }

    @Synchronized override fun tap(event: Tap): Boolean =
        owner.withInputMapping(event.geometry) { _, mapping ->
            // The dispatcher cancels a held contact before a discrete tap, as on its legacy path.
            val destination = owner.inputDestination() ?: return@withInputMapping false
            val point = sourceCoordinates(event, mapping)
            val down = SystemClock.uptimeMillis()
            fun submit(action: Int, time: Long, pressure: Float) = owner.withInputDestination(destination) { id ->
                motion(id, point, action, down, time, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, pressure)
            }
            if (!submit(MotionEvent.ACTION_DOWN, down, 1f)) {
                return@withInputMapping false
            }
            val released = submit(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), 0f)
            released
        }

    @Synchronized override fun scroll(event: Scroll): Boolean =
        owner.withInputMapping(event.target.geometry) { displayId, mapping ->
            val now = SystemClock.uptimeMillis()
            motion(displayId, sourceCoordinates(event.target, mapping), MotionEvent.ACTION_SCROLL,
                now, now, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, 0f,
                verticalScroll = -event.pixels.toFloat() / 64f)
        }

    private fun motion(
        displayId: Int,
        point: Pair<Float, Float>,
        action: Int,
        downTime: Long,
        eventTime: Long,
        source: Int,
        tool: Int,
        pressure: Float,
        verticalScroll: Float = 0f,
    ): Boolean {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = tool
        }
        val (mappedX, mappedY) = point
        val coordinates = MotionEvent.PointerCoords().apply {
            x = mappedX
            y = mappedY
            this.pressure = pressure
            size = 1f
            if (action == MotionEvent.ACTION_SCROLL) {
                setAxisValue(MotionEvent.AXIS_HSCROLL, 0f)
                setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
            }
        }
        val event = MotionEvent.obtain(downTime, eventTime, action, 1,
            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f,
            0, 0, source, 0)
        return try {
            setDisplayIdMethod.invoke(event, displayId)
            // Resolve lazy reflection setup before timing the actual framework invocation.
            val method = injectMethod
            val targetManager = manager
            val timed = action != MotionEvent.ACTION_MOVE
            val phase = when (action) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                MotionEvent.ACTION_SCROLL -> "SCROLL"
                else -> "OTHER"
            }
            if (timed) Log.i("EyeProjectorInput", "managed_motion phase=$phase stage=before_invoke uptimeMs=${SystemClock.uptimeMillis()} tid=${Process.myTid()}")
            val invoked = if (timed) SystemClock.uptimeMillis() else 0L
            var returned = false
            var accepted = false
            try {
                // ASYNC=0: true is framework acceptance, not a completed target/UI effect.
                accepted = method.invoke(targetManager, event, 0) as Boolean
                returned = true
                accepted
            } finally {
                if (timed) {
                    val completed = SystemClock.uptimeMillis()
                    Log.i("EyeProjectorInput", "managed_motion phase=$phase stage=invoke_return uptimeMs=$completed invokeUptimeMs=$invoked elapsedMs=${completed - invoked} returned=$returned accepted=$accepted tid=${Process.myTid()}")
                }
            }
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            event.recycle()
        }
    }

    /** Invert LogicalDisplay's centered aspect-fit projection into the source's logical space. */
    private fun sourceCoordinates(target: Tap, mapping: ManagedProjectionDisplay.InputMapping): Pair<Float, Float> {
        return mapping.coordinates.videoToInput(UiPoint(target.x.toDouble(), target.y.toDouble()))
    }
}
