package com.code2hack.eyeprojector.directhost.widget

import android.content.Context
import com.code2hack.eyeprojector.directhost.PhoneSessionController

/**
 * Compile/attachment boundary only. The actual combined PhoneSessionController
 * calls this seam, but these scale scenarios do not subscribe widget observers,
 * publish launcher views, dispatch widget actions or model a widget instance.
 * Actual widget/controller/scale composition is exercised by the widget suite.
 */
object PhoneWidgetUpdates {
    var attachCalls = 0
        private set
    var lastAttached: PhoneSessionController? = null
        private set

    fun attach(context: Context, sessions: PhoneSessionController) {
        attachCalls++
        lastAttached = sessions
    }
}
