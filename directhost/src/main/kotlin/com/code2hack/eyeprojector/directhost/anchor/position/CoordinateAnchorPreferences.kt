package com.code2hack.eyeprojector.directhost.anchor.position

import android.content.Context
import java.io.File

/** Both production routes obtain this app-process owner. Historical descriptor storage is untouched. */
object CoordinateAnchorPreferences {
    private var controller: CoordinateAnchorController? = null

    @Synchronized fun open(context: Context): CoordinateAnchorController = controller ?:
        CoordinateAnchorController(FileAnchorPositionStorage(
            File(context.applicationContext.filesDir, "projection_anchor_positions"),
        )).also { controller = it }
}
