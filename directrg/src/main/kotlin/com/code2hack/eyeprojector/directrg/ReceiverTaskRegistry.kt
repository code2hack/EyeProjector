package com.code2hack.eyeprojector.directrg

/** A numeric task ID is metadata, never authority to reattach a journal scope after restart. */
internal class ReceiverTaskRegistry(retained: Collection<ReceiverTaskBinding> = emptyList()) {
    enum class Presence { PRESENT, ABSENT, UNKNOWN }
    private data class Registration(val binding: ReceiverTaskBinding, val query: (() -> Presence)?)
    private val registrations = retained.associate { it.scope.id to Registration(it, null) }.toMutableMap()
    fun register(binding: ReceiverTaskBinding, originalTask: () -> Presence) {
        val old = registrations[binding.scope.id]
        if (old == null || old.query == null && old.binding == binding) registrations[binding.scope.id] = Registration(binding, originalTask)
    }
    fun reconcile(): List<ReceiverTaskBinding> {
        val absent = registrations.values.filter { r ->
            try { r.query?.invoke() == Presence.ABSENT } catch (_: Exception) { false }
        }
        return absent.map { it.binding }
    }
    fun acknowledged(binding: ReceiverTaskBinding) {
        if (registrations[binding.scope.id]?.binding == binding) registrations.remove(binding.scope.id)
    }
    fun unresolved() = registrations.values.filter { it.query == null }.map { it.binding }
}
