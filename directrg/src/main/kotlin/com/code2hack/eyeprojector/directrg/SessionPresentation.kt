package com.code2hack.eyeprojector.directrg

/** Presentation never owns or replaces the decoder, Surface or transport. */
internal class SessionPresentation {
    var panelVisible = true
        private set
    var live = false
        private set
    private var enteredFromProjection = false

    val projectedInput: Boolean get() = live && !panelVisible

    fun streaming() {
        if (!live && !enteredFromProjection) panelVisible = false
        live = true
    }

    fun showPanel() {
        enteredFromProjection = live
        panelVisible = true
    }

    fun back(): Boolean {
        if (!panelVisible || !live || !enteredFromProjection) return false
        panelVisible = false
        enteredFromProjection = false
        return true
    }

    fun disconnected() {
        live = false
        panelVisible = true
        enteredFromProjection = false
    }
}
