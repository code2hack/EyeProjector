package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.Step
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.observed

/** The actual external DOWN captures the assignment row under the existing local head pointer. */
@SuppressLint("ViewConstructor")
internal class HidBindingsPanel(
    context: Context,
    private val bindings: HidBindings,
    private val input: HidKeyInput,
    back: () -> Unit,
) : LinearLayout(context) {
    val buttons = mutableListOf<Button>()
    private val message = TextView(context).apply { setTextColor(Color.WHITE) }
    private val entries = linkedMapOf<HidAction, Button>()
    private val receivedLine = TextView(context).apply {
        setTextColor(Color.WHITE)
        maxLines = 1
        contentDescription = "Last received external HID key"
    }
    private var receivedKey: Pair<Int, HidButton>? = null
    private var receivedDown: Long? = null
    private var receivedCount = 0L
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(TextView(context).apply { text = "HID key bindings"; textSize = 22f; setTextColor(Color.WHITE) })
        addView(message)
        addView(receivedLine, LayoutParams(-1, (32 * resources.displayMetrics.density).toInt()))
        // Navigation/Lock assignment rows are introduced with their dispatch in V03-06.
        for (action in listOf(HidAction.TAP, HidAction.SCROLL_DOWN, HidAction.SCROLL_UP, HidAction.FUNCTION)) {
            entries[action] = button()
        }
        for (assignmentButton in entries.values) {
            addView(assignmentButton, LayoutParams(-1, -2))
        }
        addView(button().apply {
            text = "Reset all HID bindings"
            setOnClickListener { input.resetAllBindings(); refresh() }
        }, LayoutParams(-1, -2))
        addView(button().apply { text = "Back"; setOnClickListener { back() } }, LayoutParams(-1, -2))
        refresh()
    }
    /** Arrival feedback is separate from capture/save; this line always reserves the same space. */
    fun received(event: HidKeyInput.Event) {
        if (event.phase != HidKeyInput.Phase.DOWN || event.repeat != 0) return
        val key = Pair(event.attachmentId, event.binding.button)
        if (receivedKey == key && receivedDown == event.downTimeMs) return
        receivedCount = if (receivedKey == key) receivedCount + 1 else 1
        receivedKey = key
        receivedDown = event.downTimeMs
        receivedLine.text = if (receivedCount == 1L) event.binding.keyLabel else "${event.binding.keyLabel} x$receivedCount"
    }
    fun refresh() {
        for ((action, button) in entries) {
            val binding = bindings.binding(action)
            val assignment = binding?.let {
                "${it.deviceName}: ${it.keyLabel} (key ${it.button.keyCode}, scan ${it.button.scanCode})"
            } ?: "Unassigned"
            button.text = "${label(action)}: $assignment"
            button.isSelected = input.selected == action
        }
        message.text = bindings.error ?: input.selected?.let { "Release the external HID button for ${label(it)}." }
            ?: "Point at an action, then press and release an external HID button."
    }
    /** Pointer coordinates are root-local; visible rectangles are in the topmost View's coordinates. */
    fun actionAt(point: PointerPosition, root: View, trace: HidDecisionTrace.Event? = null): HidAction? {
        if (!observed(trace, Step.HIT_POINT, point.available && point.x.isFinite() && point.y.isFinite()) ||
            !observed(trace, Step.HIT_ROOT_SHOWN, root.isShown) ||
            !observed(trace, Step.HIT_PANEL_SHOWN, isShown) ||
            !observed(trace, Step.HIT_PANEL_ENABLED, isEnabled)) return null
        val rootVisible = Rect()
        val globalOffset = Point()
        val rootClipped = root.getGlobalVisibleRect(rootVisible, globalOffset)
        trace?.record(Step.HIT_ROOT_CLIP, if (rootClipped) HidDecisionTrace.Fact.YES else HidDecisionTrace.Fact.NO,
            bounds = if (rootClipped) rootVisible else null, offset = if (rootClipped) globalOffset else null)
        if (!rootClipped) return null
        val x = point.x + globalOffset.x
        val y = point.y + globalOffset.y
        if (observed(trace, Step.HIT_ROOT_OUTSIDE, x < rootVisible.left || x >= rootVisible.right ||
                y < rootVisible.top || y >= rootVisible.bottom, bounds = rootVisible, offset = globalOffset)) return null
        val visible = Rect()
        val target = entries.entries.firstOrNull { (action, row) ->
            observed(trace, Step.HIT_ROW_SHOWN, row.isShown, action) &&
                observed(trace, Step.HIT_ROW_ENABLED, row.isEnabled, action) &&
                row.getGlobalVisibleRect(visible).also { clipped ->
                    trace?.record(Step.HIT_ROW_CLIP, if (clipped) HidDecisionTrace.Fact.YES else HidDecisionTrace.Fact.NO,
                        action, bounds = if (clipped) visible else null)
                } && observed(trace, Step.HIT_ROW_POINT,
                    x >= visible.left && x < visible.right && y >= visible.top && y < visible.bottom,
                    action, bounds = visible)
        }?.key
        trace?.record(Step.HIT_RESULT, if (target == null) HidDecisionTrace.Fact.NO else HidDecisionTrace.Fact.YES, target)
        return target
    }
    private fun button() = Button(context).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(1, Color.WHITE) }
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        buttons.add(this)
    }
    private fun label(action: HidAction) = when (action) {
        HidAction.TAP -> "Tap"
        HidAction.SCROLL_DOWN -> "Scroll down"
        HidAction.SCROLL_UP -> "Scroll up"
        HidAction.FUNCTION -> "Function button"
        HidAction.BACK_HOME -> "Back/Home"
        HidAction.LOCK -> "Lock"
    }
}
