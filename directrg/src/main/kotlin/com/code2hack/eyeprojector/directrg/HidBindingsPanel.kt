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
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(TextView(context).apply { text = "HID key bindings"; textSize = 22f; setTextColor(Color.WHITE) })
        addView(message)
        for (action in HidAction.entries) {
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
    fun actionAt(point: PointerPosition, root: View): HidAction? {
        if (!point.available || !point.x.isFinite() || !point.y.isFinite() ||
            !root.isShown || !isShown || !isEnabled) return null
        val rootVisible = Rect()
        val globalOffset = Point()
        if (!root.getGlobalVisibleRect(rootVisible, globalOffset)) return null
        val x = point.x + globalOffset.x
        val y = point.y + globalOffset.y
        if (x < rootVisible.left || x >= rootVisible.right ||
            y < rootVisible.top || y >= rootVisible.bottom) return null
        val visible = Rect()
        return entries.entries.firstOrNull { (_, row) ->
            row.isShown && row.isEnabled && row.getGlobalVisibleRect(visible) &&
                x >= visible.left && x < visible.right && y >= visible.top && y < visible.bottom
        }?.key
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
    }
}
