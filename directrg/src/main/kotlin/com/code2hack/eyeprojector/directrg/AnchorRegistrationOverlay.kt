package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Glasses-local hierarchy: all touches terminate here, including outside the three choices. */
@SuppressLint("ViewConstructor")
internal class AnchorRegistrationOverlay(context: Context, choose: (AnchorRegistrationAction) -> Unit) : FrameLayout(context) {
    private var highlight: AnchorRegistrationHighlight? = null
    private class Presentation { var hideAttempted = false }
    private var presentation: Presentation? = null
    private var admission = 0L
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 3f }
    val buttons = listOf("Click" to AnchorRegistrationAction.CLICK, "Hold" to AnchorRegistrationAction.HOLD,
        "Cancel" to AnchorRegistrationAction.CANCEL).map { (label, action) ->
        Button(context).apply { text = label; setOnClickListener { choose(action) } }
    }

    init {
        visibility = View.GONE
        isClickable = true
        setWillNotDraw(false)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            addView(TextView(context).apply {
                text = "Choose Anchor mode"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                buttons.forEach { addView(it) }
            })
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    fun show(bounds: AnchorRegistrationHighlight) {
        val original = Presentation()
        presentation = original
        val revision = ++admission
        highlight = bounds
        for (button in buttons) {
            if (presentation !== original || admission != revision) return
            button.isEnabled = true
        }
        if (presentation !== original || admission != revision) return
        visibility = View.VISIBLE
        if (presentation === original && admission == revision) invalidate()
    }

    fun waiting() {
        val original = presentation
        val revision = if (original != null) ++admission else admission
        for (button in buttons.take(2)) {
            if (presentation !== original || admission != revision) return
            button.isEnabled = false
        }
    }
    fun prepareDismiss(): InputRetirement {
        val old = presentation
        presentation = null
        highlight = null
        val baseline = admission
        return InputRetirement({
            if (old != null && !old.hideAttempted) {
                old.hideAttempted = true
                if (presentation == null && admission == baseline) visibility = View.GONE
            }
        }, { admission != baseline })
    }
    fun dismiss() = prepareDismiss().finishOriginal()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        highlight?.let { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), outline) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = true
}
