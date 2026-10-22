package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Test

class LaunchOperationDiagnosticsTest {
    private fun event(result: ProjectionDisplayState? = null) = LaunchOperationDiagnostics.Event(
        123, LaunchOperationDiagnostics.Kind.LAUNCH, "example.chosen",
        LaunchOperationDiagnostics.Stage.RESULT, LaunchOperationDiagnostics.Outcome.RETURNED, true, result = result)

    @Test fun originalNullUnavailableAndFailedAreDistinctWithoutAnAcknowledgmentClaim() {
        val missing = LaunchOperationDiagnostics.format(event())
        val unavailable = LaunchOperationDiagnostics.format(event(ProjectionDisplayState(status = ProjectionLaunchStatus.UNAVAILABLE)))
        val failed = LaunchOperationDiagnostics.format(event(ProjectionDisplayState(77, "child", 145, "example.chosen",
            ProjectionLaunchStatus.FAILED, 255, "example.chosen/.Main", "example.home")))
        assertTrue(missing.contains("resultPresent=false resultStatus=none"))
        assertTrue(unavailable.contains("resultPresent=true resultStatus=UNAVAILABLE"))
        assertTrue(failed.contains("resultPresent=true resultStatus=FAILED resultRequestId=77 requestedPackage=example.chosen commandExit=255 displayId=145"))
        assertTrue(failed.contains("launchedComponent=example.chosen/.Main foregroundPackage=example.home"))
        assertTrue(failed.endsWith("scope=existing_operation_metadata_not_ack"))
    }

    @Test fun rawGenerationContextAndExceptionTextNeverEnterTheFixedRecord() {
        val owner = "private-owner-epoch"
        val token = "private-projection-token"
        val result = ProjectionDisplayState(generation = "$token:scrcpy-1234abcd", context = ProjectionContext(owner, token))
        val text = LaunchOperationDiagnostics.format(event(result).copy(error = SecurityException("$owner $token /private/key https://private.endpoint")))
        for (secret in listOf(owner, token, "scrcpy-1234abcd", "/private/key", "private.endpoint", "SecurityException")) assertFalse(text.contains(secret))
        assertTrue(text.contains("exceptionFamily=SECURITY"))
        assertTrue(Regex("generationDigest=[a-f0-9]{64} generationBasis=sha256_in_memory_not_raw_identity").containsMatchIn(text))
    }

    @Test fun freeTextAndControlCharactersAreRedactedRatherThanEscapedIntoLogs() {
        val hostile = "account text\nsecret=password"
        val text = LaunchOperationDiagnostics.format(event(ProjectionDisplayState(
            requestedPackage = hostile, launchedComponent = "/private/key", foregroundPackage = "https://endpoint/secret"))
            .copy(selectedPackage = hostile, error = Exception(hostile)))
        assertFalse(text.contains("account")); assertFalse(text.contains("password")); assertFalse(text.contains("\n"))
        assertFalse(text.contains("/private/key")); assertFalse(text.contains("endpoint"))
        assertTrue(text.contains("selectedPackage=redacted")); assertTrue(text.contains("exceptionFamily=OTHER"))
    }

    @Test fun constructionAndSinkFailuresCannotEscapeReporting() {
        var attempts = 0
        LaunchOperationDiagnostics.emit({ throw AssertionError("synthetic formatter-input failure") }, { attempts++ })
        assertEquals(0, attempts)
        LaunchOperationDiagnostics.emit({ event() }, { attempts++; throw AssertionError("synthetic sink failure") })
        assertEquals(1, attempts)
    }

    @Test fun shortCircuitedChecksRemainExplicitlyUnevaluated() {
        val text = LaunchOperationDiagnostics.format(event().copy(stage = LaunchOperationDiagnostics.Stage.PRE_CALL,
            outcome = LaunchOperationDiagnostics.Outcome.SKIPPED,
            match = LaunchOperationDiagnostics.Match(false, null, null, true, true, false)))
        assertTrue(text.contains("reason=NO_SERVICE servicePresent=false sameService=not_evaluated sameBinding=not_evaluated"))
        assertTrue(text.contains("resultContextMatch=not_evaluated"))
    }
}
