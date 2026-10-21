package com.code2hack.eyeprojector.directhost.apps

import android.content.Context
import android.widget.LinearLayout

/** Real menu rendering is exercised by instrumentation, not this source boundary. */
class ChosenAppsPanel(context: Context) : LinearLayout(context) {
    var onAppSelected: ((ChosenApp) -> Unit)? = null
    var onAppScale: ((ChosenApp) -> Unit)? = null
}
