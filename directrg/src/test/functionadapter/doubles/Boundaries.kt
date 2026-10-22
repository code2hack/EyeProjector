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
internal class Epoch(val active: Boolean = true)
internal class Peer(val epoch: String = "epoch", val phase: Epoch = Epoch())
internal class Attachment { val epoch: String? = "epoch"; fun release(): String? = epoch; fun suppress(epoch: String) {} }
internal class PairedSessionClient(
    directory: File, val observer: (Peer) -> Unit, val failed: () -> Unit,
    val deliver: (() -> Unit) -> Unit,
) {
    var closed = false
    var closeEpoch: String? = null
    fun start() {}
    fun close(epoch: String? = null) { closed = true; closeEpoch = epoch }
    fun disconnect(epoch: String?): List<String> = listOfNotNull(epoch)
}
internal object R { object string { const val connect = 1; const val disconnected = 2 } }
