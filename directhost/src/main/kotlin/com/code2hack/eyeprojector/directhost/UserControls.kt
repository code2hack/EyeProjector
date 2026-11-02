package com.code2hack.eyeprojector.directhost

/** Fixed app-owned commands; the original Phone Anchor owner remains the sole input gate. */
internal object UserControls {
    const val ORDINARY = 0
    const val STOP = 1
    const val RECOGNITION = 2 // Raw Function may still classify; CANCELING always discards.
}
