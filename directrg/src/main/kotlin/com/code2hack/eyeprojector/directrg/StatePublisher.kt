package com.code2hack.eyeprojector.directrg

/** Orders state callbacks without holding a monitor while calling application code. */
internal class StatePublisher {
    private val pending = ArrayDeque<() -> Unit>()
    private var publishing = false

    fun publish(action: () -> Unit) {
        synchronized(this) {
            pending.addLast(action)
            if (publishing) return
            publishing = true
        }
        var failure: Throwable? = null
        while (true) {
            val next = synchronized(this) {
                if (pending.isEmpty()) { publishing = false; null } else pending.removeFirst()
            } ?: break
            try { next() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
