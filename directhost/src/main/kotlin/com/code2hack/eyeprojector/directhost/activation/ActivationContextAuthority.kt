package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.directhost.ProjectionContext
import java.io.Closeable

/** App-process producer observation only; never a backend release or remote visibility acknowledgment. */
internal data class ActivationContextFact(val producer: Any, val sequence: Long, val binding: Long,
    val context: ProjectionContext, val eligible: Boolean)

/** The facade's existing fast lock also serializes typed commit against producer invalidation. */
internal class ActivationContextAuthority {
    val lock = Any()
    private var registration: Any? = null
    var fact: ActivationContextFact? = null; private set
    var version = 0L; private set
    fun install(token: Any, value: ActivationContextFact) = synchronized(lock) {
        registration = token; fact = value; version++
    }
    fun update(token: Any, value: ActivationContextFact) = synchronized(lock) {
        val old = fact
        if (registration === token && (old == null || old.producer === value.producer && value.sequence > old.sequence)) {
            fact = value; version++
        }
    }
    fun detach(token: Any) = synchronized(lock) {
        if (registration === token) { registration = null; fact = null; version++ }
    }
}

/**
 * Only metadata lives here. Lock order is publisher gate -> facade fastLock; never the reverse.
 * No I/O/general observer/caller closure runs under either gate. HostBootstrap assigns its cached
 * Status under gate after commit, then posts ordinary observers outside it.
 */
internal class ActivationContextPublisher {
    val gate = Any()
    private val identity = Any()
    private var epoch = 0L
    private var binding = 0L
    private var sequence = 0L
    private var blockedBinding = false
    private var current = ActivationContextFact(identity, sequence, binding, ProjectionContext(), false)
    private class Registration(val owner: ActivationContextAuthority, val token: Any = Any())
    private var installed: Registration? = null
    class Observation internal constructor(internal val producer: Any, internal val epoch: Long, internal val binding: Long, val context: ProjectionContext)
    class Binding internal constructor(internal val producer: Any, internal val id: Long)
    fun capture(): Observation = synchronized(gate) { Observation(identity, epoch, binding, current.context) }
    fun register(owner: ActivationContextAuthority): Closeable {
        val registration = synchronized(gate) {
            installed?.let { it.owner.detach(it.token) }
            Registration(owner).also { installed = it; owner.install(it.token, current) }
        }
        return Closeable { synchronized(gate) {
            if (installed === registration) installed = null
            registration.owner.detach(registration.token)
        } }
    }
    private fun matches(value: Observation) = value.producer === identity && value.epoch == epoch && value.binding == binding
    private fun emit(context: ProjectionContext, eligible: Boolean, force: Boolean = false) {
        if (!force && current.context == context && current.eligible == eligible && current.binding == binding) return
        current = ActivationContextFact(identity, ++sequence, binding, context, eligible)
        installed?.let { it.owner.update(it.token, current) }
    }
    fun bindingChanged(): Binding = synchronized(gate) {
        epoch++; binding++; blockedBinding = false; emit(current.context, false, true)
        Binding(identity, binding)
    }
    fun retire(original: Observation, blockBinding: Boolean = false): Boolean = synchronized(gate) {
        if (!matches(original)) false else {
            epoch++; blockedBinding = blockedBinding || blockBinding; emit(current.context, false, true); true
        }
    }
    fun retireBinding(original: Binding): Boolean = synchronized(gate) {
        if (original.producer !== identity || binding != original.id) false else {
            epoch++; blockedBinding = true; emit(current.context, false, true); true
        }
    }
    /** Stage known loss/context change BEFORE disk/setup/Binder work; never grants positive authority. */
    fun stage(original: Observation, context: ProjectionContext, eligibleCandidate: Boolean): Boolean = synchronized(gate) {
        if (!matches(original)) false else {
            if (!eligibleCandidate || current.context != context) emit(context, false)
            true
        }
    }
    fun commit(original: Observation, context: ProjectionContext, eligible: Boolean): Boolean = synchronized(gate) {
        if (!matches(original)) false else { emit(context, eligible && !blockedBinding); true }
    }
}
