package com.code2hack.eyeprojector.protocol

enum class SessionPhase {
    INACTIVE, STARTING, AVAILABLE, CONNECTING, STREAMING, STOPPING, FAILED, UNKNOWN;

    val active: Boolean get() = this in setOf(STARTING, AVAILABLE, CONNECTING, STREAMING)
}

/** AVAILABLE means the authenticated host listener is ready; STREAMING requires a Receiver render. */
data class SessionState(val epoch: String, val phase: SessionPhase, val detail: String = "")
