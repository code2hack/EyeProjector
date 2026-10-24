package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.protocol.ActivationGoal
import com.code2hack.eyeprojector.protocol.ReceiverIncarnation
import java.io.Closeable
import java.util.UUID

internal enum class ActivationCause { PHONE_START, GLASSES_LAUNCH, HID_ATTACHMENT, HID_PRESS, NOTIFICATION }

/** Identity tokens are retained by their caller; an old callback never resolves a new current owner. */
internal class ActivationHandle internal constructor(val id: String, val cause: ActivationCause,
    val demandId: String? = null, val goal: ActivationGoal = ActivationGoal.USABLE)
internal data class ActivationBinding(
    val activationId: String, val demandId: String?, val goal: ActivationGoal, val wakeRevision: Long,
    val attemptId: String?, val incarnation: ReceiverIncarnation?, val presentationRevision: Long?,
)
internal class ActivationAttempt internal constructor(val handle: ActivationHandle, val id: String)
internal enum class ActivationPhase { PENDING, STARTING, SETUP_REQUIRED, AWAITING_PRESENTATION, READY, RELEASING, CANCELED }
internal data class ActivationState<T : Any>(
    val handle: ActivationHandle,
    val phase: ActivationPhase,
    val attempt: ActivationAttempt? = null,
    val target: T? = null,
    val detail: String = "",
    val binding: ActivationBinding? = null,
    val authority: Long = 0,
)
internal data class ActivationReady<T : Any>(val handle: ActivationHandle, val attempt: ActivationAttempt, val target: T,
    val binding: ActivationBinding? = null, val authority: Long = 0)

/**
 * Serialized local ownership only. The existing Phone session owner supplies actual admission,
 * setup, presentation and release observations; this helper starts no backend, timer or transport.
 * T is the exact immutable backend target (ProjectionContext at the eventual integration seam).
 */
