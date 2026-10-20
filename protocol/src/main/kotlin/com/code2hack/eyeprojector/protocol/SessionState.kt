package com.code2hack.eyeprojector.protocol

enum class SessionPhase {
    INACTIVE, STARTING, AVAILABLE, CONNECTING, STREAMING, STOPPING, FAILED, UNKNOWN;

    val active: Boolean get() = this in setOf(STARTING, AVAILABLE, CONNECTING, STREAMING)
}

/** A durable pending intent acknowledges reconciliation, not actual backend release. */
enum class AttemptDisposition { OBSERVED, CANCEL_PENDING, CANCELED, RETIRED, RETRYABLE }

/** Command admission/cancellation facts are separate from the backend's actual phase and release. */
data class AttemptReceipt(val id: String, val owner: String?, val disposition: AttemptDisposition)

/** AVAILABLE means the authenticated host listener is ready; STREAMING requires a Receiver render. */
data class SessionState(val epoch: String, val phase: SessionPhase, val detail: String = "", val receipt: AttemptReceipt? = null)
