package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

/** One key sequence, one gesture. All times are Android uptime milliseconds, never sensor time. */
internal class PadGestureRecognizer(
    val doubleTapMs: Long,
    private val longPressMs: Long,
    private val single: ()->Unit,
    private val back: ()->Unit,
    private val scroll: (Int)->Unit,
    private val disconnect: ()->Unit,
) {
    enum class Key { CONTACT, TAP, DOUBLE, BACK, FORWARD, BACKWARD }
    enum class Phase { DOWN, UP, CANCEL }
    data class Event(val key: Key, val phase: Phase, val timeMs: Long, val downTimeMs: Long, val repeat: Int=0)
    private data class Press(val key: Key, val downTimeMs: Long)
    private var press: Press?=null
    private var pending: Long?=null
    private var taps=0
    private var rawPairNeedsOem=false
    private var lastTapUpMs=0L
    private var lastDoubleDownMs=-1L
    private var lastDoubleUpMs=0L
    private var contactReports=false
    private var lastEventMs=0L
    private var suppressTapsUntil=0L
    val deadline get() = pending ?: press?.let { it.downTimeMs+longPressMs }
    val hasWork get() = press!=null || pending!=null || taps!=0

    init { require(doubleTapMs in 100..600 && longPressMs in 200..2_000) }

    fun accept(event: Event, receiptUptimeMs: Long) {
        // The identified pad emits per-contact NOTIFICATION markers, then delayed
        // Enter/Back classifications. Once contacts are available, summaries must
        // never add another tap/action, including after Disconnect changes the UI.
        if (contactReports && (event.key==Key.TAP || event.key==Key.DOUBLE)) return
        // An identical OEM report is not another physical pair, including after a callback.
        if (event.key==Key.DOUBLE && event.downTimeMs==lastDoubleDownMs &&
            event.timeMs<=lastDoubleUpMs && event.repeat==0) return
        if (event.timeMs<=0 || event.timeMs<lastEventMs || event.timeMs>receiptUptimeMs ||
            receiptUptimeMs-event.timeMs>MAX_EVENT_AGE_MS || event.downTimeMs<0 || event.downTimeMs>event.timeMs) {
            cancel();return
        }
        lastEventMs=event.timeMs
        if (event.phase==Phase.CANCEL || event.repeat!=0) { cancel();return }
        if (event.key==Key.CONTACT && !contactReports) { clear();contactReports=true }
        if ((event.key==Key.CONTACT || event.key==Key.TAP || event.key==Key.DOUBLE) && event.timeMs<suppressTapsUntil) {
            // Wait for an actual quiet boundary, rather than reusing a burst's fourth
            // or later contact as a new click on the disconnected settings/Connect UI.
            suppressTapsUntil=event.timeMs+doubleTapMs
            return
        }
        if (event.phase==Phase.DOWN) {
            if (press?.let { it.key==event.key && it.downTimeMs==event.downTimeMs }==true) return
            if (press!=null) {
                cancel(event.timeMs) // A new tap cannot complete an earlier missing UP.
                if (event.key!=Key.BACK && event.key!=Key.FORWARD && event.key!=Key.BACKWARD) return
            }
            confirm(event.timeMs)
            // A press within the continuation interval defers the old action until its UP.
            if (event.key==Key.FORWARD || event.key==Key.BACKWARD || event.key==Key.BACK) { taps=0;rawPairNeedsOem=false }
            pending=null
            press=Press(event.key,event.downTimeMs)
            return
        }
        val old=press ?: return
        if (old.key!=event.key || old.downTimeMs!=event.downTimeMs) { cancel();return }
        if (event.timeMs-old.downTimeMs>=longPressMs) { cancel();return }
        press=null
        when(event.key) {
            Key.CONTACT,Key.TAP -> {
                taps++;lastTapUpMs=event.timeMs
                rawPairNeedsOem=taps==2
                if (taps==3) {
                    clear();suppressTapsUntil=event.timeMs+doubleTapMs
                    disconnect()
                } else pending=event.timeMs+doubleTapMs
            }
            Key.DOUBLE -> {
                lastDoubleDownMs=event.downTimeMs;lastDoubleUpMs=event.timeMs
                if (taps==2 && rawPairNeedsOem) {
                    // Two raw taps followed by their OEM summary still represent only two taps.
                    rawPairNeedsOem=false;pending=lastTapUpMs+doubleTapMs
                } else if (taps<=1) {
                    // OEM-only reporting, or one raw single followed by the pair's OEM summary.
                    taps=2;rawPairNeedsOem=false;lastTapUpMs=event.timeMs
                    pending=event.timeMs+doubleTapMs
                } else {
                    // Two distinct OEM pairs are four taps, not the selected three-tap action.
                    clear();suppressTapsUntil=event.timeMs+doubleTapMs
                }
            }
            Key.FORWARD,Key.BACKWARD -> {
                clear();suppressTapsUntil=event.timeMs+doubleTapMs
                scroll(if(event.key==Key.FORWARD) 160 else -160)
            }
            Key.BACK -> { clear();suppressTapsUntil=event.timeMs+doubleTapMs;back() }
        }
    }
    fun confirm(nowUptimeMs: Long) {
        press?.let {
            if (nowUptimeMs>=it.downTimeMs+longPressMs) cancel(nowUptimeMs)
            return
        }
        val old=pending ?: return
        if(nowUptimeMs<old) return
        val count=taps
        clear() // Clear before the callback, which may pause or disconnect the Activity.
        when(count) { 1->single();2->back() }
    }
    private fun clear() { press=null;pending=null;taps=0;rawPairNeedsOem=false }
    fun cancel(nowUptimeMs: Long = lastEventMs) {
        // Do not reinterpret the remaining tail of an invalidated physical sequence as a fresh tap
        // after a pause, focus loss or geometry change.
        if(hasWork) suppressTapsUntil=maxOf(suppressTapsUntil,pending ?: 0L,nowUptimeMs+doubleTapMs)
        clear()
    }
    companion object { const val MAX_EVENT_AGE_MS=250L }
}
