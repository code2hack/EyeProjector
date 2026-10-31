package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.FunctionIntent
import com.code2hack.eyeprojector.protocol.ReceiverUsability
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode

/** Local presentation of the original Phone contact; this class owns no input or toggle. */
internal class AnchorHeldIndicator(private val render: (Boolean) -> Unit) {
    private var original: Any? = null
    private var context: ReceiverUsability? = null
    private var revision = -1L
    var visible = false
        private set

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
    fun accept(source: Any, intent: FunctionIntent, stateRevision: Long, held: Boolean): Boolean {
        if (source !== original || !sameContext(context, intent.context.usability) || context == null) return false
        if (stateRevision <= revision) return true
        revision = stateRevision
        show(held)
        return true
    }

    fun clear() {
        context = null
        show(false)
    }

    private fun show(value: Boolean) {
        if (visible == value) return
        visible = value
        render(value)
    }

    private fun sameContext(a: ReceiverUsability?, b: ReceiverUsability?): Boolean =
        if (a == null || b == null) a == b else a.ownerEpoch == b.ownerEpoch &&
            a.incarnation == b.incarnation && a.inputRevision == b.inputRevision && a.mode == b.mode
}
