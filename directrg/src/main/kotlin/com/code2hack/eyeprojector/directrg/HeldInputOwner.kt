package com.code2hack.eyeprojector.directrg

import java.lang.ref.WeakReference

/** One output contact, with source ownership kept separate from Android's real down-time. */
internal class HeldInputOwner(private val dispatcher: HeldPointerDispatcher) {
    private class Press(val owner: Any, val downTimeMs: Long)
    private class Retired(press: Press) {
        val owner = WeakReference(press.owner)
        val downTimeMs = press.downTimeMs
    }
    private class Pending(val press: Press, val down: PadGestureRecognizer.Event, val generation: Long) {
        val following = mutableListOf<PadGestureRecognizer.Event>()
    }

    private var press: Press? = null
    private var pending: Pending? = null
    private val retired = mutableListOf<Retired>()
    private var generation = 0L
    private var dispatchDepth = 0
    private var draining = false
    private var nativePress: Press? = null
    private var lastNativeDownTimeMs = -1L

    val holding: Boolean get() = dispatcher.holding

    /**
     * [owner] is compared by reference; a HID adapter supplies a fresh token per physical press.
     * A reentrant replacement is drained after the outer dispatcher call returns, and returns false
     * here because it has not acquired an output contact yet. Input consumption belongs to the adapter.
     */
    fun accept(owner: Any, event: PadGestureRecognizer.Event): Boolean {
        retired.removeAll { it.owner.get() == null }
        if (event.phase == PadGestureRecognizer.Phase.DOWN) {
            val old = press
            if (old?.owner === owner && old.downTimeMs == event.downTimeMs) return dispatcher.holding
            val waiting = pending
            if (waiting?.press?.owner === owner && waiting.press.downTimeMs == event.downTimeMs) return false
            if (retired.any { it.owner.get() === owner && it.downTimeMs == event.downTimeMs }) return false

            val next = Press(owner, event.downTimeMs)
            retire(old)
            retire(waiting?.press)
            press = null
            pending = null
            val currentGeneration = ++generation
            if (dispatchDepth > 0) {
                // LocalPointerDispatcher must finish its deferred CANCEL before another DOWN.
                pending = Pending(next, event, currentGeneration)
                return false
            }
            return start(next, event, currentGeneration)
        }

        val waiting = pending
        if (waiting?.press?.owner === owner && waiting.press.downTimeMs == event.downTimeMs) {
            if (event.phase == PadGestureRecognizer.Phase.CANCEL) cancel(owner, event.timeMs)
            else waiting.following.add(event)
            return false
        }
        val old = press ?: return false
        if (old.owner !== owner || old.downTimeMs != event.downTimeMs) return false
        if (event.phase == PadGestureRecognizer.Phase.UP || event.phase == PadGestureRecognizer.Phase.CANCEL) {
            retire(old)
            press = null // Release ownership before any callback can acquire the next context.
        }
        val accepted = dispatch { dispatcher.accept(event) }
        if ((!accepted || !dispatcher.holding) && press === old) { retire(old); press = null }
        return accepted
    }

    private fun start(next: Press, event: PadGestureRecognizer.Event, currentGeneration: Long): Boolean {
        // Force a handoff even when two independent sources share the same uptime millisecond.
        dispatch { dispatcher.cancel(event.timeMs) }
        if (generation != currentGeneration) { retire(next); return false }
        press = next // A route may synchronously cancel or queue another contact.
        val accepted = dispatch { dispatcher.accept(event) }
        if ((!accepted || !dispatcher.holding) && press === next) { retire(next); press = null }
        return accepted
    }

    private fun <T> dispatch(action: () -> T): T {
        dispatchDepth++
        try { return action() }
        finally {
            dispatchDepth--
            if (dispatchDepth == 0) drain()
        }
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (true) {
                val waiting = pending ?: break
                pending = null
                if (generation != waiting.generation) { retire(waiting.press); continue }
                if (!start(waiting.press, waiting.down, waiting.generation)) continue
                for (event in waiting.following) {
                    if (generation != waiting.generation || press !== waiting.press) break
                    accept(waiting.press.owner, event)
                }
            }
        } finally { draining = false }
    }

    /** The native source has one physical contact; a retired DOWN cannot reacquire after HID handoff. */
    fun acceptNative(event: PadGestureRecognizer.Event): Boolean {
        if (event.phase == PadGestureRecognizer.Phase.DOWN) {
            val old = nativePress
            if (old?.downTimeMs == event.downTimeMs) return accept(old.owner, event)
            if (event.downTimeMs <= lastNativeDownTimeMs) return false
            val next = Press(Any(), event.downTimeMs)
            nativePress = next
            lastNativeDownTimeMs = event.downTimeMs
            return accept(next.owner, event)
        }
        val old = nativePress ?: return false
        if (old.downTimeMs != event.downTimeMs) return false
        if (event.phase == PadGestureRecognizer.Phase.UP || event.phase == PadGestureRecognizer.Phase.CANCEL) nativePress = null
        return accept(old.owner, event)
    }

    fun cancel(timeMs: Long) {
        generation++
        retire(press)
        retire(pending?.press)
        press = null
        pending = null
        nativePress = null
        dispatch { dispatcher.cancel(timeMs) }
    }

    fun cancel(owner: Any, timeMs: Long) {
        val old = press
        val waiting = pending
        if (old?.owner !== owner && waiting?.press?.owner !== owner) return
        generation++
        retire(old)
        retire(waiting?.press)
        press = null
        pending = null
        if (nativePress?.owner === owner) nativePress = null
        dispatch { dispatcher.cancel(timeMs) }
    }

    private fun retire(old: Press?) {
        if (old == null) return
        retired.removeAll { it.owner.get() == null }
        if (retired.none { it.owner.get() === old.owner && it.downTimeMs == old.downTimeMs }) retired.add(Retired(old))
    }
}
