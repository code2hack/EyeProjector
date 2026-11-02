package com.code2hack.eyeprojector.directhost

/** Fixed app-owned commands scoped to the current projection. */
internal object UserControls {
    const val ORDINARY = 0
    const val STOP = 1
    const val RECOGNITION = 2 // Raw Function may still classify; CANCELING always discards.
}
