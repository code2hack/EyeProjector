package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Stepped controls registered with the same local-pointer hit testing as Connect. */
@SuppressLint("ViewConstructor") // Constructed with the local settings and change callback.
internal class LocalInputSettingsPanel(
    context: Context,
    private val settings: LocalInputSettings,
    private val changed: () -> Unit,
) : LinearLayout(context) {
    val buttons = mutableListOf<Button>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(row("Pointer X", { String.format(Locale.getDefault(), "%.1f×", settings.pointerXGain) },
            { if (it < 0) settings.pointerXTenths > 1 else settings.pointerXTenths < 60 }, settings::stepPointerX))
        addView(row("Pointer Y", { String.format(Locale.getDefault(), "%.1f×", settings.pointerYGain) },
            { if (it < 0) settings.pointerYTenths > 1 else settings.pointerYTenths < 60 }, settings::stepPointerY))
        addView(row("Scroll", { "${settings.scrollSpeed} nominal px/s" },
            { if (it < 0) settings.scrollSpeed > 10 else settings.scrollSpeed < 1200 }, settings::stepScroll))
        addView(button().apply {
            fun refresh() {
                text = "Head-motion scrolling: ${if (settings.headScrollEnabled) "On" else "Off"}"
                stateDescription = if (settings.headScrollEnabled) "On" else "Off"
            }
            refresh()
            setOnClickListener {
                settings.setHeadScroll(!settings.headScrollEnabled)
                refresh()
                changed()
            }
        })
    }

    private fun button() = Button(context).apply {
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.WHITE, Color.LTGRAY),
        ))
        background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(1, Color.WHITE) }
        minimumWidth = (48 * resources.displayMetrics.density).toInt()
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        buttons.add(this)
    }

    private fun row(label: String, value: () -> String, enabled: (Int) -> Boolean, step: (Int) -> Boolean): LinearLayout {
        val number = TextView(context).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val minus = button().apply { text = "−"; contentDescription = "Decrease $label" }
        val plus = button().apply { text = "+"; contentDescription = "Increase $label" }
        fun refresh() {
            number.text = "$label ${value()}"
            minus.isEnabled = enabled(-1); plus.isEnabled = enabled(1)
        }
        for ((button, direction) in listOf(minus to -1, plus to 1)) {
            button.setOnClickListener {
                step(direction)
                refresh()
                changed()
            }
        }
        refresh()
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(minus); addView(number, LayoutParams(0, -2, 1f)); addView(plus)
        }
    }
}
