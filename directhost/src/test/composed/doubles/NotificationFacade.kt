package com.code2hack.eyeprojector.directhost
/** Legacy non-notification adapter constructor/claim boundary; never a substitute claim. */
class PhoneSessionController {
    internal fun tryCommitActivationReady(ready: com.code2hack.eyeprojector.directhost.activation.ActivationReady<ProjectionContext>,
        permit: com.code2hack.eyeprojector.directhost.activation.LaunchCommitPermit): Boolean =
        error("Notification claim is outside this legacy adapter fixture")
}
