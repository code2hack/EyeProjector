package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.widget.Toast
import com.code2hack.eyeprojector.protocol.AnchorFeedback

/** Brief local feedback; output submission never claims the app performed its action. */
internal object AnchorFeedbackUi {
    fun show(context: Context?, status: AnchorFeedback) {
        val text = when (status) {
            AnchorFeedback.NO_ANCHOR -> "Hover a target and long-press Function to set an Anchor"
            AnchorFeedback.UNAVAILABLE, AnchorFeedback.RETIRED, AnchorFeedback.INPUT_REJECTED -> "Anchor unavailable"
            AnchorFeedback.HOLD_MODE -> "Anchor unavailable"
            AnchorFeedback.CLICK_SUBMITTED, AnchorFeedback.HOLD_SUBMITTED, AnchorFeedback.RELEASE_SUBMITTED -> null
        }
        if (context != null && text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
