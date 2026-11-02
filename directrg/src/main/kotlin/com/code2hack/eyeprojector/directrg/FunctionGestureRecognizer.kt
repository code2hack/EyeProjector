package com.code2hack.eyeprojector.directrg

/** OEM fallback: completed summaries describe the observed sequence, never additional presses. */
internal class FunctionGestureRecognizer(
    private val now: () -> Long,
    private val context: () -> Any?,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (Any) -> Unit,
) {
    enum class Report { DOWN, UP, CLICK, DOUBLE, TRIPLE, LONG }
    private var owner: Any? = null
    private var downAt: Long? = null
    private var shorts = 0
    private var terminalUntil = Long.MIN_VALUE
    private var cancelTimer: (() -> Unit)? = null
    private var revision = 0L
    fun accept(report: Report, at: Long = now()) {
        val current = context() ?: run { cancel(); return }
        if (owner != null && owner != current) cancel()
        if (owner == null && report != Report.DOWN && at < terminalUntil) return
        if (owner == null) owner = current
        when(report) {
            Report.DOWN -> if (downAt == null) downAt = at
            Report.UP -> {
                val down = downAt ?: return
                downAt = null
                if (at-down < 800) shorts++ else { cancel(); terminalUntil=at+500; return }
            }
            Report.LONG -> { cancel(); terminalUntil=at+500; return }
            Report.CLICK,Report.DOUBLE,Report.TRIPLE -> {
                downAt = null
                shorts = maxOf(shorts,when(report) { Report.DOUBLE -> 2; Report.TRIPLE -> 3; else -> 1 })
            }
        }
        cancelTimer?.invoke()
        val original = ++revision
        cancelTimer = schedule(at+500) {
            if (revision == original && downAt == null) {
                val captured = owner; val count = shorts
                cancel(); terminalUntil=now()+500
                if (captured != null && context() == captured) repeat(count) { dispatch(captured) }
            }
        }
    }
    fun cancel() { revision++; val old=cancelTimer;cancelTimer=null;owner=null;downAt=null;shorts=0;old?.invoke() }
}
