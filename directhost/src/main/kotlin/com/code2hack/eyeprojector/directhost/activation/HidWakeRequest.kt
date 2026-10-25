package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.directhost.ProjectionContext

/** Pure original scope. It carries no mapping address, Binder call, policy gate or latest-owner lookup. */
internal data class HidInputAuthority(
    val handle: ActivationHandle?,
    val attemptId: String?,
    val context: ProjectionContext,
    val producerVersion: Long,
    val admissionRevision: Long,
    val regionGeneration: Long,
)
internal data class HidWakeRequest(
    val disposition: PhoneHidDisposition,
    val authority: HidInputAuthority,
    val regionId: String? = null,
    val word: Long? = null,
    val setupReason: HidSetupReason? = null,
    val admitted: Boolean = false, // An original accepted wake obligation, not current input permission.
)
