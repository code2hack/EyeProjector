package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlAdmission
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorControlDisposition

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
    private val anchorIntent: (AnchorIntent) -> Unit = {},
    private val anchorIdentity: AnchorIdentity = sourceIdentity,
    private val anchorControl: (AnchorControlAdmission) -> AnchorControlDisposition = { AnchorControlDisposition.NORMAL },
    private val cancelPassiveControl: () -> Unit = cancelControl,
    private val captureHeldControl: (AnchorControlAdmission) -> Unit = {},
    private val originalAnchorIntent: ((AnchorIntent, AnchorControlAdmission) -> Unit)? = null,
    private val receiveControl: (AnchorControlAdmission, Boolean) -> Unit = { _, _ -> },
    private val passiveAllowed: () -> Boolean = { true },
    private val registrationRelease: (AnchorIntent, Boolean) -> Unit = { _, _ -> },
    private val navigationSchedule: (Long, () -> Unit) -> (() -> Unit) = schedule,
) {
    /** Process/source identity survives Session replacement; the original Session token stays local. */
    class AnchorIdentity {
        private val gesture = java.util.concurrent.atomic.AtomicLong()
        private val delivery = java.util.concurrent.atomic.AtomicLong()
        fun gesture() = gesture.getAndIncrement()
        fun delivery() = delivery.getAndIncrement()
    }
    companion object { private val sourceIdentity = AnchorIdentity() }

    private class Press(val id: Long, val edge: PhoneHidEdge, val point: HeadPointerState, val contact: Long,
        val qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean, val source: ReceiverUsability?,
        val qualifiesContext: (HeadPointerState, ReceiverUsability?) -> Boolean,
        val control: AnchorControlAdmission = AnchorControlAdmission())
    private data class NavigationToken(val route: Long, val attachment: Int, val key: Int, val scan: Int,
        val down: Long, val action: PhoneHidAction)
    private class AnchorPress(val press: Press, val context: AnchorIntentContext, val gestureId: Long)
    private class ReceivedPress(val edge: PhoneHidEdge, val control: AnchorControlAdmission = AnchorControlAdmission())
    private var point: HeadPointerState? = null
    private var pointerSource: ReceiverUsability? = null
    private val presses = java.util.concurrent.ConcurrentHashMap<Long, Press>()
    private var held: Press? = null
    private var nativeContact: Long? = null
    private val nativeControls = java.util.concurrent.ConcurrentHashMap<Long, AnchorControlAdmission>()
    private val receivedPresses = java.util.concurrent.ConcurrentHashMap<Long, ReceivedPress>()
    private var navigationOriginal: Press? = null
    private val anchorInput = AnchorInput()
    private var contactSequence = 0L
    private var generation = 0L
    private val backHome = HidNavigationGestureRecognizer<Press>(now, ::currentNavigation,
        { at, action -> navigationSchedule(at) { transaction { action() } } },
        { action, press -> transaction { out -> finishNavigation(press, when (action) {
            HidNavigationGestureRecognizer.Action.BACK -> ReceiverInputKind.BACK
            HidNavigationGestureRecognizer.Action.HOME -> ReceiverInputKind.HOME
            HidNavigationGestureRecognizer.Action.LOCK -> ReceiverInputKind.LOCK
        }, out) } })
    private val backHomeReceipts = HidNavigationGestureRecognizer.Receipts<AnchorControlAdmission>(backHome.doublePressMs)
    private var completionSequence = 0L // Under the existing control lock.
    private val emission = Object()
    private var nextEmission = 0L
    private sealed interface Emission {
        data class Receiver(val event: ReceiverInput) : Emission
        data class Anchor(val intent: AnchorIntent, val original: AnchorControlAdmission) : Emission
        data class Release(val intent: AnchorIntent, val canceled: Boolean) : Emission
    }
    private class Output {
        val values = mutableListOf<Emission>()
        fun release(intent: AnchorIntent, canceled: Boolean) { values.add(Emission.Release(intent, canceled)) }
        fun add(event: ReceiverInput) { values.add(Emission.Receiver(event)) }
        fun add(intent: AnchorIntent, original: AnchorControlAdmission) { values.add(Emission.Anchor(intent, original)) }
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
                is Emission.Anchor -> originalAnchorIntent?.invoke(it.intent, it.original) ?: anchorIntent(it.intent)
                is Emission.Release -> registrationRelease(it.intent, it.canceled)
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
            // Session already retired Anchor before publishing this accepted usability context.
            // Pointer cleanup must not retire an Anchor birth received after that publication.
            retire(out, false, passive = true)
        }
        val old = point
        if (old != null && next.sequence <= old.sequence) return@transaction
        if (old != null && (old.revision != next.revision || old.projected != next.projected || old.geometry != next.geometry))
            retire(out, false, passive = sourceContextChanged, preserveRegistration = anchorInput.registration?.second?.context?.let { original ->
                source != null && source.ownerEpoch == original.usability.ownerEpoch &&
                    source.incarnation == original.usability.incarnation && source.mode == ReceiverUsabilityMode.LOCAL_PANEL &&
                    !next.projected && next.available && next.geometry == original.pointer.geometry
            } == true)
        else if (!next.available) retire(out, false, preserveNavigation = true, passive = true)
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
        stampedEdgeOriginal(routeId, edge, null, qualifiesContext, qualifies)
    }

    fun stampedEdgeOriginal(routeId: Long, edge: PhoneHidEdge, originalReceipt: AnchorControlAdmission?,
        qualifiesContext: (HeadPointerState, ReceiverUsability?) -> Boolean,
        qualifies: (HeadPointerState, ReceiverUsability?) -> Boolean) {
        // The terminal of the original held Anchor is not a fresh command in chooser mode.
        val registered = anchorInput.registration
        if (registered != null && registered.first.press.id == routeId &&
            registered.first.press.edge.samePress(edge) && edge.phase != PhoneHidPhase.DOWN) {
            transaction { out ->
                if (anchorInput.registration === registered) {
                    anchorInput.release(registered, edge, out)
                }
            }
            return
        }
        // Correlate the represented physical press before stamping/removing any current receipt.
        // Reads are lock-free; a fresh receipt observes CANCELING before its first map/merger wait.
        val existing = receivedPresses[routeId]
        if (existing == null && edge.phase == PhoneHidPhase.CANCEL && edge.action == PhoneHidAction.NAVIGATION) {
            // Physical UP disposed its raw record; this captured route still owns pending grammar cleanup.
            val token = navigationToken(routeId, edge)
            backHomeReceipts.cancel(token)
            transaction {
                val original = backHome.capturedSequence(token)
                if (original != null && navigationOriginal === original) navigationOriginal = null
                backHome.cancel(token)
            }
            return
        }
        val received = if (existing != null) {
            if (!existing.edge.samePress(edge)) return
            receiveControl(existing.control, edge.action == PhoneHidAction.ANCHOR)
            existing
        } else {
            if (edge.phase != PhoneHidPhase.DOWN || edge.repeatCount != 0) return
            val fresh = ReceivedPress(edge, originalReceipt ?: AnchorControlAdmission())
            receiveControl(fresh.control, edge.action == PhoneHidAction.ANCHOR)
            if (edge.action == PhoneHidAction.NAVIGATION) {
                val prior = backHomeReceipts.down(navigationToken(routeId, edge),
                    Triple(edge.attachmentId, edge.keyCode, edge.scanCode), fresh.control, now())
                fresh.control.inherit(prior)
            }
            val installed = receivedPresses.putIfAbsent(routeId, fresh)
            if (installed == null) fresh else {
                if (!installed.edge.samePress(edge)) return
                installed.control.inherit(fresh.control)
                installed
            }
        }
        val original=received.control
        if (edge.action == PhoneHidAction.NAVIGATION) when(edge.phase) {
            PhoneHidPhase.UP -> backHomeReceipts.up(navigationToken(routeId, edge), edge.eventTimeMs)
            PhoneHidPhase.CANCEL -> backHomeReceipts.cancel(navigationToken(routeId, edge))
            else -> Unit
        }
        if (edge.phase != PhoneHidPhase.DOWN) receivedPresses.remove(routeId, received)
        if (original.terminal && (edge.action != PhoneHidAction.NAVIGATION || original.receivedDuringRetirement)) {
            if (edge.phase != PhoneHidPhase.DOWN) {
                val old = presses[routeId]
                if (old != null && old.control === original && old.edge.samePress(edge)) presses.remove(routeId, old)
            }
            return
        }
        transaction { out ->
        if (!active()) return@transaction
        val old = presses[routeId]
        if (edge.phase == PhoneHidPhase.DOWN) {
            if (old != null || edge.repeatCount != 0) return@transaction
            val dedicated = edge.action == PhoneHidAction.NAVIGATION
            val position = point?.takeIf { (!it.projected || it.geometry == geometry()) } ?: return@transaction
            // This check happens only AFTER acquiring the real control lock, never before its wait.
            if (!qualifiesContext(position, pointerSource)) return@transaction
            val press = Press(routeId, edge, position, ++contactSequence,
                if (dedicated) qualifiesContext else qualifies, pointerSource, qualifiesContext, original)
            presses[routeId] = press
            if (edge.action == PhoneHidAction.ANCHOR) {
                captureHeldControl(press.control)
                if (press.control.terminal) return@transaction
            }
            if (edge.action == PhoneHidAction.NAVIGATION) {
                val token = navigationToken(routeId, edge)
                backHome.down(token, Triple(edge.attachmentId, edge.keyCode, edge.scanCode), press)
                val original = backHome.capturedSequence(token) ?: return@transaction
                navigationOriginal = original
                try { anchorControl(original.control) }
                finally { press.control.inherit(original.control) }
                return@transaction
            }
            if (edge.action != PhoneHidAction.ANCHOR &&
                anchorControl(press.control) != AnchorControlDisposition.NORMAL) return@transaction
            if (!dedicated && !allowsNew(press, position)) { presses.remove(routeId); return@transaction }
            when (edge.action) {
                PhoneHidAction.TAP -> {
                    val admitted = ++generation
                    nativeContact = null
                    retireContact(out)
                    if (anchorControl(press.control) != AnchorControlDisposition.NORMAL) return@transaction
                    cancelControl()
                    if (admitted != generation || presses[routeId] !== press || !allowsNew(press, position)) {
                        presses.remove(routeId); return@transaction
                    }
                    held = press
                    contact(press, Touch.Phase.DOWN, position, edge.eventTimeMs, out)
                }
                PhoneHidAction.ANCHOR -> anchorInput.down(press, out)
                PhoneHidAction.NAVIGATION -> Unit
                else -> Unit
            }
            return@transaction
        }
        if (old == null) {
            if (edge.phase == PhoneHidPhase.CANCEL) {
                val latest = anchorInput.activePress?.press
                if (latest?.id == routeId && latest.edge.samePress(edge)) anchorInput.cancel()
                if (edge.action == PhoneHidAction.NAVIGATION) backHome.cancel(navigationToken(routeId, edge))
            }
            return@transaction
        }
        if (!old.edge.samePress(edge)) return@transaction
        presses.remove(routeId)
        if (old.control.terminal && old.edge.action != PhoneHidAction.NAVIGATION) return@transaction
        if (edge.phase == PhoneHidPhase.CANCEL) {
            if (held === old) retireContact(out)
            if (anchorInput.activePress?.press === old) anchorInput.cancel()
            if (old.edge.action == PhoneHidAction.NAVIGATION) backHome.cancel(navigationToken(routeId, edge))
            return@transaction
        }
        if (old.edge.action == PhoneHidAction.NAVIGATION) {
            if (!currentNavigation(old)) backHome.cancel(navigationToken(routeId, edge))
            else if (old.edge.action == PhoneHidAction.NAVIGATION) backHome.up(navigationToken(routeId, edge), edge.eventTimeMs)
            return@transaction
        }
        val position = point
        if (position?.available != true || position.revision != old.point.revision || position.projected != old.point.projected) {
            if (held === old) retireContact(out)
            if (anchorInput.activePress?.press === old) anchorInput.cancel()
            return@transaction
        }
        when (old.edge.action) {
            PhoneHidAction.TAP -> if (held === old) contact(old, Touch.Phase.UP, position, edge.eventTimeMs, out)
            PhoneHidAction.SCROLL_DOWN, PhoneHidAction.SCROLL_UP -> {
                if (anchorControl(old.control) != AnchorControlDisposition.NORMAL) return@transaction
                val admitted = ++generation
                nativeContact = null; retireContact(out)
                if (generation != admitted || !allowsNew(old, position)) return@transaction
                val pixels = if (old.edge.action == PhoneHidAction.SCROLL_DOWN) 160 else -160
                if (position.projected) position.target()?.let { scroll(Scroll(it, pixels)) }
                else out.add(ReceiverInput(ReceiverInputKind.SCROLL, position.revision, x = position.x, y = position.y, pixels = pixels))
            }
            PhoneHidAction.ANCHOR -> anchorInput.up(old, edge.eventTimeMs, out)
            PhoneHidAction.NAVIGATION -> Unit // Completed above without spatial input.
        }
        }
    }

    private fun contact(press: Press, phase: Touch.Phase, position: HeadPointerState, at: Long, out: Output) {
        if (held !== press) return
        if (anchorControl(press.control) != AnchorControlDisposition.NORMAL) {
            if (held === press) held = null
            return
        }
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
        if (old.point.projected) {
            if (anchorControl(old.control) == AnchorControlDisposition.NORMAL) cancelControl()
        }
        else if (emit) out.add(ReceiverInput(ReceiverInputKind.CANCEL, old.point.revision, old.contact))
    }

    /** Native input keeps its actual timestamps/IDs; the sidecar prevents cross-source ID collisions. */
    fun nativeTouch(event: Touch): Boolean {
        val original = if (event.phase == Touch.Phase.DOWN) nativeControls.computeIfAbsent(event.contactId) { AnchorControlAdmission() }
            else nativeControls[event.contactId] ?: return false
        receiveControl(original, false)
        if (event.phase == Touch.Phase.UP || event.phase == Touch.Phase.CANCEL) nativeControls.remove(event.contactId, original)
        if (original.terminal) return false
        return transaction { out ->
        if (!active()) return@transaction false
        if (anchorControl(original) != AnchorControlDisposition.NORMAL) return@transaction false
        if (event.phase == Touch.Phase.DOWN) {
            val admitted = ++generation
            nativeContact = event.contactId; retireContact(out)
            if (generation != admitted || anchorControl(original) != AnchorControlDisposition.NORMAL) return@transaction false
        }
        else if (nativeContact != event.contactId) return@transaction false
        if (event.phase == Touch.Phase.UP || event.phase == Touch.Phase.CANCEL) nativeContact = null
        active() && touch(event)
        }
    }

    fun nativeAction(action: () -> Boolean): Boolean = nativeAction(AnchorControlAdmission(), action)

    fun passiveAction(action: () -> Boolean): Boolean = transaction {
        if (active() && passiveAllowed()) action() else false
    }

    fun nativeAction(original: AnchorControlAdmission, action: () -> Boolean): Boolean {
        receiveControl(original, false)
        if (original.terminal) return false
        return transaction { out ->
        if (!active() || anchorControl(original) != AnchorControlDisposition.NORMAL) return@transaction false
        val admitted = ++generation
        nativeContact = null; retireContact(out)
        if (generation == admitted && active() && anchorControl(original) == AnchorControlDisposition.NORMAL) action() else false
        }
    }

    /** Launch/resize/release already invalidate Receiver context; do not send under output/publication locks. */
    fun invalidate() = transaction { out -> retire(out, false) }

    private fun retire(out: Output, emit: Boolean, clearPoint: Boolean = true, preserveNavigation: Boolean = false,
        passive: Boolean = false, preserveRegistration: Boolean = false) {
        generation++
        val old = held
        held = null
        presses.entries.removeAll { !it.value.control.terminal && (!preserveNavigation ||
            it.value.edge.action != PhoneHidAction.NAVIGATION) }
        if (!preserveNavigation && navigationOriginal?.control?.terminal != true) {
            backHome.cancel(); navigationOriginal = null
        }
        nativeContact = null
        if (clearPoint) { point = null; pointerSource = null }
        anchorInput.cancel()
        if (!preserveRegistration) anchorInput.cancelRegistration(out)
        if (old != null && !old.point.projected && emit) out.add(ReceiverInput(ReceiverInputKind.CANCEL, old.point.revision, old.contact))
        if (passive) cancelPassiveControl() else cancelControl()
    }

    /** Semantic Anchor owns its classified press, chooser-release lease and timer. Ordinary
     * Tap/Navigation only submit their own controls and never enter this component. */
    private inner class AnchorInput {
        var activePress: AnchorPress? = null
        @Volatile var registration: Pair<AnchorPress, AnchorIntent>? = null
        private var cancelTimer: (() -> Unit)? = null
        private var timerGeneration = 0L

        fun release(original: Pair<AnchorPress, AnchorIntent>, edge: PhoneHidEdge, out: Output) {
            if (registration !== original) return
            registration = null
            val press = original.first.press
            presses.remove(press.id, press)
            receivedPresses[press.id]?.takeIf { it.edge.samePress(edge) }?.let { receivedPresses.remove(press.id, it) }
            out.release(original.second, edge.phase != PhoneHidPhase.UP)
        }

        fun cancel() {
            timerGeneration++
            val cancel = cancelTimer
            cancelTimer = null; activePress = null
            cancel?.invoke()
        }

        fun cancelRegistration(out: Output) {
            val old = registration ?: return
            registration = null
            out.release(old.second, true)
        }

        fun down(press: Press, out: Output) {
            if (registration != null) {
                cancelRegistration(out)
                press.control.disposition = AnchorControlDisposition.RETIRED
                presses.remove(press.id, press)
                return
            }
            if (!allowsNew(press, press.point)) { presses.remove(press.id); return }
            cancel()
            val source = pointerSource ?: return
            val next = AnchorPress(press, AnchorIntentContext(source, press.point), anchorIdentity.gesture())
            activePress = next
            arm(next, press.edge.downTimeMs + 800)
        }

        fun up(press: Press, at: Long, out: Output) {
            val current = activePress?.takeIf { it.press === press } ?: return
            finish(current, if (at - press.edge.downTimeMs >= 800) AnchorIntentKind.REGISTER
                else AnchorIntentKind.ACTIVATE, out)
        }

        private fun arm(current: AnchorPress, deadline: Long) {
            cancelTimer?.invoke()
            val expected = ++timerGeneration
            cancelTimer = schedule(deadline) { transaction { out ->
                if (!active()) { cancel(); return@transaction }
                if (activePress !== current || expected != timerGeneration) return@transaction
                if (now() < deadline) arm(current, deadline)
                else finish(current, AnchorIntentKind.REGISTER, out, physicalHeld = true)
            } }
        }

        private fun finish(current: AnchorPress, kind: AnchorIntentKind, out: Output, physicalHeld: Boolean = false) {
            if (activePress !== current) return
            cancel() // A long's eventual physical UP cannot become an activation.
            if (kind == AnchorIntentKind.REGISTER) current.press.control.ordinary()
            if (kind == AnchorIntentKind.REGISTER && currentNavigation(current.press) &&
                anchorControl(current.press.control) != AnchorControlDisposition.NORMAL) return
            val position = point?.takeIf { it.available && it.revision == current.context.pointer.revision } ?: return
            if (!allowsNew(current.press, position)) return
            val intent = AnchorIntent(kind, AnchorSource.PHONE_HID, current.gestureId, anchorIdentity.delivery(), current.context)
            if (kind == AnchorIntentKind.REGISTER && physicalHeld) registration = current to intent
            out.add(intent, current.press.control)
            if (kind == AnchorIntentKind.REGISTER && !physicalHeld) out.release(intent, true)
        }
    }

    private fun allowsNew(press: Press, position: HeadPointerState): Boolean = active() && position.available &&
        (!position.projected || position.geometry == geometry()) && press.qualifies(position, pointerSource)

    private fun navigationToken(route: Long, edge: PhoneHidEdge) = NavigationToken(route, edge.attachmentId,
        edge.keyCode, edge.scanCode, edge.downTimeMs, edge.action)

    private fun currentNavigation(press: Press): Boolean {
        if (press.control.terminal) return active()
        val position = point ?: return false
        val original = press.source
        val current = pointerSource
        return active() && position.revision == press.point.revision && position.projected == press.point.projected &&
            position.geometry == press.point.geometry && (!position.projected || position.geometry == geometry()) &&
            (if (original == null) current == null else current != null && original.ownerEpoch == current.ownerEpoch &&
                original.incarnation == current.incarnation && original.inputRevision == current.inputRevision && original.mode == current.mode) &&
            press.qualifiesContext(position, current)
    }

    private fun finishNavigation(press: Press, kind: ReceiverInputKind, out: Output) {
        if (navigationOriginal === press) navigationOriginal = null
        if (!currentNavigation(press)) return
        if (anchorControl(press.control) != AnchorControlDisposition.NORMAL) return
        val admitted = generation + 1
        retire(out, true, clearPoint = false)
        if (generation != admitted || !currentNavigation(press) ||
            anchorControl(press.control) != AnchorControlDisposition.NORMAL) return
        if (kind == ReceiverInputKind.LOCK || !press.point.projected) out.add(ReceiverInput(kind, press.point.revision))
        else press.point.geometry?.let { navigation(if (kind == ReceiverInputKind.BACK) Navigation.BACK else Navigation.CONTEXTUAL_HOME, it) }
    }
}
