package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Four assignments use the existing local head pointer and ordinary View hit testing. */
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
            entries[action] = button().apply { setOnClickListener { input.beginCapture(action) } }
        }
        for ((action, assignmentButton) in entries) {
            val clear = button().apply {
                text = "Clear ${label(action)}"
                contentDescription = "Clear ${label(action)} binding"
                setOnClickListener { input.clearBinding(action); refresh() }
            }
            addView(LinearLayout(context).apply {
                addView(assignmentButton, LayoutParams(0, -2, 1f))
                addView(clear, LayoutParams(-2, -2))
            })
        }
        addView(button().apply { text = "Back"; setOnClickListener { back() } })
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
        message.text = bindings.error ?: input.selected?.let { "Press and release an external HID button for ${label(it)}." }
            ?: "Select a control, then press and release an external HID button."
    }
    private fun button() = Button(context).apply {
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
