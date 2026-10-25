package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.ReceiverUsability
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode
import java.util.concurrent.atomic.AtomicReference

internal data class SessionUsabilitySnapshot(val context: ProjectionContext, val fact: ReceiverUsability? = null, val retired: Boolean = false) {
    val usable: Boolean get() = !retired && fact?.mode in setOf(ReceiverUsabilityMode.LOCAL_PANEL, ReceiverUsabilityMode.PROJECTED)
}

/** Actual Session-owned immutable slot. No stock/TLS/control-lock acquisition in publish/read/retire. */
internal class SessionUsability(context: ProjectionContext) {
    private val current = AtomicReference(SessionUsabilitySnapshot(context))
    fun snapshot(): SessionUsabilitySnapshot = current.get()
    fun publish(fact: ReceiverUsability): Boolean {
        while (true) {
            val old = current.get()
            if (old.retired || fact.ownerEpoch != old.context.ownerEpoch) return false
            val before = old.fact
            if (before != null && (fact.incarnation.sequence < before.incarnation.sequence ||
                fact.incarnation.sequence == before.incarnation.sequence &&
                (fact.incarnation.id != before.incarnation.id || fact.revision <= before.revision))) return false
            if (current.compareAndSet(old, old.copy(fact = fact))) return true
        }
    }
    fun retire() { current.updateAndGet { it.copy(retired = true) } }
}
