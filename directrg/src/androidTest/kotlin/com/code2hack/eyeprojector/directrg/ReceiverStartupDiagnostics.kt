package com.code2hack.eyeprojector.directrg

/** Existing test-driver freshness intent, including current-run and current-boot interval bounds. */
internal fun receiverStatusRejection(
    state: String, startedNs: Long?, elapsedMs: Long?, runStartNs: Long?, readNowNs: Long,
): String? {
    if (runStartNs == null) return "RUN_NOT_BOUND"
    if (startedNs == null || elapsedMs == null) return "MISSING_MONOTONIC_FIELDS"
    if (startedNs < runStartNs) return "HISTORICAL_SESSION_STATUS"
    if (startedNs > readNowNs) return "FUTURE_SESSION_START"
    val intervalNs = readNowNs - startedNs
    if (intervalNs < 0 || elapsedMs < 0 || elapsedMs > intervalNs / 1_000_000L) return "INVALID_OR_FUTURE_STATUS_INTERVAL"
    // Validate before multiplication: an imported huge elapsed value cannot overflow into freshness.
    val reportAgeNs = intervalNs - elapsedMs * 1_000_000L
    if (state in listOf("STREAMING", "CONNECTING", "WAITING_FOR_VIDEO") && reportAgeNs >= 3_000_000_000L) {
        return "STALE_ACTIVE_SESSION_STATUS"
    }
    return null
}

internal fun diagnosticClockLong(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    else -> null
}

/** Only exported to the owned private diagnostic report, never to the public console. */
internal fun startupErrorFields(error: Throwable): Map<String, Any?> = mapOf(
    "errorClass" to error.javaClass.name, "errorMessage" to error.message)
