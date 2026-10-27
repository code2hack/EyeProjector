package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.*

/** Owned by one actual Session. LAN supplies position; actual Phone edges supply actions/time. */
internal class PhoneInputMerger(
    private val lock: Any,
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val geometry: () -> Geometry?,
    private val touch: (Touch) -> Boolean,
    private val scroll: (Scroll) -> Boolean,
    private val navigation: (Navigation, Geometry) -> Unit,
    private val cancelControl: () -> Unit,
    private val receiver: (ReceiverInput) -> Unit,
    private val active: () -> Boolean = { true },
) {
    private class Press(val id: Long, val edge: PhoneHidEdge, val point: HeadPointerState, val contact: Long,
        val qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean)
    private class FunctionSequence(val point: HeadPointerState, val key: Triple<Int, Int, Int>) {
        var held: Press? = null
        var latest: Press? = null
        var presses = 0
        var releasedAt = 0L
    }
    private var point: HeadPointerState? = null
    private var pointerSource: ReceiverUsability? = null
    private val presses = mutableMapOf<Long, Press>()
    private var held: Press? = null
    private var nativeContact: Long? = null
    private var contactSequence = 0L
    private var generation = 0L
    private var function: FunctionSequence? = null
    private var cancelFunctionTimer: (() -> Unit)? = null
    private var functionTimerGeneration = 0L
    private var completionSequence = 0L // Under the existing control lock.
    private val emission = Object()
    private var nextEmission = 0L
    private val transactionOutput = ThreadLocal<MutableList<ReceiverInput>?>()
    private data class Completion(val ticket: Long, val output: List<ReceiverInput>)
    private val reentrantEmissions = ThreadLocal<MutableList<Completion>?>()

    /** Completed Receiver writes happen after releasing the short control lock. */
    private fun <T> transaction(action: (MutableList<ReceiverInput>) -> T): T {
        transactionOutput.get()?.let { return action(it) }
        val output = mutableListOf<ReceiverInput>()
        val committed = synchronized(lock) {
            transactionOutput.set(output)
            val result = try { runCatching { action(output) } } finally { transactionOutput.remove() }
            result to if (output.isEmpty()) null else Completion(completionSequence++, output)
        }
        val emitted = runCatching { committed.second?.let(::emit) }
        committed.first.exceptionOrNull()?.let { error ->
            emitted.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        emitted.getOrThrow()
        return committed.first.getOrThrow()
    }

    /** Ticket order is commit order. Waiting/writing never holds the control/publication locks. */
    private fun emit(completion: Completion) {
        reentrantEmissions.get()?.let { it.add(completion); return }
        val nested = mutableListOf<Completion>()
        reentrantEmissions.set(nested)
        var failure: Throwable? = null
        fun deliver(value: Completion) {
            var interrupted = false
            synchronized(emission) {
                while (nextEmission != value.ticket) try { emission.wait() }
                catch (_: InterruptedException) { interrupted = true }
            }
            try { value.output.forEach(receiver) }
            catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
            finally {
                synchronized(emission) { nextEmission++; emission.notifyAll() }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        try {
            deliver(completion)
            while (nested.isNotEmpty()) {
                val next = nested.minBy { it.ticket }
                nested.remove(next); deliver(next)
            }
        } finally { reentrantEmissions.remove() }
        failure?.let { throw it }
    }

    fun pointer(next: HeadPointerState, source: ReceiverUsability? = null, accepts: () -> Boolean = { true }) = transaction { out ->
        if (!active() || !accepts()) return@transaction
        if (pointerSource != null && source != null && pointerSource!!.incarnation != source.incarnation) {
            retire(out, false) // A new incarnation can restart numeric pointer sequence/revision.
        }
        val old = point
        if (old != null && next.sequence <= old.sequence) return@transaction
        if (old != null && (old.revision != next.revision || old.projected != next.projected || old.geometry != next.geometry) || !next.available)
            retire(out, false)
        if (!active() || !accepts()) return@transaction // Retirement may contain an old stock write.
        if (point?.let { it.sequence >= next.sequence } == true) return@transaction
        point = next; pointerSource = source
        val current = held
        if (current != null) contact(current, Touch.Phase.MOVE, next, now(), out)
    }

    fun edge(routeId: Long, edge: PhoneHidEdge) = stampedEdge(routeId, edge) { _, _ -> true }

    fun stampedEdge(routeId: Long, edge: PhoneHidEdge,
        qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean) = transaction { out ->
        if (!active()) return@transaction
        val old = presses[routeId]
        if (edge.phase == PhoneHidPhase.DOWN) {
            if (old != null || edge.repeatCount != 0) return@transaction
            val position = point?.takeIf { it.available && (!it.projected || it.geometry == geometry()) } ?: return@transaction
            // This check happens only AFTER acquiring the real control lock, never before its wait.
            if (!qualifies(position, pointerSource)) return@transaction
            val press = Press(routeId, edge, position, ++contactSequence, qualifies)
            presses[routeId] = press
            when (edge.action) {
                PhoneHidAction.TAP -> {
                    val admitted = ++generation
                    nativeContact = null
                    retireContact(out)
                    cancelControl()
                    if (admitted != generation || presses[routeId] !== press || !allowsNew(press, position)) {
                        presses.remove(routeId); return@transaction
                    }
                    held = press
                    contact(press, Touch.Phase.DOWN, position, edge.eventTimeMs, out)
                }
                PhoneHidAction.FUNCTION -> functionDown(press, out)
                else -> Unit
            }
            return@transaction
        }
        if (old == null) {
            if (edge.phase == PhoneHidPhase.CANCEL) {
                val latest = function?.latest
                if (latest?.id == routeId && latest.edge.samePress(edge)) cancelFunction()
            }
            return@transaction
        }
        if (!old.edge.samePress(edge)) return@transaction
        presses.remove(routeId)
        if (edge.phase == PhoneHidPhase.CANCEL) {
            if (held === old) retireContact(out)
            if (function?.latest === old) cancelFunction()
            return@transaction
        }
        val position = point
        if (position?.available != true || position.revision != old.point.revision || position.projected != old.point.projected) {
            if (held === old) retireContact(out)
            if (function?.latest === old) cancelFunction()
            return@transaction
        }
        when (old.edge.action) {
            PhoneHidAction.TAP -> if (held === old) contact(old, Touch.Phase.UP, position, edge.eventTimeMs, out)
            PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP -> {
                val admitted = ++generation
                nativeContact = null; retireContact(out)
                if (generation != admitted || !allowsNew(old, position)) return@transaction
                val pixels = if (old.edge.action == PhoneHidAction.SCROLL_DOWN) 160 else -160
                if (position.projected) position.target()?.let { scroll(Scroll(it, pixels)) }
                else out.add(ReceiverInput(ReceiverInputKind.SCROLL, position.revision, x = position.x, y = position.y, pixels = pixels))
            }
            PhoneHidAction.FUNCTION -> functionUp(old, edge.eventTimeMs, out)
        }
    }

    private fun contact(press: Press, phase: Touch.Phase, position: HeadPointerState, at: Long, out: MutableList<ReceiverInput>) {
        if (held !== press) return
        if (phase == Touch.Phase.DOWN && !allowsNew(press, position)) {
            held = null; presses.remove(press.id); return
        }
        if (phase == Touch.Phase.UP || phase == Touch.Phase.CANCEL) held = null
        if (position.projected) {
            val admitted = generation
            val target = position.target(phase != Touch.Phase.DOWN)
            if (target == null || position.geometry != geometry() || !touch(Touch(phase, press.contact, target))) {
                if (held === press) held = null
                presses.remove(press.id)
                if (generation == admitted) cancelControl()
            }
        } else {
            out.add(ReceiverInput(ReceiverInputKind.valueOf(phase.name), press.point.revision, press.contact,
                position.x, position.y, heldMs = (at - press.edge.downTimeMs).coerceAtLeast(0)))
        }
    }

    private fun retireContact(out: MutableList<ReceiverInput>, emit: Boolean = true) {
        val old = held ?: return
        held = null; presses.remove(old.id)
        if (old.point.projected) cancelControl()
        else if (emit) out.add(ReceiverInput(ReceiverInputKind.CANCEL, old.point.revision, old.contact))
    }

    /** Native input keeps its actual timestamps/IDs; the sidecar prevents cross-source ID collisions. */
    fun nativeTouch(event: Touch): Boolean = transaction { out ->
        if (!active()) return@transaction false
        if (event.phase == Touch.Phase.DOWN) {
            val admitted = ++generation
            nativeContact = event.contactId; retireContact(out)
            if (generation != admitted) return@transaction false
        }
        else if (nativeContact != event.contactId) return@transaction false
        if (event.phase == Touch.Phase.UP || event.phase == Touch.Phase.CANCEL) nativeContact = null
        active() && touch(event)
    }

    fun nativeAction(action: () -> Boolean): Boolean = transaction { out ->
        if (!active()) return@transaction false
        val admitted = ++generation
        nativeContact = null; retireContact(out)
        if (generation == admitted && active()) action() else false
    }

    /** Launch/resize/release already invalidate Receiver context; do not send under output/publication locks. */
    fun invalidate() = transaction { out -> retire(out, false) }

    private fun retire(out: MutableList<ReceiverInput>, emit: Boolean, clearPoint: Boolean = true) {
        generation++
        val old = held
        held = null; presses.clear(); nativeContact = null
        if (clearPoint) { point = null; pointerSource = null }
        cancelFunction()
        if (old != null && !old.point.projected && emit) out.add(ReceiverInput(ReceiverInputKind.CANCEL, old.point.revision, old.contact))
        cancelControl()
    }

    private fun cancelFunction() {
        functionTimerGeneration++
        val cancel = cancelFunctionTimer
        cancelFunctionTimer = null; function = null
        cancel?.invoke()
    }

    private fun functionDown(press: Press, out: MutableList<ReceiverInput>) {
        if (!allowsNew(press, press.point)) { presses.remove(press.id); return }
        val key = Triple(press.edge.attachmentId, press.edge.keyCode, press.edge.scanCode)
        var current = function
        if (current != null && (current.key != key || current.point.revision != press.point.revision ||
                current.latest?.let { allowsNew(it, press.point) } != true)) { cancelFunction(); current = null }
        if (current != null && current.held == null && press.edge.eventTimeMs - current.releasedAt >= 500) {
            val afterCompletion = generation + 1
            finishFunction(current, out); current = null
            if (generation != afterCompletion || point?.let { it.available && it.revision == press.point.revision && allowsNew(press, it) } != true) return
            presses[press.id] = press
        }
        val next = current ?: FunctionSequence(press.point, key).also { function = it }
        next.held = press; next.latest = press
        armFunction(next, press.edge.downTimeMs + 800)
    }

    private fun functionUp(press: Press, at: Long, out: MutableList<ReceiverInput>) {
        val current = function?.takeIf { it.held === press } ?: return
        current.held = null
        if (at - press.edge.downTimeMs >= 800) { cancelFunction(); return } // Existing LONG is a no-op.
        current.presses = (current.presses + 1).coerceAtMost(3)
        current.releasedAt = at
        armFunction(current, at + 500)
    }

    private fun armFunction(current: FunctionSequence, deadline: Long) {
        cancelFunctionTimer?.invoke()
        val timerGeneration = ++functionTimerGeneration
        cancelFunctionTimer = schedule(deadline) { transaction { out ->
            if (!active()) { cancelFunction(); return@transaction }
            if (function !== current || timerGeneration != functionTimerGeneration) return@transaction
            val position = point
            if (position?.available != true || position.revision != current.point.revision ||
                current.latest?.let { allowsNew(it, position) } != true) { cancelFunction(); return@transaction }
            if (now() < deadline) armFunction(current, deadline)
            else if (current.held != null) cancelFunction() else finishFunction(current, out)
        } }
    }

    private fun finishFunction(current: FunctionSequence, out: MutableList<ReceiverInput>) {
        if (function !== current) return
        cancelFunction()
        val position = point?.takeIf { it.available && it.revision == current.point.revision } ?: return
        if (current.latest?.let { allowsNew(it, position) } != true) return
        val admitted = generation + 1
        retire(out, true, clearPoint = false)
        if (generation != admitted || !active() || current.latest?.let { allowsNew(it, position) } != true) return
        when (current.presses) {
            1 -> if (position.projected) position.geometry?.let { navigation(Navigation.BACK, it) }
                else out.add(ReceiverInput(ReceiverInputKind.BACK, position.revision))
            2 -> if (position.projected) position.geometry?.let { navigation(Navigation.FUNCTION_DOUBLE, it) }
                else out.add(ReceiverInput(ReceiverInputKind.FUNCTION_DOUBLE, position.revision))
            3 -> out.add(ReceiverInput(ReceiverInputKind.SLEEP, position.revision))
        }
    }

    private fun allowsNew(press: Press, position: HeadPointerState): Boolean = active() && position.available &&
        (!position.projected || position.geometry == geometry()) && press.qualifies(position, pointerSource)
}
