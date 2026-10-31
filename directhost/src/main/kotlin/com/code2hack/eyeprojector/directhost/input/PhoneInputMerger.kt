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
    private val functionIntent: (FunctionIntent) -> Unit = {},
    private val functionIdentity: FunctionIdentity = sourceIdentity,
) {
    /** Process/source identity survives Session replacement; the original Session token stays local. */
    class FunctionIdentity {
        private val gesture = java.util.concurrent.atomic.AtomicLong()
        private val delivery = java.util.concurrent.atomic.AtomicLong()
        fun gesture() = gesture.getAndIncrement()
        fun delivery() = delivery.getAndIncrement()
    }
    companion object { private val sourceIdentity = FunctionIdentity() }

    private class Press(val id: Long, val edge: PhoneHidEdge, val point: HeadPointerState, val contact: Long,
        val qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean, val source: ReceiverUsability?)
    private data class NavigationToken(val route: Long, val attachment: Int, val key: Int, val scan: Int,
        val down: Long, val action: PhoneHidAction)
    private class FunctionPress(val press: Press, val context: FunctionIntentContext, val gestureId: Long)
    private var point: HeadPointerState? = null
    private var pointerSource: ReceiverUsability? = null
    private val presses = mutableMapOf<Long, Press>()
    private var held: Press? = null
    private var nativeContact: Long? = null
    private var contactSequence = 0L
    private var generation = 0L
    private var function: FunctionPress? = null
    private var cancelFunctionTimer: (() -> Unit)? = null
    private var functionTimerGeneration = 0L
    private val backHome = BackHomeGestureRecognizer<Press>(now, ::currentNavigation,
        { at, action -> schedule(at) { transaction { action() } } },
        { action, press -> transaction { out -> finishNavigation(press, when (action) {
            BackHomeGestureRecognizer.Action.BACK -> ReceiverInputKind.BACK
            BackHomeGestureRecognizer.Action.HOME -> ReceiverInputKind.HOME
        }, out) } })
    private var completionSequence = 0L // Under the existing control lock.
    private val emission = Object()
    private var nextEmission = 0L
    private sealed interface Emission {
        data class Receiver(val event: ReceiverInput) : Emission
        data class Function(val intent: FunctionIntent) : Emission
    }
    private class Output {
        val values = mutableListOf<Emission>()
        fun add(event: ReceiverInput) { values.add(Emission.Receiver(event)) }
        fun add(intent: FunctionIntent) { values.add(Emission.Function(intent)) }
    }
    private val transactionOutput = ThreadLocal<Output?>()
    private data class Completion(val ticket: Long, val output: List<Emission>)
    private val reentrantEmissions = ThreadLocal<MutableList<Completion>?>()

    /** Completed Receiver writes happen after releasing the short control lock. */
    private fun <T> transaction(action: (Output) -> T): T {
        transactionOutput.get()?.let { return action(it) }
        val output = Output()
        val committed = synchronized(lock) {
            transactionOutput.set(output)
            val result = try { runCatching { action(output) } } finally { transactionOutput.remove() }
            result to if (output.values.isEmpty()) null else Completion(completionSequence++, output.values.toList())
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
            try { value.output.forEach { when (it) {
                is Emission.Receiver -> receiver(it.event)
                is Emission.Function -> functionIntent(it.intent)
            } } }
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
        if (old != null && (old.revision != next.revision || old.projected != next.projected || old.geometry != next.geometry))
            retire(out, false)
        else if (!next.available) retire(out, false, preserveNavigation = true)
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
            val dedicated = edge.action == PhoneHidAction.BACK_HOME || edge.action == PhoneHidAction.LOCK
            val position = point?.takeIf { (dedicated || it.available) && (!it.projected || it.geometry == geometry()) } ?: return@transaction
            // This check happens only AFTER acquiring the real control lock, never before its wait.
            if (!qualifies(position, pointerSource)) return@transaction
            val press = Press(routeId, edge, position, ++contactSequence, qualifies, pointerSource)
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
                PhoneHidAction.FUNCTION -> functionDown(press)
                PhoneHidAction.BACK_HOME -> backHome.down(navigationToken(routeId, edge),
                    Triple(edge.attachmentId, edge.keyCode, edge.scanCode), press)
                else -> Unit
            }
            return@transaction
        }
        if (old == null) {
            if (edge.phase == PhoneHidPhase.CANCEL) {
                val latest = function?.press
                if (latest?.id == routeId && latest.edge.samePress(edge)) cancelFunction()
                if (edge.action == PhoneHidAction.BACK_HOME) backHome.cancel(navigationToken(routeId, edge))
            }
            return@transaction
        }
        if (!old.edge.samePress(edge)) return@transaction
        presses.remove(routeId)
        if (edge.phase == PhoneHidPhase.CANCEL) {
            if (held === old) retireContact(out)
            if (function?.press === old) cancelFunction()
            if (old.edge.action == PhoneHidAction.BACK_HOME) backHome.cancel(navigationToken(routeId, edge))
            return@transaction
        }
        if (old.edge.action == PhoneHidAction.BACK_HOME || old.edge.action == PhoneHidAction.LOCK) {
            if (!currentNavigation(old)) backHome.cancel(navigationToken(routeId, edge))
            else if (old.edge.action == PhoneHidAction.BACK_HOME) backHome.up(navigationToken(routeId, edge), edge.eventTimeMs)
            else finishNavigation(old, ReceiverInputKind.LOCK, out)
            return@transaction
        }
        val position = point
        if (position?.available != true || position.revision != old.point.revision || position.projected != old.point.projected) {
            if (held === old) retireContact(out)
            if (function?.press === old) cancelFunction()
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
            PhoneHidAction.BACK_HOME, PhoneHidAction.LOCK -> Unit // Completed above without spatial input.
        }
    }

    private fun contact(press: Press, phase: Touch.Phase, position: HeadPointerState, at: Long, out: Output) {
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

    private fun retireContact(out: Output, emit: Boolean = true) {
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

    private fun retire(out: Output, emit: Boolean, clearPoint: Boolean = true, preserveNavigation: Boolean = false) {
        generation++
        val old = held
        held = null
        if (preserveNavigation) presses.entries.removeAll { it.value.edge.action != PhoneHidAction.BACK_HOME && it.value.edge.action != PhoneHidAction.LOCK }
        else { presses.clear(); backHome.cancel() }
        nativeContact = null
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

    private fun functionDown(press: Press) {
        if (!allowsNew(press, press.point)) { presses.remove(press.id); return }
        cancelFunction()
        val source = pointerSource ?: return
        val next = FunctionPress(press, FunctionIntentContext(source, press.point), functionIdentity.gesture())
        function = next
        armFunction(next, press.edge.downTimeMs + 800)
    }

    private fun functionUp(press: Press, at: Long, out: Output) {
        val current = function?.takeIf { it.press === press } ?: return
        finishFunction(current, if (at - press.edge.downTimeMs >= 800) FunctionIntentKind.REGISTER
            else FunctionIntentKind.ACTIVATE, out)
    }

    private fun armFunction(current: FunctionPress, deadline: Long) {
        cancelFunctionTimer?.invoke()
        val timerGeneration = ++functionTimerGeneration
        cancelFunctionTimer = schedule(deadline) { transaction { out ->
            if (!active()) { cancelFunction(); return@transaction }
            if (function !== current || timerGeneration != functionTimerGeneration) return@transaction
            if (now() < deadline) armFunction(current, deadline)
            else finishFunction(current, FunctionIntentKind.REGISTER, out)
        } }
    }

    private fun finishFunction(current: FunctionPress, kind: FunctionIntentKind, out: Output) {
        if (function !== current) return
        cancelFunction() // A long's eventual physical UP cannot become an activation.
        val position = point?.takeIf { it.available && it.revision == current.context.pointer.revision } ?: return
        if (!allowsNew(current.press, position)) return
        out.add(FunctionIntent(kind, FunctionSource.PHONE_HID, current.gestureId, functionIdentity.delivery(), current.context))
    }

    private fun allowsNew(press: Press, position: HeadPointerState): Boolean = active() && position.available &&
        (!position.projected || position.geometry == geometry()) && press.qualifies(position, pointerSource)

    private fun navigationToken(route: Long, edge: PhoneHidEdge) = NavigationToken(route, edge.attachmentId,
        edge.keyCode, edge.scanCode, edge.downTimeMs, edge.action)

    private fun currentNavigation(press: Press): Boolean {
        val position = point ?: return false
        val original = press.source
        val current = pointerSource
        return active() && position.revision == press.point.revision && position.projected == press.point.projected &&
            position.geometry == press.point.geometry && (!position.projected || position.geometry == geometry()) &&
            (if (original == null) current == null else current != null && original.ownerEpoch == current.ownerEpoch &&
                original.incarnation == current.incarnation && original.inputRevision == current.inputRevision && original.mode == current.mode) &&
            press.qualifies(position, current)
    }

    private fun finishNavigation(press: Press, kind: ReceiverInputKind, out: Output) {
        if (!currentNavigation(press)) return
        val admitted = generation + 1
        retire(out, true, clearPoint = false)
        if (generation != admitted || !currentNavigation(press)) return
        if (kind == ReceiverInputKind.LOCK || !press.point.projected) out.add(ReceiverInput(kind, press.point.revision))
        else press.point.geometry?.let { navigation(if (kind == ReceiverInputKind.BACK) Navigation.BACK else Navigation.CONTEXTUAL_HOME, it) }
    }
}
