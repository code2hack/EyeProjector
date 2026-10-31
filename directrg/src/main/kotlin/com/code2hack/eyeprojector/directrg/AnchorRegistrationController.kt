package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.FunctionIntentContext
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.ReceiverIncarnation

internal enum class AnchorRegistrationAction { CLICK, HOLD, CANCEL }

/** Video pixels from the actual selected source target; never a saved preference coordinate. */
internal data class AnchorRegistrationHighlight(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun receiver(fit: Fit, video: Geometry) = AnchorRegistrationHighlight(
        fit.left + left * fit.width / video.width, fit.top + top * fit.height / video.height,
        fit.left + right * fit.width / video.width, fit.top + bottom * fit.height / video.height)
}

/**
 * Main-thread chooser state, bound to the original media Session and Receiver incarnation. The
 * Activity explicitly binds the revision produced by entering this chooser; later invalidation
 * cannot extend that permission to an arbitrary next revision or replacement Session.
 */
internal class AnchorRegistrationController<S : Any>(
    private val send: (S, Long, AnchorRegistrationAction) -> Boolean,
) {
    internal data class Offer<S>(val source: S, val id: Long, val original: FunctionIntentContext,
        val highlight: AnchorRegistrationHighlight, val fit: Fit, val geometry: Geometry,
        var chooserRevision: Long? = null, var choosing: Boolean = false)

    var offer: Offer<S>? = null
        private set
    val active: Boolean get() = offer != null

    fun show(source: S, id: Long, original: FunctionIntentContext, highlight: AnchorRegistrationHighlight): Boolean {
        val fit = original.pointer.fit ?: return false
        val geometry = original.pointer.geometry ?: return false
        if (offer?.let { it.source === source && it.id == id } == true) return true
        cancel()
        offer = Offer(source, id, original, highlight.receiver(fit, geometry), fit, geometry)
        return true
    }

    /** Exactly the single intentional local chooser transition, while preserving original values. */
    fun enteredChooser(source: S, incarnation: ReceiverIncarnation, geometry: Geometry?, revision: Long): Boolean {
        val pending = offer ?: return false
        if (pending.chooserRevision != null || source !== pending.source ||
            incarnation != pending.original.usability.incarnation || geometry != pending.geometry) {
            cancel(); return false
        }
        pending.chooserRevision = revision
        return true
    }

    fun current(source: S?, incarnation: ReceiverIncarnation?, geometry: Geometry?, revision: Long, fit: Fit?): Boolean {
        val pending = offer ?: return false
        return source === pending.source && incarnation == pending.original.usability.incarnation &&
            geometry == pending.geometry && fit == pending.fit &&
            revision == (pending.chooserRevision ?: pending.original.usability.inputRevision)
    }

    /** Active chooser taps are consumed even while its result is pending or outside a button. */
    fun choose(action: AnchorRegistrationAction): Boolean {
        val pending = offer ?: return false
        if (action == AnchorRegistrationAction.CANCEL) { cancel(); return true }
        if (pending.choosing) return true
        pending.choosing = true
        if (!send(pending.source, pending.id, action)) offer = null
        return true
    }

    /** Back and genuine lifecycle/context loss discard only this original registration. */
    fun cancel(): Boolean {
        val pending = offer ?: return false
        offer = null
        send(pending.source, pending.id, AnchorRegistrationAction.CANCEL)
        return true
    }

    /** A late completion for another Session or transaction cannot dismiss the current chooser. */
    fun completed(source: S, id: Long): Boolean {
        val pending = offer ?: return false
        if (source !== pending.source || id != pending.id) return false
        offer = null
        return true
    }
}
