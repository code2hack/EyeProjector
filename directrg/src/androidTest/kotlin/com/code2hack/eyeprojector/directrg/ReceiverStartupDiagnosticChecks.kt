package com.code2hack.eyeprojector.directrg

internal fun receiverStartupDiagnosticChecks() {
    val run = 14_300_000_000_000L
    val now = 14_346_830_000_000L
    val oldBootStart = 23_461_317_476_307L
    check(receiverStatusRejection("STOPPED", oldBootStart, 1_352_926, run, now) == "FUTURE_SESSION_START")
    check(receiverStatusRejection("STREAMING", oldBootStart, 1_352_926, run, now) == "FUTURE_SESSION_START")
    check(receiverStatusRejection("STREAMING", run - 1, 0, run, now) == "HISTORICAL_SESSION_STATUS")
    check(receiverStatusRejection("STREAMING", now - 1_000_000_000, 1001, run, now) == "INVALID_OR_FUTURE_STATUS_INTERVAL")
    check(receiverStatusRejection("STREAMING", now - 1_000_000_000, Long.MAX_VALUE, run, now) == "INVALID_OR_FUTURE_STATUS_INTERVAL")
    check(receiverStatusRejection("STOPPED", null, 0, run, now) == "MISSING_MONOTONIC_FIELDS")
    check(receiverStatusRejection("STOPPED", now, -1, run, now) == "INVALID_OR_FUTURE_STATUS_INTERVAL")
    check(receiverStatusRejection("STREAMING", now - 1_000_000_000, 999, run, now) == null)
    check(receiverStatusRejection("STREAMING", now - 3_000_000_000, 0, run, now) == "STALE_ACTIVE_SESSION_STATUS")
    check(receiverStatusRejection("STOPPED", now - 5_000_000_000, 0, run, now) == null) // Existing terminal age behavior.
    check(receiverStatusRejection("STOPPED", now - 5_000_000_000, 0, now - 1_000_000_000, now) == "HISTORICAL_SESSION_STATUS")
    check(receiverStatusRejection("STREAMING", now, 0, null, now) == "RUN_NOT_BOUND")
    check(diagnosticClockLong(1L) == 1L && diagnosticClockLong(1) == 1L)
    check(diagnosticClockLong("1") == null && diagnosticClockLong(1.0) == null)
    val original = IllegalStateException("No current projection Surface")
    val fields = startupErrorFields(original)
    check(fields["errorClass"] == "java.lang.IllegalStateException")
    check(fields["errorMessage"] == original.message)
}
