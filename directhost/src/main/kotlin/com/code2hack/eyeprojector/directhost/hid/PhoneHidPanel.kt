package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import java.io.Closeable

/** Select a physical group, establish interception, then learn complete inputs. */
internal class PhoneHidPanel(context: Context, private val runtime: PhoneHidRuntime, back: () -> Unit) : LinearLayout(context) {
    private val input = runtime.controller
    private val availability = text()
    private val message = text()
    private val received = text().apply {
        maxLines = 1
        contentDescription = "Last received pinned HID input"
    }
    private val devices = Spinner(context)
    private val pin = button("Pin selected device") { runtime.pin.pinSelected() }
    private val rows = linkedMapOf<PhoneHidAction, Button>()
    private var deviceKeys = emptyList<String>()
    private var deviceLabels = emptyList<String>()
    private var updatingDevices = false
    private var subscription: Closeable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(text().apply { text = "Pinned HID controls"; textSize = 22f })
        addView(availability)
        addView(devices, LayoutParams(-1, -2))
        devices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingDevices) deviceKeys.getOrNull(position)?.let(runtime.pin::select)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(button("Refresh connected devices") { runtime.refresh() }, LayoutParams(-1, -2))
        addView(pin, LayoutParams(-1, -2))
        addView(message)
        addView(received, LayoutParams(-1, (32 * resources.displayMetrics.density).toInt()))
        for (action in listOf(PhoneHidAction.TAP, PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP,
            PhoneHidAction.BACK_HOME, PhoneHidAction.LOCK, PhoneHidAction.FUNCTION)) rows[action] = button("") { input.select(action) }.also {
            addView(it, LayoutParams(-1, -2))
        }
        addView(button("Unpin and reset Phone bindings") { runtime.pin.unpinAndReset() }, LayoutParams(-1, -2))
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
        val owner = runtime.pin.state
        val pinned = runtime.pin.pinnedKey
        val choices = owner.devices.filter { pinned == null || it.key == pinned }
        val names = choices.map { device ->
            "${device.name} (${device.vendorId}:${device.productId}) · ${device.collectionCount} collection(s)" +
                if (device.hasTouch) " · gestures" else ""
        }.ifEmpty {
            if (pinned == null) emptyList() else listOf("${owner.savedName ?: "Pinned device"} · disconnected")
        }
        val keys = if (pinned != null) listOf(pinned) else choices.map { it.key }
        if (keys != deviceKeys || names != deviceLabels) {
            updatingDevices = true
            deviceKeys = keys; deviceLabels = names
            devices.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                if (names.isEmpty()) listOf("No connected physical HID devices") else names)
            updatingDevices = false
        }
        val selection = deviceKeys.indexOf(runtime.pin.selectedKey)
        if (selection >= 0 && selection != devices.selectedItemPosition) {
            updatingDevices = true
            devices.setSelection(selection)
            updatingDevices = false
        }
        devices.isEnabled = runtime.pin.canSelectDevice && keys.isNotEmpty()
        pin.isEnabled = runtime.pin.canPinSelected
        pin.text = when {
            owner.intercepted -> "Device pinned"
            pinned != null -> "Retry pin / interception"
            else -> "Pin selected device"
        }
        availability.text = when {
            owner.intercepted -> "${owner.savedName ?: "Pinned device"}: intercepted (${owner.collectionCount} collections). Dedicated until Unpin, including unassigned inputs."
            owner.savedKey != null -> "Saved pin: ${owner.savedName ?: "device"}. Interception unavailable · ${owner.phase.lowercase()}."
            owner.phase == "RELEASING" -> "Releasing interception and clearing Phone assignments…"
            else -> "Select a connected physical HID device, then Pin it. Projection Stop keeps a pin active."
        }
        message.text = when {
            owner.error != null -> owner.error
            input.bindings.error != null -> input.bindings.error
            input.saving > 0 -> "Saving Phone bindings."
            !owner.intercepted -> "Assignments become available after interception starts. Existing saved bindings are retained until Unpin and reset."
            input.capturing -> "Release the button to assign it."
            input.selected != null -> "Press and release a button, or complete one gesture, to assign ${label(input.selected!!)}."
            else -> "Select an action, then use the pinned device. For Y05, press its physical Up button when assigning Scroll up, even if it sends a downward stroke."
        }
        received.text = input.receivedText
        rows.forEach { (action, row) ->
            val saved = input.bindings.binding(action)
            val binding = saved?.takeIf { value ->
                owner.savedKey?.let { PhoneHidPinRuntime.bindingMatchesDevice(value, it) } ?: true
            }
            val assignment = binding?.let { "${it.deviceName}: ${it.keyLabel}" }
                ?: if (owner.savedKey == null) "Unassigned" else "Unassigned for this device"
            row.text = "${label(action)}: $assignment"
            row.isSelected = input.selected == action
            row.isEnabled = owner.intercepted
        }
    }

    private fun text() = TextView(context).apply { setTextColor(Color.WHITE) }
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
        PhoneHidAction.BACK_HOME -> "Back/Home"
        PhoneHidAction.LOCK -> "Lock"
    }
}
