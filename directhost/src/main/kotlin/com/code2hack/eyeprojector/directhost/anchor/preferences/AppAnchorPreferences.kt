package com.code2hack.eyeprojector.directhost.anchor.preferences

import android.content.Context
import android.util.Log
import java.io.File

/** One app-process instance, shared by Activity menus, registration and the future input owner. */
object AppAnchorPreferences {
    private var store: AppAnchorStore? = null

    @Synchronized fun open(context: Context): AppAnchorStore = store ?: AppAnchorStore(
        FileAppAnchorStorage(File(context.applicationContext.filesDir, "projection_app_anchors")),
        onObserverFailure = { Log.e("AppAnchorPreferences", "Anchor preference observer failed", it) },
    ).also { store = it }
}
