package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.protocol.SessionControl

/** Existing request identities, without presentation fields that change on automatic retries. */
internal enum class RemoteControlKind { INTENT, CANCEL_TRIGGER, CANCEL_ACTIVATION, START, STOP }
internal data class RemoteControlKey(val kind: RemoteControlKind, val id: String, val admission: String = "")

internal fun SessionControl.Request.remoteControlKey(): RemoteControlKey? {
    val canceledTrigger = cancelTrigger
    val canceledActivation = cancelActivation
    val incomingIntent = intent
    return when {
        canceledTrigger != null -> RemoteControlKey(RemoteControlKind.CANCEL_TRIGGER, canceledTrigger.triggerId)
        canceledActivation != null -> RemoteControlKey(RemoteControlKind.CANCEL_ACTIVATION, canceledActivation.activationId)
        incomingIntent != null -> RemoteControlKey(RemoteControlKind.INTENT, incomingIntent.triggerId)
        operation == SessionControl.Operation.START -> RemoteControlKey(RemoteControlKind.START, epoch, startEpoch)
        operation == SessionControl.Operation.STOP -> RemoteControlKey(RemoteControlKind.STOP, epoch, startEpoch)
        else -> null
    }
}

/** Captured before the feature FIFO. False is terminal; true is rechecked against the SAME
 * original Phone receipt if persistence needs a retry, never against a later Hold. */
internal class RemoteControlReceipt(val request: SessionControl.Request, private val original: () -> Boolean) {
    private var consumed = false // Feature writer only.
    fun admit(): Boolean {
        if (consumed) return false
        return original().also { if (!it) consumed = true }
    }
}
