package com.code2hack.eyeprojector.directhost

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Scroll
import com.code2hack.eyeprojector.protocol.Tap
import com.code2hack.eyeprojector.protocol.Touch

/** Pointer injection into this original VDM source; keys retain the stock scrcpy route. */
internal class ManagedProjectionInput(
    private val owner: ManagedProjectionDisplay,
) : PhoneTouchDispatcher.MotionTarget {
    private var heldContact: Long? = null
    private var heldDownTime = 0L

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

    @Synchronized override fun touch(event: Touch): Boolean =
        owner.withInputMapping(event.target.geometry) { displayId, mapping ->
            val now = SystemClock.uptimeMillis()
            if (event.phase != Touch.Phase.DOWN && heldContact != event.contactId) {
                return@withInputMapping event.phase == Touch.Phase.CANCEL && heldContact == null
            }
            val down = if (event.phase == Touch.Phase.DOWN) now else heldDownTime
            val accepted = motion(displayId, mapping, event.target,
                event.phase.action, down, now, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER,
                if (event.phase == Touch.Phase.DOWN || event.phase == Touch.Phase.MOVE) 1f else 0f)
            if (accepted) {
                if (event.phase == Touch.Phase.DOWN) {
                    heldContact = event.contactId
                    heldDownTime = down
                } else if (event.phase == Touch.Phase.UP || event.phase == Touch.Phase.CANCEL) {
                    heldContact = null
                    heldDownTime = 0L
                }
            }
            accepted
        }

    @Synchronized override fun tap(event: Tap): Boolean =
        owner.withInputMapping(event.geometry) { displayId, mapping ->
            // The dispatcher cancels a held contact before a discrete tap, as on its legacy path.
            val down = SystemClock.uptimeMillis()
            if (!motion(displayId, mapping, event, MotionEvent.ACTION_DOWN,
                    down, down, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 1f)) {
                return@withInputMapping false
            }
            val released = motion(displayId, mapping, event, MotionEvent.ACTION_UP,
                down, SystemClock.uptimeMillis(), InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, 0f)
            if (!released) motion(displayId, mapping, event, MotionEvent.ACTION_CANCEL,
                down, SystemClock.uptimeMillis(), InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, 0f)
            released
        }

    @Synchronized override fun scroll(event: Scroll): Boolean =
        owner.withInputMapping(event.target.geometry) { displayId, mapping ->
            val now = SystemClock.uptimeMillis()
            motion(displayId, mapping, event.target, MotionEvent.ACTION_SCROLL,
                now, now, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, 0f,
                verticalScroll = -event.pixels.toFloat() / 64f)
        }

    private fun motion(
        displayId: Int,
        mapping: ManagedProjectionDisplay.InputMapping,
        target: Tap,
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
        val (mappedX, mappedY) = sourceCoordinates(target, mapping)
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
            // ASYNC=0: returned true is framework injection acceptance, not a target/UI effect.
            injectMethod.invoke(manager, event, 0) as Boolean
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
        val source = mapping.source
        val physical = source.physical
        val px = target.x.toDouble() * physical.width / mapping.video.width
        val py = target.y.toDouble() * physical.height / mapping.video.height
        val rotated = source.rotation == Surface.ROTATION_90 || source.rotation == Surface.ROTATION_270
        val orientedWidth = if (rotated) physical.height else physical.width
        val orientedHeight = if (rotated) physical.width else physical.height
        val (orientedX, orientedY) = when (source.rotation) {
            Surface.ROTATION_0 -> px to py
            Surface.ROTATION_90 -> py to (physical.width - px)
            Surface.ROTATION_180 -> (physical.width - px) to (physical.height - py)
            Surface.ROTATION_270 -> (physical.height - py) to px
            else -> error("Unexpected display rotation")
        }
        val logical = source.logical
        val widthConstrained = orientedWidth.toLong() * logical.height <
            orientedHeight.toLong() * logical.width
        val frameWidth = if (source.scalingDisabled) logical.width else if (widthConstrained) orientedWidth else
            (logical.width.toLong() * orientedHeight / logical.height).toInt()
        val frameHeight = if (source.scalingDisabled) logical.height else if (widthConstrained)
            (logical.height.toLong() * orientedWidth / logical.width).toInt() else orientedHeight
        val left = (orientedWidth - frameWidth) / 2
        val top = (orientedHeight - frameHeight) / 2
        val x = ((orientedX - left) * logical.width / frameWidth).toFloat()
            .coerceIn(0f, (logical.width - 1).toFloat())
        val y = ((orientedY - top) * logical.height / frameHeight).toFloat()
            .coerceIn(0f, (logical.height - 1).toFloat())
        return x to y
    }
}
