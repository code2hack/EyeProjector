package com.code2hack.eyeprojector.protocol

/** Exact authorized444 seam only; replaced by canonical source when that source is available. */
enum class PhoneHidAction { TAP, SCROLL_DOWN, SCROLL_UP, FUNCTION }
enum class PhoneHidPhase { DOWN, UP, CANCEL }
data class PhoneHidEdge(
    val action: PhoneHidAction, val phase: PhoneHidPhase, val attachmentId: Int,
    val keyCode: Int, val scanCode: Int, val downTimeMs: Long, val eventTimeMs: Long,
    val repeatCount: Int = 0,
)
