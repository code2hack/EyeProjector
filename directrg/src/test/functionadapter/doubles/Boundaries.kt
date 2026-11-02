package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*

internal class Boundary {
    var visibility = 8
    var width = 480
    var height = 640
    var position = PointerPosition(120f, 240f, true)
    var onPosition: () -> Unit = {}
    var calls = 0
    fun inputPosition(): PointerPosition { onPosition(); return position }
    fun refresh() { calls++ }
    fun cancel(at: Long) { calls++ }
    fun cancel(owner: Any, at: Long) { calls++ }
    fun accept(owner: Any, event: PadGestureRecognizer.Event) { calls++ }
}

/** Only the surrounding Session API; actual Session transport/currentness has its own tests. */
internal class ProjectionSession {
    var fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 1, ReceiverUsabilityMode.PROJECTED)
    val received = mutableListOf<CapturedFunctionIntent<ProjectionSession>>()
    fun captureFunctionContext(point: HeadPointerState): FunctionIntentContext? =
        if (point.available) FunctionIntentContext(fact, point) else null
    fun functionIntent(captured: CapturedFunctionIntent<ProjectionSession>): Boolean { received.add(captured); return true }
}
