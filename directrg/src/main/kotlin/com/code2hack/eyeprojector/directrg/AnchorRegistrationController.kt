package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.AnchorIntent
import com.code2hack.eyeprojector.protocol.AnchorSource
import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.AnchorIntentContext
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
 * Activity records the revision produced by entering this chooser. Passive presentation changes
 * keep the original selection; no later Session can adopt its release or transaction.
 */
internal class AnchorRegistrationController<S : Any>(
    private val send: (S, Long, AnchorRegistrationAction) -> Boolean,
) {
    internal data class Offer<S>(val source: S, val id: Long, val original: AnchorIntentContext,
        val highlight: AnchorRegistrationHighlight, val fit: Fit, val geometry: Geometry,
        var chooserRevision: Long? = null, var choosing: Boolean = false,
        val intent: AnchorIntent? = null, var highlighted: AnchorRegistrationAction? = null, var ready: Boolean = false)

    var offer: Offer<S>? = null
        private set
    private class Original<S>(val source: S, val intent: AnchorIntent, var held: Boolean)
    private val originals = mutableMapOf<AnchorSource, Original<S>>()
    private var revision = 0L

    /** Classification retains this physical original before its asynchronous registration request. */
    fun registration(source: S, intent: AnchorIntent, held: Boolean) {
        val old = originals[intent.source]
        if (old?.source === source && old.intent == intent) return
        originals[intent.source] = Original(source, intent, held)
        val pending = offer
        if (pending?.intent?.source == intent.source && pending.intent != intent) cancel()
    }

    /** Exact original UP, never a summary, current context recapture or ordinary activation. */
    fun released(source: S, intent: AnchorIntent, canceled: Boolean = false, transactionId: Long? = null): Boolean {
        if (transactionId != null && offer?.let { it.source === source && it.intent == intent && it.id != transactionId } == true) return false
        val old = originals[intent.source]
        if (old == null) {
            // Phone can report release before its asynchronous app snapshot/Offer returns.
            if (intent.source == AnchorSource.PHONE_HID) originals[intent.source] = Original(source, intent, false)
            return false
        }
        if (old.source === source && intent.source == AnchorSource.PHONE_HID && intent.sequence > old.intent.sequence) {
            originals[intent.source] = Original(source, intent, false)
            return false
        }
        if (old.source !== source || old.intent != intent || !old.held) return false
        old.held = false
        val pending = offer ?: return false
        if (pending.source !== source || pending.intent != intent) return false
        return choose(if (!canceled && pending.ready) pending.highlighted ?: AnchorRegistrationAction.CANCEL
            else AnchorRegistrationAction.CANCEL)
    }

    fun highlight(action: AnchorRegistrationAction?) {
        offer?.takeUnless { it.choosing }?.highlighted = action
    }
    fun presented() { offer?.ready = true }

    val active: Boolean get() = offer != null

    fun show(source: S, id: Long, original: AnchorIntentContext, highlight: AnchorRegistrationHighlight,
        intent: AnchorIntent? = null): Boolean {
        if (intent != null) {
            if (intent.source == AnchorSource.PHONE_HID) {
                val prior = originals[intent.source]
                if (prior == null || prior.source !== source || intent.sequence > prior.intent.sequence)
                    registration(source, intent, true)
            }
            val press = originals[intent.source]
            if (press?.source !== source || press.intent != intent || !press.held) return false
        }
        val fit = original.pointer.fit ?: return false
        val geometry = original.pointer.geometry ?: return false
        if (offer?.let { it.source === source && it.id == id } == true) return true
        val previousOffer = offer
        val ownRevision = ++revision
        offer = null
        previousOffer?.let { send(it.source, it.id, AnchorRegistrationAction.CANCEL) }
        if (revision != ownRevision || offer != null) return false
        offer = Offer(source, id, original, highlight.receiver(fit, geometry), fit, geometry, intent = intent)
        return true
    }

    /** Exactly the single intentional local chooser transition, while preserving original values. */
    fun enteredChooser(source: S, incarnation: ReceiverIncarnation, geometry: Geometry?, revision: Long): Boolean {
        val pending = offer ?: return false
        if (pending.chooserRevision != null || source !== pending.source ||
            incarnation != pending.original.usability.incarnation) return false
        pending.chooserRevision = revision
        return true
    }

    fun current(source: S?, incarnation: ReceiverIncarnation?, geometry: Geometry?, revision: Long, fit: Fit?): Boolean {
        val pending = offer ?: return false
        return source === pending.source && incarnation == pending.original.usability.incarnation
    }

    /** A matching physical release commits the highlighted choice; local taps never invoke this. */
    fun choose(action: AnchorRegistrationAction): Boolean {
        val pending = offer ?: return false
        if (action == AnchorRegistrationAction.CANCEL) { cancel(); return true }
        if (pending.choosing) return true
        pending.choosing = true
        if (!send(pending.source, pending.id, action) && offer === pending) { offer = null; revision++ }
        return true
    }

    /** Explicit user Cancel discards only this original registration. */
    fun cancel(): Boolean {
        val pending = offer ?: return false
        offer = null; revision++
        pending.intent?.let { originals[it.source]?.takeIf { p -> p.source === pending.source && p.intent == it }?.held = false }
        send(pending.source, pending.id, AnchorRegistrationAction.CANCEL)
        return true
    }

    /** A late completion for another Session or transaction cannot dismiss the current chooser. */
    fun completed(source: S, id: Long): Boolean {
        val pending = offer ?: return false
        if (source !== pending.source || id != pending.id) return false
        offer = null; revision++
        return true
    }

    /** Loss of the original presentation/input resource is not a user Cancel or a release. */
    fun lost(source: S, intent: AnchorIntent): Boolean {
        originals[intent.source]?.takeIf { it.source === source && it.intent == intent }?.held = false
        val pending = offer ?: return false
        if (pending.source !== source || pending.intent != intent) return false
        offer = null; revision++
        return true
    }
}

/** Compact three-way menu centered on the current target and clamped to the visible viewport. */
internal data class AnchorRegistrationMenu(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun at(x: Float, y: Float): AnchorRegistrationAction? {
        if (width <= 0 || height <= 0 || x < left || x >= left + width || y < top || y >= top + height) return null
        return AnchorRegistrationAction.entries[((x - left) * 3 / width).toInt().coerceIn(0, 2)]
    }
    companion object {
        fun layout(target: AnchorRegistrationHighlight, viewportWidth: Int, viewportHeight: Int, density: Float,
            anchorX: Float = ((target.left + target.right) / 2).toFloat(),
            anchorY: Float = ((target.top + target.bottom) / 2).toFloat()): AnchorRegistrationMenu {
            val width = (216f * density).coerceAtMost(viewportWidth.toFloat().coerceAtLeast(0f))
            val height = (44f * density).coerceAtMost(viewportHeight.toFloat().coerceAtLeast(0f))
            val left = (anchorX.coerceIn(target.left.toFloat(), target.right.toFloat()) - width / 2)
                .coerceIn(0f, (viewportWidth - width).coerceAtLeast(0f))
            val top = (anchorY.coerceIn(target.top.toFloat(), target.bottom.toFloat()) - height / 2)
                .coerceIn(0f, (viewportHeight - height).coerceAtLeast(0f))
            return AnchorRegistrationMenu(left, top, width, height)
        }
    }
}