internal class PhoneActivationOwner<T : Any>(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private class Observer<T : Any>(val readyOnly: Boolean, val receive: (ActivationState<T>) -> Unit)
    private var current: ActivationState<T>? = null
    private val canceledAttempts = mutableSetOf<ActivationAttempt>()
    private val constructing = mutableSetOf<ActivationAttempt>()
    private val targets = mutableMapOf<ActivationAttempt, T>()
    private val observers = mutableMapOf<ActivationHandle, MutableSet<Observer<T>>>()

    fun requestActivation(cause: ActivationCause): ActivationHandle {
        current?.let { return it.handle }
        val handle = ActivationHandle(newId(), cause)
        current = ActivationState(handle, ActivationPhase.PENDING)
        return handle
    }

    /** Restore desire only; authoritative backend/presentation reconciliation supplies every observation. */
    fun restoreActivation(id: String, cause: ActivationCause): ActivationHandle {
        current?.let { return it.handle }
        val handle = ActivationHandle(id, cause)
        current = ActivationState(handle, ActivationPhase.PENDING)
        return handle
    }

    fun state(handle: ActivationHandle): ActivationState<T> = current?.takeIf { it.handle === handle }
        ?: ActivationState(handle, ActivationPhase.CANCELED)

    /** The old attempt must actually release before another can be admitted, even after failure. */
    fun beginAttempt(handle: ActivationHandle, id: String = newId()): ActivationAttempt? {
        if (canceledAttempts.isNotEmpty()) return null
        val old = current?.takeIf { it.handle === handle && it.attempt == null } ?: return null
        val attempt = ActivationAttempt(handle, id)
        constructing.add(attempt)
        try { update(old.copy(phase = ActivationPhase.STARTING, attempt = attempt, target = null, detail = "")) }
        finally { constructing.remove(attempt) }
        return attempt.takeIf { current?.attempt === it }
    }

    fun setupRequired(attempt: ActivationAttempt, detail: String) = change(attempt, ActivationPhase.SETUP_REQUIRED, detail)
    fun awaitingPresentation(attempt: ActivationAttempt) = change(attempt, ActivationPhase.AWAITING_PRESENTATION)
    fun releasing(attempt: ActivationAttempt, detail: String = "") = change(attempt, ActivationPhase.RELEASING, detail)

    /** Boolean results acknowledge the observation, not authorization after observer reentry. */
    fun ready(attempt: ActivationAttempt, target: T): Boolean {
        if (attempt in constructing) return false
        val old = current?.takeIf { it.attempt === attempt &&
            it.phase in setOf(ActivationPhase.STARTING, ActivationPhase.AWAITING_PRESENTATION, ActivationPhase.READY) } ?: return false
        val bound = targets[attempt]
        if (bound != null && bound != target) return false
        val original = bound ?: target.also { targets[attempt] = it }
        update(old.copy(phase = ActivationPhase.READY, target = original, detail = ""))
        return true
    }

    /** Actual source context replacement retires presentation binding, not backend resources. */
    fun invalidatePresentation(attempt: ActivationAttempt): Boolean {
        val old = current?.takeIf { it.attempt === attempt } ?: return false
        targets.remove(attempt)
        if (old.phase != ActivationPhase.RELEASING)
            update(old.copy(phase = ActivationPhase.AWAITING_PRESENTATION, target = null))
        return true
    }

    /** Release is an external observation, never inferred from a failed submission or sent Stop. */
    fun released(attempt: ActivationAttempt, detail: String = ""): Boolean {
        if (canceledAttempts.remove(attempt)) { targets.remove(attempt); return true }
        val old = current?.takeIf { it.attempt === attempt } ?: return false
        targets.remove(attempt)
        update(old.copy(phase = ActivationPhase.PENDING, attempt = null, target = null, detail = detail))
        return true
    }

    /** Invalidate locally before the caller cancels this exact returned attempt on its backend. */
    fun cancelActivation(handle: ActivationHandle): ActivationAttempt? {
        val old = current?.takeIf { it.handle === handle } ?: return null
        val admitted = old.attempt?.takeUnless { it in constructing }
        admitted?.let(canceledAttempts::add)
        current = null
        publish(old.copy(phase = ActivationPhase.CANCELED, target = null))
        return admitted
    }

    fun observe(handle: ActivationHandle, receive: (ActivationState<T>) -> Unit): Closeable =
        register(handle, Observer(false, receive))

    /** One-shot readiness captures the original handle/attempt/target before invoking user code. */
    fun whenReady(handle: ActivationHandle, receive: (ActivationReady<T>) -> Unit): Closeable =
        register(handle, Observer(true) { value ->
            if (value.phase == ActivationPhase.READY) receive(ActivationReady(handle, value.attempt!!, value.target!!))
        })

    private fun register(handle: ActivationHandle, observer: Observer<T>): Closeable {
        observers.getOrPut(handle) { linkedSetOf() }.add(observer)
        deliver(observer, state(handle))
        return Closeable { remove(handle, observer) }
    }

    private fun change(attempt: ActivationAttempt, phase: ActivationPhase, detail: String = ""): Boolean {
        val old = current?.takeIf { it.attempt === attempt } ?: return false
        // A late presentation/setup callback cannot undo release of that same attempt.
        if (old.phase == ActivationPhase.RELEASING && phase != ActivationPhase.RELEASING) return false
        update(old.copy(phase = phase, target = null, detail = detail))
        return true
    }

    private fun update(value: ActivationState<T>) { current = value; publish(value) }
    private fun publish(value: ActivationState<T>) {
        val pending = observers[value.handle]?.toList().orEmpty()
        for (observer in pending) {
            if (value.phase != ActivationPhase.CANCELED && current !== value) break
            if (observer in observers[value.handle].orEmpty()) deliver(observer, value)
        }
    }
    private fun deliver(observer: Observer<T>, value: ActivationState<T>) {
        if (value.phase == ActivationPhase.CANCELED || observer.readyOnly && value.phase == ActivationPhase.READY)
            remove(value.handle, observer) // Reentry cannot deliver this one-shot twice.
        if (!observer.readyOnly || value.phase == ActivationPhase.READY) try { observer.receive(value) }
        catch (_: Exception) { /* Observation cannot prevent ownership publication to the next observer. */ }
    }
    private fun remove(handle: ActivationHandle, observer: Observer<T>) {
        observers[handle]?.let { it.remove(observer); if (it.isEmpty()) observers.remove(handle) }
    }
}
