package com.code2hack.eyeprojector.directrg
import java.io.File

internal enum class Navigation { HOME, BACK }
internal class Boundary {
    var visibility = 0
    var text = ""
    var calls = 0
    val surface = Any()
    fun refresh() { calls++ }
    fun start() { calls++ }
    fun stop() { calls++ }
    fun cancel() { calls++ }
    fun cancel(at: Long) { calls++ }
    fun clear() { calls++ }
    fun close() { calls++ }
    fun cancelTouch() { calls++ }
    fun removeCallbacks(task: Runnable) { calls++ }
    fun scrollTo(x: Int, y: Int) { scrollY = y; calls++ }
    var scrollY = 120
    fun clearFlags(flags: Int) { calls++ }
}
internal class Session {
    val navigations = mutableListOf<Navigation>()
    val decoder = Any()
    var closed = false
    var canceled = 0
    fun navigate(value: Navigation) { navigations.add(value) }
    fun cancelPendingInput() { canceled++ }
    fun cancelHeadScroll() { canceled++ }
    fun close() { closed = true }
}
internal class ReceiverSessionOwner(private val stopped: () -> Unit) {
    var session: Session? = null
    var closed = false
    var detached = 0
    var attached = 0
    private var revision = 0L
    fun advanceInputRevision() = ++revision
    fun attach() { attached++ }
    fun detach(view: Any) { detached++ }
    fun disconnect(notifyPeer: Boolean = true) { session?.close(); session = null; stopped() }
    fun close() { closed = true; disconnect() }
}
internal class ReceiverSessionService
internal object R { object string { const val connect = 1; const val disconnected = 2 } }
