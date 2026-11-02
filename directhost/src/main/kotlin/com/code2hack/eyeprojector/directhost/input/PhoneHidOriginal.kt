package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.IPhoneInputSession
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase

/** Captured ordinary input route; stale terminal edges only retire their original Session. */
internal class PhoneHidOriginal(private val remote: IPhoneInputSession?, val context: ProjectionContext,
    private val current: () -> Boolean) {
    val discarded: Boolean get() = !current()
    fun consumeUserControl(): Boolean = current() &&
        (remote?.let { runCatching { it.active() && current() }.getOrDefault(false) } ?: !context.available)
    fun route(down: PhoneHidEdge, region: String, word: Long, binding: Long, routeId: Long): PhoneHidRoute? {
        val source = remote ?: return null
        if (!current()) return null
        return PhoneHidRoute(down,binding) { edge ->
            if (current()) source.edge(routeId,edge.bytes(),region,word)
            else if (edge.phase != PhoneHidPhase.DOWN) source.edge(routeId,edge.copy(phase=PhoneHidPhase.CANCEL).bytes(),region,word)
        }.also { it.submit(down) }
    }
}
