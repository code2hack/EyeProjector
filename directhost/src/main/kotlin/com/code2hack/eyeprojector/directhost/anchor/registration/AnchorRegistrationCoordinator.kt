package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchor
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStore
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolution
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolver
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetSnapshot
import com.code2hack.eyeprojector.protocol.FunctionIntent

sealed interface AnchorRegistrationCompletion {
    data class Saved(val anchor: AppAnchor) : AnchorRegistrationCompletion
    data object Canceled : AnchorRegistrationCompletion
    data object ContextChanged : AnchorRegistrationCompletion
    data object SaveFailed : AnchorRegistrationCompletion
}

/**
 * Confined to the app's serialized Anchor worker. The shared bridge owns original Session/Binder
 * admission, ordering and retirement, and supplies snapshots from that exact captured route. A
 * chooser's intentional input-mode transition changes no saved source/window identity here.
 * T is the bridge's opaque original transaction token; this helper never finds a latest Session.
 */
class AnchorRegistrationCoordinator<T : Any>(private val store: AppAnchorStore) {
    private data class Pending<T>(val token: T, val target: AnchorRegistrationTarget)
    private var pending: Pending<T>? = null

    fun begin(token: T, intent: FunctionIntent, snapshot: UiTargetSnapshot): AnchorRegistrationCapture {
        pending = null
        return AnchorRegistrationTargets.capture(intent, snapshot).also {
            if (it is AnchorRegistrationCapture.Selected) pending = Pending(token, it.target)
        }
    }

    /** Cancel does no read, write, rollback or store-event publication. */
    fun cancel(token: T): Boolean {
        if (pending?.token != token) return false
        pending = null
        return true
    }

    /** Called only after the bridge admits this original transaction, never by a raw Function UP. */
    fun choose(token: T, mode: AnchorMode, snapshot: UiTargetSnapshot): AnchorRegistrationCompletion {
        val original = pending?.takeIf { it.token == token } ?: return AnchorRegistrationCompletion.Canceled
        pending = null
        val selected = original.target
        val resolved = AnchorTargetResolver.resolve(selected.descriptor, snapshot)
        if (resolved !is AnchorTargetResolution.Unique || resolved.current.source != selected.current.source ||
            resolved.current.window.windowId != selected.current.window.windowId ||
            resolved.current.window.type != selected.current.window.type) return AnchorRegistrationCompletion.ContextChanged
        val anchor = AppAnchor(selected.descriptor, mode)
        return try {
            store.replace(anchor)
            AnchorRegistrationCompletion.Saved(anchor)
        } catch (_: Exception) {
            // The atomic store leaves its previous complete value in place on write failure.
            AnchorRegistrationCompletion.SaveFailed
        }
    }
}
