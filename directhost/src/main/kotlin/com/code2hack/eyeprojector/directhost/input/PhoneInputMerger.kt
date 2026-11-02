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
    private val navigationSchedule: (Long, () -> Unit) -> (() -> Unit) = schedule,
    // Issue149 supplies the authoritative mode owner. This default is component-only, not Mode readiness.
    private val hidMode: () -> Unit = {},
    // True consumes this semantic operation (Settings Tap/Back, mode-aware Scroll).
    private val hidAction: (PhoneHidAction, HeadPointerState) -> Boolean = { _, _ -> false },
) {
    private class Press(val id: Long, val edge: PhoneHidEdge, val point: HeadPointerState, val contact: Long,
        val qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean, val source: ReceiverUsability?,
        val qualifiesContext: (HeadPointerState, ReceiverUsability?) -> Boolean) {
        var cancelRepeat: (() -> Unit)? = null
        var consumed = false
    }
    private data class NavigationToken(val route: Long, val attachment: Int, val key: Int, val scan: Int,
        val down: Long, val action: PhoneHidAction)
    private class ReceivedPress(val edge: PhoneHidEdge)
    private var point: HeadPointerState? = null
    private var pointerSource: ReceiverUsability? = null
    private val presses = java.util.concurrent.ConcurrentHashMap<Long, Press>()
    private var held: Press? = null
    private var nativeContact: Long? = null
    private val receivedPresses = java.util.concurrent.ConcurrentHashMap<Long, ReceivedPress>()
    private var contactSequence = 0L
    private var generation = 0L
    private val gestures = PhoneHidAction.entries.filter { !it.learnsSingleOnly }.associateWith {
        PhoneHidGestureRecognizer<Press>(now, ::currentNavigation,
            { at, action -> navigationSchedule(at) { transaction { action() } } },
            { gesture, press -> transaction { out ->
                if (gesture == press.edge.gesture) finishGesture(press, out)
            } })
    }
    private var completionSequence = 0L // Under the existing control lock.
    private val emission = Object()
    private var nextEmission = 0L
    private sealed interface Emission {
        data class Receiver(val event: ReceiverInput) : Emission
        data class Effect(val run: () -> Unit) : Emission
        data object Mode : Emission
    }
    private class Output {
        val values = mutableListOf<Emission>()
        fun mode() { values.add(Emission.Mode) }
        fun effect(run: () -> Unit) { values.add(Emission.Effect(run)) }
        fun add(event: ReceiverInput) { values.add(Emission.Receiver(event)) }
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
                is Emission.Effect -> it.run()
                Emission.Mode -> hidMode()
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
        val sourceContextChanged = pointerSource?.let { old -> source != null &&
            (old.ownerEpoch != source.ownerEpoch || old.incarnation != source.incarnation ||
                old.inputRevision != source.inputRevision || old.mode != source.mode) } == true
        if (pointerSource != null && source != null && pointerSource!!.incarnation != source.incarnation) {
            // A new source retires ordinary contacts before accepting its pointer.
            // Pointer cleanup retains the current source publication.
            retire(out, false, passive = true, ordinaryOnly = true)
        }
        val old = point
        if (old != null && next.sequence <= old.sequence) return@transaction
        if (old != null && (old.revision != next.revision || old.projected != next.projected || old.geometry != next.geometry))
            retire(out, false, passive = true, ordinaryOnly = true)
        else if (!next.available) retire(out, false, preserveNavigation = true, passive = true, ordinaryOnly = true)
        if (!active() || !accepts()) return@transaction // Retirement may contain an old stock write.
        if (point?.let { it.sequence >= next.sequence } == true) return@transaction
        point = next; pointerSource = source
        val current = held
        if (current != null) contact(current, Touch.Phase.MOVE, next, now(), out)
    }

    fun edge(routeId: Long, edge: PhoneHidEdge) = stampedEdge(routeId, edge) { _, _ -> true }

    fun stampedEdge(routeId: Long, edge: PhoneHidEdge,
        qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean) =
        stampedEdge(routeId, edge, qualifies, qualifies)

    fun stampedEdge(routeId: Long, edge: PhoneHidEdge,
        qualifiesContext: (HeadPointerState, ReceiverUsability?) -> Boolean,
        qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean) {
        stampedEdgeOriginal(routeId, edge, qualifiesContext, qualifies)
    }

    fun stampedEdgeOriginal(routeId: Long, edge: PhoneHidEdge,
        qualifiesContext: (HeadPointerState, ReceiverUsability?) -> Boolean,
        qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean) {
        // Receipt belongs to the original DOWN even if later metadata or the current app changes.
        val existing = receivedPresses[routeId]
        val token = navigationToken(routeId, edge)
        if (existing == null && edge.phase == PhoneHidPhase.CANCEL && !edge.action.learnsSingleOnly) {
            transaction { gestures[edge.action]?.cancel(token) }
            return
        }
        val received = if (existing != null) {
            if (!existing.edge.samePress(edge)) return
            existing
        } else {
            if (edge.phase != PhoneHidPhase.DOWN || edge.repeatCount != 0) return
            val fresh = ReceivedPress(edge)
            val installed = receivedPresses.putIfAbsent(routeId, fresh)
            if (installed == null) fresh else {
                if (!installed.edge.samePress(edge)) return
                installed
            }
        }
        if (edge.phase != PhoneHidPhase.DOWN) receivedPresses.remove(routeId, received)
        transaction { out ->
            val old = presses[routeId]
            if (!active()) {
                if (old != null) {
                    presses.remove(routeId, old); stopRepeat(old)
                    if (held === old) retireContact(out)
                }
                gestures[edge.action]?.cancel(token)
                return@transaction
            }
            if (edge.phase == PhoneHidPhase.DOWN) {
                if (old != null || edge.repeatCount != 0) return@transaction
                val position = point?.takeIf { !it.projected || it.geometry == geometry() } ?: return@transaction
                if (!qualifiesContext(position, pointerSource)) return@transaction
                val dedicated = !edge.action.learnsSingleOnly
                val press = Press(routeId, edge, position, ++contactSequence,
                    if (dedicated) qualifiesContext else qualifies, pointerSource, qualifiesContext)
                presses[routeId] = press
                if (dedicated) {
                    gestures.getValue(edge.action).down(token, listOf(edge.attachmentId, edge.keyCode, edge.scanCode, edge.action, edge.gesture),
                        press, edge.eventTimeMs)
                    return@transaction
                }
                if (!allowsNew(press, position)) {
                    presses.remove(routeId, press); return@transaction
                }
                when (edge.action) {
                    PhoneHidAction.TAP -> {
                        val admitted = ++generation
                        retireContact(out)
                        if (admitted != generation || presses[routeId] !== press || !allowsNew(press, position)) {
                            presses.remove(routeId, press); return@transaction
                        }
                        held = press
                        out.effect { press.consumed = hidAction(PhoneHidAction.TAP, position) }
                        contact(press, Touch.Phase.DOWN, position, edge.eventTimeMs, out)
                    }
                    PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP -> {
                        val admitted = ++generation
                        retireContact(out)
                        if (generation != admitted || !allowsNew(press, position)) {
                            presses.remove(routeId, press); return@transaction
                        }
                        if (scrollPress(press, out)) repeatScroll(press, now() + 400)
                    }
                    else -> Unit
                }
                return@transaction
            }
            if (old == null || !old.edge.samePress(edge)) return@transaction
            presses.remove(routeId, old)
            stopRepeat(old)
            if (edge.phase == PhoneHidPhase.CANCEL) {
                if (held === old) retireContact(out)
                gestures[edge.action]?.cancel(token)
                return@transaction
            }
            if (!old.edge.action.learnsSingleOnly) {
                if (!currentNavigation(old)) gestures[edge.action]?.cancel(token)
                else gestures.getValue(edge.action).up(token, edge.eventTimeMs)
                return@transaction
            }
            // Scroll is completed by its original DOWN/repeat sequence, never by release.
            if (old.edge.action != PhoneHidAction.TAP || old.consumed) return@transaction
            val position = point
            if (position == null || !currentNavigation(old) || !position.available) {
                if (held === old) retireContact(out)
                return@transaction
            }
            if (held === old) contact(old, Touch.Phase.UP, position, edge.eventTimeMs, out)
        }
    }

    private fun stopRepeat(press: Press) {
        val cancel = press.cancelRepeat
        press.cancelRepeat = null
        cancel?.invoke()
    }

    private fun scrollPress(press: Press, out: Output): Boolean {
        val position = point ?: return false
        if (presses[press.id] !== press || !currentNavigation(press) || !allowsNew(press, position)) return false
        val pixels = if (press.edge.action == PhoneHidAction.SCROLL_DOWN) 160 else -160
        out.effect {
            if (!hidAction(press.edge.action, position)) {
                if (position.projected) position.target()?.let { scroll(Scroll(it,pixels)) }
                else receiver(ReceiverInput(ReceiverInputKind.SCROLL,position.revision,x=position.x,y=position.y,pixels=pixels))
            }
        }
        return true
    }

    private fun repeatScroll(press: Press, deadline: Long) {
        stopRepeat(press)
        if (presses[press.id] !== press || !currentNavigation(press)) return
        val cancel = navigationSchedule(deadline) { transaction { out ->
            if (presses[press.id] !== press || !currentNavigation(press)) return@transaction
            press.cancelRepeat = null
            if (now() < deadline) repeatScroll(press, deadline)
            else if (scrollPress(press, out)) repeatScroll(press, now() + 100)
            else presses.remove(press.id, press)
        } }
        if (presses[press.id] === press && currentNavigation(press)) press.cancelRepeat = cancel else cancel()
    }

    private fun contact(press: Press, phase: Touch.Phase, position: HeadPointerState, at: Long, out: Output) {
        if (held !== press) return
        if (phase == Touch.Phase.DOWN && !allowsNew(press, position)) {
            held = null; presses.remove(press.id); return
        }
        if (phase == Touch.Phase.UP || phase == Touch.Phase.CANCEL) held = null
        out.effect {
            if (!press.consumed) {
                if (position.projected) {
                    val target = position.target(phase != Touch.Phase.DOWN)
                    if (target != null && position.geometry == geometry() && !touch(Touch(phase,press.contact,target))) {
                        synchronized(lock) { if(held === press) held=null;presses.remove(press.id,press) }
                    }
                } else receiver(ReceiverInput(ReceiverInputKind.valueOf(phase.name),press.point.revision,press.contact,
                    position.x,position.y,heldMs=(at-press.edge.downTimeMs).coerceAtLeast(0)))
            }
        }
    }

    private fun retireContact(out: Output, emit: Boolean = true) {
        val originalNative = nativeContact
        nativeContact = null
        val originalHid = held
        held = null
        originalHid?.let { presses.remove(it.id) }
        // Replacing the source retires its original output before a mode operation can consume it.
        if (originalNative != null || originalHid?.point?.projected == true) {
            out.effect { cancelControl() }
        } else if (originalHid != null && emit) {
            out.add(ReceiverInput(ReceiverInputKind.CANCEL, originalHid.point.revision, originalHid.contact))
        }
    }

    /** Native contact bookkeeping is ordered with HID; effects emit outside the control lock. */
    fun nativeTouch(event: Touch): Boolean {
        var result=false
        transaction { out ->
            if (!active()) return@transaction
            val admitted=if(event.phase == Touch.Phase.DOWN) ++generation else generation
            if (event.phase == Touch.Phase.DOWN) {
                retireContact(out);nativeContact=event.contactId
            } else if (nativeContact != event.contactId) return@transaction
            if (event.phase == Touch.Phase.UP || event.phase == Touch.Phase.CANCEL) nativeContact=null
            out.effect {
                val current=synchronized(lock) { active() && (event.phase != Touch.Phase.DOWN ||
                    generation == admitted && nativeContact == event.contactId) }
                if(current) result=touch(event)
            }
        }
        return result
    }
    fun passiveAction(action: () -> Boolean): Boolean {
        var result=false
        transaction { out -> if(active()) out.effect { result=action() } }
        return result
    }
    fun nativeAction(action: () -> Boolean): Boolean {
        var result=false
        transaction { out ->
            if (!active()) return@transaction
            generation++;retireContact(out)
            out.effect { result=action() }
        }
        return result
    }

    /** Launch/resize/release already invalidate Receiver context; do not send under output/publication locks. */
    fun invalidate() = transaction { out -> retire(out, false) }
    fun invalidateOrdinary() = transaction { out -> retire(out, false, passive = true, ordinaryOnly = true) }
    fun invalidateForLaunch() = transaction { out ->
        check(active())
        retire(out,false,passive=true,ordinaryOnly=true)
    }
    fun sourceLost() = transaction {
        generation++
        held = null; nativeContact = null; point = null; pointerSource = null
        presses.values.forEach(::stopRepeat)
        presses.clear(); receivedPresses.clear()
        gestures.values.forEach { it.cancel() }
    }

    private fun retire(out: Output, emit: Boolean, clearPoint: Boolean = true, preserveNavigation: Boolean = false,
        passive: Boolean = false, preserveRegistration: Boolean = false, ordinaryOnly: Boolean = false,
        preserveScroll: Boolean = false) {
        generation++
        val old = held
        held = null
        presses.entries.removeAll {
            val scrolling = it.value.edge.action == PhoneHidAction.SCROLL_DOWN || it.value.edge.action == PhoneHidAction.SCROLL_UP
            val remove = (!preserveNavigation || it.value.edge.action.learnsSingleOnly) && (!preserveScroll || !scrolling)
            if (remove) stopRepeat(it.value)
            remove
        }
        if (!preserveNavigation) { gestures.values.forEach { it.cancel() } }
        nativeContact = null
        if (clearPoint) { point = null; pointerSource = null }
        if (old != null && !old.point.projected && emit) out.add(ReceiverInput(ReceiverInputKind.CANCEL, old.point.revision, old.contact))
        out.effect { cancelControl() }
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
            press.qualifiesContext(position, current)
    }

    private fun finishGesture(press: Press, out: Output) {
        if (!currentNavigation(press)) return
        val admitted = generation + 1
        retire(out, true, clearPoint = false, preserveNavigation = true, preserveScroll = true)
        if (generation != admitted || !currentNavigation(press)) return
        val position = point ?: return
        if (press.edge.action == PhoneHidAction.MODE) { out.mode(); return }
        out.effect {
            if (!hidAction(press.edge.action, position)) {
                val kind = when (press.edge.action) {
                    PhoneHidAction.BACK -> ReceiverInputKind.BACK
                    PhoneHidAction.HOME -> ReceiverInputKind.HOME
                    PhoneHidAction.LOCK -> ReceiverInputKind.LOCK
                    else -> return@effect
                }
                if (kind == ReceiverInputKind.LOCK || !position.projected) receiver(ReceiverInput(kind,position.revision))
                else position.geometry?.let { navigation(if (kind == ReceiverInputKind.BACK) Navigation.BACK else Navigation.CONTEXTUAL_HOME,it) }
            }
        }
    }
}
