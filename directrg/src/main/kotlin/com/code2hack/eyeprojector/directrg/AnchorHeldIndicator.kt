package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.AnchorIntent
import com.code2hack.eyeprojector.protocol.ReceiverUsability
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode

/** Local presentation of the original Phone contact; this class owns no input or toggle. */
internal class AnchorHeldIndicator(private val render: (Boolean) -> Unit) {
    private var original: Any? = null
    private var context: ReceiverUsability? = null
    private var revision = -1L
    var visible = false
        private set
    private class Rendering(var value: Boolean?) { var clearAttempted = false }
    private var rendering = Rendering(false)
    private var admission = 0L

    fun bind(source: Any?, current: ReceiverUsability?) {
        val next = current?.takeIf { source != null && it.mode == ReceiverUsabilityMode.PROJECTED }
        if (original !== source || !sameContext(context, next)) {
            val replaced = original !== source
            original = source
            context = next
            if (replaced) revision = -1L
            show(false)
        }
    }

    /** State revisions are monotonic within the original Session, including cancellation. */
    fun accept(source: Any, intent: AnchorIntent, stateRevision: Long, held: Boolean): Boolean {
        if (source !== original || !sameContext(context, intent.context.usability) || context == null) return false
        if (stateRevision <= revision) return true
        admission++
        revision = stateRevision
        show(held)
        return true
    }

    fun prepareClear(): InputRetirement {
        val old = rendering
        context = null
        visible = false
        val baseline = admission
        val source = original
        val stateRevision = revision
        return InputRetirement({
            if (!old.clearAttempted) {
                old.clearAttempted = true
                if (!visible && rendering === old && old.value != false) renderValue(false)
            }
        }, { admission != baseline || original !== source || revision != stateRevision })
    }

    fun clear() = prepareClear().finishOriginal()

    private fun show(value: Boolean) {
        if (visible == value && rendering.value == value) return
        visible = value
        renderValue(value)
    }

    private fun renderValue(value: Boolean) {
        val next = Rendering(value)
        rendering = next // Publish before arbitrary rendering callbacks.
        try { render(value) }
        catch (error: Throwable) {
            if (rendering === next) next.value = null // A later genuine render may reconcile unknown output.
            throw error
        }
    }

    private fun sameContext(a: ReceiverUsability?, b: ReceiverUsability?): Boolean =
        if (a == null || b == null) a == b else a.ownerEpoch == b.ownerEpoch &&
            a.incarnation == b.incarnation
}
