package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.Touch

/** Retains one physical contact's edges until its original Phone decision, including consumed tails. */
internal class ReceiverContactInput<T>(
    private val key: (T) -> Any,
    private val phase: (T) -> Touch.Phase,
    private val admit: ((Boolean) -> Unit) -> Unit,
    private val deliver: (T) -> Unit,
    private val dispose: (T) -> Unit = {},
) {
    private class Contact<T>(val original: T) { val edges = ArrayDeque<T>(); var normal: Boolean? = null; var draining = false }
    private val contacts = mutableMapOf<Any, Contact<T>>()
    var admission = 0L; private set

    fun accept(event: T): Boolean {
        val id = key(event)
        val edge = phase(event)
        if (edge == Touch.Phase.DOWN) {
            if (contacts.containsKey(id)) return true
            val original = Contact(event)
            original.edges.addLast(event)
            contacts[id] = original
            admission++
            admit { normal ->
                if (contacts[id] !== original || original.normal != null) return@admit
                original.normal = normal
                drain(id, original)
            }
        } else {
            val original = contacts[id] ?: return false
            original.edges.addLast(event)
            drain(id, original)
        }
        return true
    }

    private fun drain(id: Any, original: Contact<T>) {
        val normal = original.normal ?: return
        if (original.draining) return
        original.draining = true
        try { while (contacts[id] === original && original.edges.isNotEmpty()) {
            val event = original.edges.removeFirst()
            val end = phase(event) == Touch.Phase.UP || phase(event) == Touch.Phase.CANCEL
            if (end) contacts.remove(id, original) // A reentrant new contact may reuse the numeric ID.
            try { if (normal) deliver(event) } finally { dispose(event) }
        } } finally { original.draining = false }
    }

    /** Metadata only. The component's original output receipt owns any actual CANCEL. */
    fun original(id: Any): T? = contacts[id]?.original
    fun contains(id: Any): Boolean = contacts.containsKey(id)
    fun retire(id: Any) { contacts.remove(id)?.let { original ->
        while (original.edges.isNotEmpty()) dispose(original.edges.removeFirst())
    } }
    fun retire(): Set<Any> {
        val original = contacts.toMap()
        contacts.clear() // Detach originals before disposal; later input owns separate queues.
        original.values.forEach { contact ->
            while (contact.edges.isNotEmpty()) dispose(contact.edges.removeFirst())
        }
        return original.keys
    }
}
