package android.widget

import android.content.Context

/** Records the actual AnchorFeedbackUi output, without invoking the Android widget runtime. */
object Toast {
    val messages = mutableListOf<String>()
    const val LENGTH_SHORT = 0
    fun makeText(context: Context, text: CharSequence, length: Int): Toast {
        messages.add(text.toString()); return this
    }
    fun show() = Unit
}
