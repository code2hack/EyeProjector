package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** Head pointer selects; only the original Function/Anchor UP commits. Touches are consumed. */
@SuppressLint("ViewConstructor")
internal class AnchorRegistrationOverlay(context: Context,
    private val highlighted: (AnchorRegistrationAction?) -> Unit) : FrameLayout(context) {
    private var target: AnchorRegistrationHighlight? = null
    private var menu: AnchorRegistrationMenu? = null
    private var selected: AnchorRegistrationAction? = null
    private var choosing = false
    private class Presentation { var hideAttempted = false }
    private var presentation: Presentation? = null
    private var admission = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { visibility = View.GONE; isClickable = true; setWillNotDraw(false) }

    fun show(bounds: AnchorRegistrationHighlight) {
        presentation = Presentation(); admission++
        target = bounds; choosing = false; selected = null
        menu = AnchorRegistrationMenu.layout(bounds, width, height, resources.displayMetrics.density)
        visibility = View.VISIBLE; invalidate()
    }
    fun pointer(x: Float, y: Float, available: Boolean) {
        if (presentation == null || choosing) return
        val bounds = target ?: return
        menu = AnchorRegistrationMenu.layout(bounds, width, height, resources.displayMetrics.density)
        selected = if (available) menu?.at(x, y) else null
        highlighted(selected); invalidate()
    }
    fun waiting() { choosing = true; invalidate() }
    fun prepareDismiss(): InputRetirement {
        val old = presentation
        presentation = null; target = null; menu = null; selected = null
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
        target?.let {
            paint.color = Color.YELLOW; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), paint)
        }
        val box = menu ?: return
        for ((index, action) in AnchorRegistrationAction.entries.withIndex()) {
            val left = box.left + index * box.width / 3
            paint.style = Paint.Style.FILL
            paint.color = if (!choosing && selected == action) Color.YELLOW else Color.argb(235, 0, 0, 0)
            canvas.drawRect(left, box.top, left + box.width / 3, box.top + box.height, paint)
            paint.color = if (!choosing && selected == action) Color.BLACK else Color.WHITE
            paint.textSize = 14f * resources.displayMetrics.scaledDensity; paint.textAlign = Paint.Align.CENTER
            canvas.drawText(when (action) { AnchorRegistrationAction.CLICK -> "Click"; AnchorRegistrationAction.HOLD -> "Hold";
                AnchorRegistrationAction.CANCEL -> "Cancel" }, left + box.width / 6,
                box.top + box.height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    override fun performClick(): Boolean = true
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = true
}
