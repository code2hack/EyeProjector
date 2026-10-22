package com.code2hack.eyeprojector.directhost.scale

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.R
import java.util.Locale

/** Blank gesture surface: no decoder, video surface, preview or transform. */
@SuppressLint("ViewConstructor") // Programmatic view: callers must supply the gesture and close actions.
class ScalePanelView(context: Context, onPinch: (Double) -> Unit, onClose: () -> Unit) : LinearLayout(context) {
    private val value = TextView(context).apply {
        tag = "scale-applied"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val feedback = TextView(context).apply {
        tag = "scale-feedback"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
    }
    private var active = false
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (active) onPinch(detector.scaleFactor.toDouble())
            return true
        }
    })

    init {
        orientation = VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24)
        addView(Button(context).apply {
            tag = "scale-close"; setText(R.string.app_scale_close); setOnClickListener { onClose() }
        })
        addView(value)
        addView(feedback)
        addView(TextView(context).apply {
            setText(R.string.app_scale_hint); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        addView(object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }.apply { tag = "scale-gesture"; contentDescription = context.getString(R.string.app_scale_gesture) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        render(null)
    }

    fun render(state: AppScalePanelState?) {
        active = state?.canPinch == true
        value.text = state?.applied?.let { String.format(Locale.ROOT, "%.2f×", it) } ?: "—"
        feedback.setText(when (state?.detail) {
            "OPENING" -> R.string.app_scale_opening
            "RESTORING", "REQUESTING", "PENDING" -> R.string.app_scale_pending
            "READY", "OBSERVED" -> R.string.app_scale_observed
            "INTERRUPTED" -> R.string.app_scale_interrupted
            "REJECTED", "INVALID_SCALE" -> R.string.app_scale_rejected
            "UNSUPPORTED" -> R.string.app_scale_unsupported
            "SAVE_FAILED" -> R.string.app_scale_save_failed
            "TARGET_CHANGED" -> R.string.app_scale_target_changed
            "LAUNCH_UNCONFIRMED" -> R.string.app_scale_launch_unconfirmed
            else -> R.string.app_scale_unavailable
        })
    }
}
