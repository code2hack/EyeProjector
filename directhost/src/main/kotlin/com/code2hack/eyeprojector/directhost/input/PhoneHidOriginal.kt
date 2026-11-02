package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.execution.IAnchorRawControl
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase

/** The represented native DOWN's original Phone lifetime, including an absent/retired original. */
internal class PhoneHidOriginal(private val remote: IAnchorRawControl?, val context: ProjectionContext,
    private val receivedDisposition: Int = com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlDisposition.NORMAL.ordinal,
    val capturedAnchor: Boolean = false,
    private val current: () -> Boolean) {
    val discarded: Boolean get() = receivedDisposition !=
        com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlDisposition.NORMAL.ordinal

    fun consumeUserControl(): Boolean {
        if (!current()) return false
        val source = remote ?: return !context.available
        return runCatching { source.admitUserControl() ==
            com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlDisposition.NORMAL.ordinal && current() }.getOrDefault(false)
    }

    fun route(down: PhoneHidEdge, region: String, word: Long, binding: Long, routeId: Long): PhoneHidRoute? {
        val source = remote ?: return null
        if (!current()) return null
        return PhoneHidRoute(down, binding) { edge ->
            if (current()) source.edge(routeId, edge.bytes(), region, word)
            else if (edge.phase != PhoneHidPhase.DOWN) {
                // The old route still owes its own metadata retirement, never a late activation.
                source.edge(routeId, edge.copy(phase = PhoneHidPhase.CANCEL).bytes(), region, word)
            }
        }.also { it.submit(down) }
    }
}
