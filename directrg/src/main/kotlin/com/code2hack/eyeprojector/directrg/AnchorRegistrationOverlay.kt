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
        highlight = bounds
        buttons.forEach { it.isEnabled = true }
        visibility = View.VISIBLE
        invalidate()
    }

    fun waiting() { buttons.take(2).forEach { it.isEnabled = false } }
    fun dismiss() { visibility = View.GONE; highlight = null }

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
