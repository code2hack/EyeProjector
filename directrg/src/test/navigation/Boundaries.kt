package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*

internal object Calls { val events = mutableListOf<String>() }
internal class Node {
    var visibility = View.GONE
    var scrollY = 42
    fun scrollTo(x: Int, y: Int) { scrollY = y }
    fun removeCallbacks(task: Runnable) = Unit
    fun cancel() { Calls.events.add("cancel") }
    fun cancel(time: Long) = cancel()
    fun touch(event: HeldPointerDispatcher.Event) = true
    fun scroll(position: PointerPosition, pixels: Int) = true
    fun cancelTouch() { Calls.events.add("cancel-touch") }
    fun stop() { Calls.events.add("stop-input") }
    fun clear() = Unit
    var submitted = true
    var requests = 0
    fun sleep(): Boolean { requests++; Calls.events.add("sleep-request"); return submitted }
}
internal data class PointerPosition(val x: Float, val y: Float, val available: Boolean)
internal class HeldPointerDispatcher {
    data class Event(val phase: Touch.Phase, val contactId: Long, val timeMs: Long, val position: PointerPosition)
}
internal object View { const val VISIBLE = 0; const val GONE = 8 }
internal object Log { fun w(tag: String, message: String) { Calls.events.add("warning") } }
internal object SystemClock { fun uptimeMillis() = 100L }
internal class Intent(val action: String) {
    val categories = mutableSetOf<String>()
    var flags = 0
    fun addCategory(value: String) = apply { categories.add(value) }
    fun addFlags(value: Int) = apply { flags = flags or value }
    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_HOME = "android.intent.category.HOME"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
internal data class ReceiverLaunchScope(val owner: String, val token: String, val task: Int)
internal class Session {
    val decoder = Any()
    val navigations = mutableListOf<Navigation>()
    var closed = false
    fun cancelPendingInput() { Calls.events.add("session-cancel") }
    fun navigate(action: Navigation) { navigations.add(action); Calls.events.add("navigate") }
}
internal class Owner {
    var revision = 0L
    val canceled = mutableListOf<Pair<ReceiverLaunchScope, String?>>()
    fun advanceInputRevision() = ++revision
    fun disconnect(scope: ReceiverLaunchScope, activationId: String?) {
        canceled.add(scope to activationId); Calls.events.add("owner-cancel")
    }
}
internal object ReceiverSessionService {
    fun cancelIntent(context: Any, scope: ReceiverLaunchScope, activation: String?) = scope to activation
}
