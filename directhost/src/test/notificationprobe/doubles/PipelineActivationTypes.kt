package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.protocol.ActivationGoal
import com.code2hack.eyeprojector.protocol.ReceiverIncarnation

/** Type/identity boundary only; no production activation, permit transition or claim is modeled. */
internal enum class ActivationCause { PHONE_START, GLASSES_LAUNCH, HID_ATTACHMENT, HID_PRESS, NOTIFICATION }
internal class ActivationHandle internal constructor(val id: String, val cause: ActivationCause,
    val demandId: String? = null, val goal: ActivationGoal = ActivationGoal.USABLE)
internal data class ActivationBinding(val activationId: String, val demandId: String?, val goal: ActivationGoal,
    val wakeRevision: Long, val attemptId: String?, val incarnation: ReceiverIncarnation?, val presentationRevision: Long?)
internal class ActivationAttempt internal constructor(val handle: ActivationHandle, val id: String)
internal data class ActivationReady<T : Any>(val handle: ActivationHandle, val attempt: ActivationAttempt, val target: T,
    val binding: ActivationBinding? = null, val authority: Long = 0)
internal class LaunchCommitPermit { enum class State { PENDING, COMMITTED, RETIRED } }
