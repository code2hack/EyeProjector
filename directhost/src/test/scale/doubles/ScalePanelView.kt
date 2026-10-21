package com.code2hack.eyeprojector.directhost.scale

import android.content.Context

/** Callback/render boundary only; no real View, pinch recognition or numeric formatting proof. */
class ScalePanelView(context: Context, private val onPinch: (Double) -> Unit, private val onClose: () -> Unit) {
    val renders = mutableListOf<AppScalePanelState?>()
    val latest: AppScalePanelState? get() = renders.lastOrNull()
    fun render(state: AppScalePanelState?) { renders.add(state) }
    fun pinch(factor: Double) { onPinch(factor) }
    fun close() { onClose() }
}
