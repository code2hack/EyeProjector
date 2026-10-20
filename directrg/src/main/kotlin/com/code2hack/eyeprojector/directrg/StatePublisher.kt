package com.code2hack.eyeprojector.directrg

/** Orders callbacks; staged items become eligible only after their owning frame exits. */
internal class StatePublisher {
    class Gate {
        @Volatile private var released = false
        fun release() { released = true }
        val ready get() = released
    }
    private class Item(val gate: Gate?, val action: () -> Unit) {
        val ready get() = gate?.ready != false
    }
    private val pending = ArrayDeque<Item>()
    private var publishing = false

    fun publish(action: () -> Unit) { enqueue(action = action); drain() }

    @Synchronized fun enqueue(gate: Gate? = null, action: () -> Unit) {
        pending.addLast(Item(gate, action))
    }

    fun drain() {
        synchronized(this) {
            if (publishing || pending.firstOrNull()?.ready != true) return
            publishing = true
        }
        var failure: Throwable? = null
        while (true) {
            val next = synchronized(this) {
                if (pending.firstOrNull()?.ready != true) {
                    publishing = false
                    null
                } else pending.removeFirst()
            } ?: break
            try { next.action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
