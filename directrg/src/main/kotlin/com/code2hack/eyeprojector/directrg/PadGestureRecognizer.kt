package com.code2hack.eyeprojector.directrg

/**
 * Normalizes firmware classifications, not physical touch edges. NOTIFICATION is a short
 * marker even for a held finger (see rokid-long-touch-2026-10-20.md). Its UP is never a release.
 * Times use Android uptime. Ambiguous tap markers use the same replay offset, retaining tap
 * intervals. A definitive swipe needs that offset only while earlier input is still pending.
 */
internal class PadGestureRecognizer(
    val doubleTapMs: Long,
    private val longPressMs: Long,
    private val emit: (Action) -> Unit,
) {
    enum class Key { CONTACT, TAP, DOUBLE, BACK, FORWARD, BACKWARD, FORWARD_ALIAS, BACKWARD_ALIAS, FORWARD_TAIL, BACKWARD_TAIL, HELD_CONTACT }
    enum class Phase { DOWN, MOVE, UP, CANCEL }
    enum class Timing { CONTACT_MARKER, INFERRED_SUMMARY }
    data class Event(
        val key: Key, val phase: Phase, val timeMs: Long, val downTimeMs: Long,
        val repeat: Int = 0, val position: PointerPosition? = null,
    )
    sealed interface Action {
        val timeMs: Long
        data class Tap(
            val downTimeMs: Long, val upTimeMs: Long, val position: PointerPosition?, val timing: Timing,
            override val timeMs: Long,
        ) : Action
        data class Scroll(val pixels: Int, val position: PointerPosition?, override val timeMs: Long) : Action
        /** Only a source with real sustained DOWN/release may supply these events. */
        data class Contact(val event: Event) : Action { override val timeMs = event.timeMs }
        /** Classification only: it supplies neither sustained contact nor release. EP-11 owns those. */
        data class LongPress(val markerDownTimeMs: Long?, override val timeMs: Long) : Action
        data class Back(override val timeMs: Long) : Action
    }
    private data class Press(val event: Event)
    private data class Pending(val origin: Long, val due: Long, val action: Action)
    private data class SummarySingle(val up: Event, var matched: Boolean = false)
    private val presses = mutableMapOf<Key, Press>()
    private val completed = mutableMapOf<Key, Long>()
    private val pending = mutableListOf<Pending>()
    private val summarySingles = mutableListOf<SummarySingle>()
    private var continuousReports = false
    private var contactReports = false
    private var lastEventMs = 0L
    private var canceledThrough = -1L
    private var epoch = 0L
    private var held: Event? = null
    private var lastSwipe: Event? = null
    private var swipeFirst: Event? = null
    val deadline: Long? get() = (pending.map { it.due } + presses.values
        .map { pressDeadline(it.event) }).minOrNull()
    val hasWork get() = pending.isNotEmpty() || presses.isNotEmpty() || held != null

    init { require(doubleTapMs in 100..600 && longPressMs in 200..2_000) }

    fun accept(event: Event, receiptUptimeMs: Long) {
        // Related summaries can arrive after a UI transition or with an earlier timestamp.
        if (continuousReports && event.key in listOf(Key.CONTACT, Key.TAP, Key.DOUBLE)) return
        if (contactReports && event.key in listOf(Key.TAP, Key.DOUBLE)) return
        if (event.key != Key.HELD_CONTACT && event.downTimeMs <= (completed[event.key] ?: -1L)) return
        if (event.downTimeMs <= canceledThrough) return
        if (event.timeMs <= 0 || event.timeMs < lastEventMs || event.timeMs > receiptUptimeMs ||
            receiptUptimeMs - event.timeMs > MAX_EVENT_AGE_MS || event.downTimeMs < 0 || event.downTimeMs > event.timeMs) {
            cancel(receiptUptimeMs); return
        }
        lastEventMs = event.timeMs
        // Android repeats are the same held press, never a new gesture or a release.
        if (event.phase == Phase.CANCEL) {
            if (event.key == Key.HELD_CONTACT) contact(event) else cancel(event.timeMs)
            return
        }
        if (event.repeat != 0) return
        if (event.key == Key.HELD_CONTACT) { contact(event); return }
        if (event.phase == Phase.MOVE) return
        if (event.key == Key.FORWARD_TAIL || event.key == Key.BACKWARD_TAIL) {
            if (event.phase == Phase.DOWN) {
                completed[event.key] = event.downTimeMs
                val first = swipeFirst
                swipeFirst = null
                if (first != null && event.timeMs - first.timeMs in 0..500 &&
                    (first.key == Key.FORWARD) == (event.key == Key.FORWARD_TAIL)) {
                    presses.remove(first.key); completed[first.key] = first.downTimeMs
                    // Legacy first-key UP may already have classified this same sequence.
                    if (lastSwipe?.downTimeMs != first.downTimeMs) swipe(first, event)
                    confirm(receiptUptimeMs)
                } else if (first != null) retireUnclassifiedSwipe(first, event.timeMs)
            }
            return
        }
        if (event.key == Key.CONTACT) { contactReports = true; summarySingles.clear() }
        if (event.phase == Phase.DOWN) {
            if (presses[event.key]?.event?.downTimeMs == event.downTimeMs) return
            swipeFirst?.let { retireUnclassifiedSwipe(it, event.timeMs) }
            swipeFirst = if (event.key == Key.FORWARD || event.key == Key.BACKWARD) event else null
            presses.remove(event.key) // A missing UP cannot be completed by another DOWN.
            presses[event.key] = Press(event)
            return
        }
        val press = presses.remove(event.key)?.event ?: return
        if (press.downTimeMs != event.downTimeMs) return
        completed[event.key] = event.downTimeMs
        when (event.key) {
            Key.CONTACT -> {
                // The duration describes the marker pulse, not the user's hold duration.
                if (event.timeMs - event.downTimeMs >= longPressMs) return
                val action = Action.Tap(press.timeMs, event.timeMs, press.position,
                    Timing.CONTACT_MARKER, event.timeMs + CLASSIFICATION_MS)
                pending.add(Pending(press.timeMs, action.timeMs, action))
            }
            Key.TAP -> if (event.timeMs - event.downTimeMs < longPressMs) {
                val action = Action.Tap(press.timeMs, event.timeMs, press.position,
                    Timing.INFERRED_SUMMARY, event.timeMs + doubleTapMs)
                pending.add(Pending(press.timeMs, action.timeMs, action))
                summarySingles.removeAll { event.timeMs - it.up.timeMs > CLASSIFICATION_MS }
                summarySingles.add(SummarySingle(event))
            }
            Key.DOUBLE -> summaryPair(press, event)
            Key.FORWARD, Key.BACKWARD, Key.FORWARD_ALIAS, Key.BACKWARD_ALIAS -> swipe(press, event)
            Key.BACK -> { cancel(event.timeMs); emit(Action.Back(event.timeMs)) }
            else -> Unit
        }
        confirm(receiptUptimeMs)
    }

    private fun summaryPair(down: Event, up: Event) {
        if (up.timeMs - down.timeMs >= longPressMs) return
        summarySingles.removeAll { down.timeMs - it.up.timeMs > CLASSIFICATION_MS }
        // Retain single-report provenance after replay, so a delayed pair summary cannot replay it.
        val singles = summarySingles.filter { !it.matched }.takeLast(2)
        singles.forEach { it.matched = true }
        if (singles.size >= 2) return // Their firmware pair summary adds no third touch.
        val gap = doubleTapMs / 2
        val starts = if (singles.size == 1) listOf(down.timeMs) else listOf(down.timeMs - gap, down.timeMs)
        for (start in starts) {
            val action = Action.Tap(start, start + minOf(20L, up.timeMs - down.timeMs), down.position,
                Timing.INFERRED_SUMMARY, start + 20 + doubleTapMs)
            pending.add(Pending(start, action.timeMs, action))
        }
    }

    private fun swipe(down: Event, up: Event) {
        val contact = held
        if (contact != null) {
            held = null
            emit(Action.Contact(contact.copy(phase = Phase.CANCEL, timeMs = up.timeMs)))
        }
        val pixels = if (down.key in listOf(Key.FORWARD, Key.FORWARD_ALIAS)) 160 else -160
        val old = lastSwipe
        // Retain legacy alternate key reports, without counting their companion twice.
        if (old != null && old.key != down.key &&
            up.timeMs - old.timeMs in 0..500 &&
            (old.key in listOf(Key.FORWARD, Key.FORWARD_ALIAS)) == (pixels > 0)) return
        val marker = takeMarker(up.timeMs, old?.timeMs ?: -1)
        lastSwipe = down.copy(timeMs = up.timeMs)
        val origin = marker?.origin ?: down.timeMs
        // Classification has already replaced this swipe's ambiguous marker. With no earlier
        // queued input there is nothing left to disambiguate or wait for. Keep the existing
        // replay schedule behind pending taps so a swipe cannot jump ahead of them.
        val due = if (pending.isEmpty()) up.timeMs
            else marker?.due ?: up.timeMs + if (contactReports) CLASSIFICATION_MS else doubleTapMs
        pending.add(Pending(origin, due, Action.Scroll(pixels,
            (marker?.action as? Action.Tap)?.position ?: down.position, due)))
    }

    private fun takeMarker(at: Long, after: Long = -1): Pending? {
        val marker = pending.lastOrNull { it.action is Action.Tap &&
            it.action.timing == Timing.CONTACT_MARKER && it.origin > after && at - it.origin in 0..CLASSIFICATION_MS }
        // Some firmware classifications precede the marker UP.
        presses[Key.CONTACT]?.event?.let {
            if (it.timeMs > maxOf(after, marker?.origin ?: -1) && at - it.timeMs in 0..CLASSIFICATION_MS) {
                presses.remove(Key.CONTACT); completed[Key.CONTACT] = it.downTimeMs
                return Pending(it.timeMs, at + CLASSIFICATION_MS,
                    Action.Tap(it.timeMs, at, it.position, Timing.CONTACT_MARKER, at + CLASSIFICATION_MS))
            }
        }
        if (marker != null) pending.remove(marker)
        return marker
    }

    fun classifyLongPress(receiptUptimeMs: Long) {
        val marker = takeMarker(receiptUptimeMs)
        emit(Action.LongPress(marker?.origin, receiptUptimeMs))
    }

    private fun contact(event: Event) {
        when (event.phase) {
            Phase.DOWN -> {
                if (held != null) return
                continuousReports = true
                presses.remove(Key.CONTACT); presses.remove(Key.TAP); presses.remove(Key.DOUBLE)
                pending.removeAll { it.action is Action.Tap }
                summarySingles.clear()
                held = event
            }
            Phase.MOVE, Phase.UP, Phase.CANCEL -> if (held?.downTimeMs != event.downTimeMs) return
        }
        if (event.phase == Phase.UP || event.phase == Phase.CANCEL) held = null
        emit(Action.Contact(event))
    }

    fun confirm(nowUptimeMs: Long) {
        val expired = presses.values.map { it.event }.filter { nowUptimeMs >= pressDeadline(it) }
        for (event in expired) {
            presses.remove(event.key)
            completed[event.key] = maxOf(completed[event.key] ?: -1L, event.downTimeMs)
            if (event.key == Key.FORWARD || event.key == Key.BACKWARD) {
                // An incomplete directional sequence is not a marker tap.
                takeMarker(pressDeadline(event))
                swipeFirst = null
            }
        }
        val generation = epoch
        while (epoch == generation) {
            val next = pending.minWithOrNull(compareBy<Pending> { it.due }.thenBy { it.origin }) ?: break
            if (next.due > nowUptimeMs) break
            pending.remove(next) // Callbacks may synchronously cancel or change context.
            emit(next.action)
        }
    }

    fun cancel(nowUptimeMs: Long = lastEventMs) {
        val old = held
        held = null; continuousReports = false; pending.clear(); presses.clear(); summarySingles.clear(); swipeFirst = null
        // Like completed key identities, retain swipe provenance: a dispatch callback may
        // change context before this already-classified gesture's companion reports arrive.
        canceledThrough = maxOf(canceledThrough, nowUptimeMs); epoch++
        if (old != null) emit(Action.Contact(old.copy(phase = Phase.CANCEL, timeMs = nowUptimeMs)))
    }

    companion object {
        const val MAX_EVENT_AGE_MS = 250L
        // Two measured AI classifications arrived ~815 ms after the initial 22 ms marker.
        // This is a replay choice for that firmware evidence, not a measured universal bound.
        const val CLASSIFICATION_MS = 1_000L
    }
    private fun pressDeadline(event: Event) = event.downTimeMs +
        if (event.key == Key.FORWARD || event.key == Key.BACKWARD) 500L else CLASSIFICATION_MS

    private fun retireUnclassifiedSwipe(first: Event, at: Long) {
        if (presses[first.key]?.event?.downTimeMs != first.downTimeMs) return
        presses.remove(first.key); completed[first.key] = first.downTimeMs
        takeMarker(minOf(at, pressDeadline(first)))
    }
}
