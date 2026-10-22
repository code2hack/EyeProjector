package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import java.io.Closeable

/** Phone-local key learning: a row stays selected for repeated replacement. */
internal class PhoneHidPanel(context: Context, private val runtime: PhoneHidRuntime, back: () -> Unit) : LinearLayout(context) {
    private val input = runtime.controller
    private val availability = TextView(context).apply { setTextColor(Color.WHITE) }
    private val message = TextView(context).apply { setTextColor(Color.WHITE) }
    private val received = TextView(context).apply {
        setTextColor(Color.WHITE); maxLines = 1
        contentDescription = "Last received external HID key"
    }
    private val rows = linkedMapOf<PhoneHidAction, Button>()
    private var subscription: Closeable? = null
    private var settingsFailed = false
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(TextView(context).apply { text = "HID key bindings"; textSize = 22f; setTextColor(Color.WHITE) })
        addView(availability)
        addView(button("Open accessibility settings") { settingsFailed = !runtime.openSettings(); refresh() })
        addView(message)
        addView(received, LayoutParams(-1, (32 * resources.displayMetrics.density).toInt()))
        for (action in PhoneHidAction.entries) rows[action] = button("") { input.select(action) }.also {
            addView(it, LayoutParams(-1, -2))
        }
        addView(button("Reset all HID bindings") { input.resetAll() }, LayoutParams(-1, -2))
        addView(button("Back", back), LayoutParams(-1, -2))
        refresh()
    }
    fun open() {
        subscription?.close()
        subscription = runtime.observe(::refresh)
        input.openPage()
        runtime.refresh()
        refresh()
    }
    fun close() {
        val old = subscription
        subscription = null
        old?.close()
        input.closePage()
    }
    fun refresh() {
        val error = input.bindings.error
        availability.text = when {
            input.connected -> "Key capture service connected. Android must deliver the peripheral's keys."
            runtime.enabled() -> "Key capture is enabled; waiting for the service to connect."
            else -> "Key capture is disabled. Enable EyeProjector HID key capture in accessibility settings."
        }
        message.text = when {
            settingsFailed -> "Accessibility settings could not be opened."
            error != null -> error
            input.saving > 0 -> "Saving HID bindings."
            input.capturing -> "Release the external HID button to assign it."
            input.selected != null -> "Press and release an external HID button to replace ${label(input.selected!!)}."
            else -> "Tap an action, then press and release an external HID button."
        }
        received.text = input.receivedText
        rows.forEach { (action, row) ->
            val binding = input.bindings.binding(action)
            val assignment = binding?.let { "${it.deviceName}: ${it.keyLabel} (key ${it.button.keyCode}, scan ${it.button.scanCode})" } ?: "Unassigned"
            row.text = "${label(action)}: $assignment"
            row.isSelected = input.selected == action
        }
    }
    private fun button(text: String, action: () -> Unit) = Button(context).apply {
        isAllCaps = false; this.text = text; setTextColor(Color.WHITE)
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
    private fun label(action: PhoneHidAction) = when (action) {
        PhoneHidAction.TAP -> "Tap"
        PhoneHidAction.SCROLL_DOWN -> "Scroll down"
        PhoneHidAction.SCROLL_UP -> "Scroll up"
        PhoneHidAction.FUNCTION -> "Function button"
    }
}
