package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*

internal object Calls { val events = mutableListOf<String>() }
internal class Node {
    val holder = Holder()
    var width = 1000
    var height = 800
    var rotation = 0
    var visibility = View.GONE
    var scrollY = 42
    fun scrollTo(x: Int, y: Int) { scrollY = y }
    fun removeCallbacks(task: Runnable) = Unit
    fun cancel() { Calls.events.add("cancel") }
    fun cancel(time: Long) = cancel()
    fun touch(event: HeldPointerDispatcher.Event): Boolean { Calls.events.add("local-${event.phase}"); return true }
    fun scroll(position: PointerPosition, pixels: Int): Boolean { Calls.events.add("local-scroll"); return true }
    fun waiting() = Unit
    fun show(highlight: AnchorRegistrationHighlight) { visibility = View.VISIBLE }
    fun dismiss() { visibility = View.GONE }
    var onBounds: (() -> Unit)? = null
    fun bounds(left: Float, top: Float, right: Float, bottom: Float, rotation: Int) { onBounds?.invoke() }
    fun cancelTouch() { Calls.events.add("cancel-touch") }
    fun stop() { Calls.events.add("stop-input") }
    fun clear() = Unit
    var submitted = true
    var requests = 0
    fun sleep(): Boolean { requests++; Calls.events.add("sleep-request"); return submitted }
}
internal data class PointerPosition(val x: Float, val y: Float, val available: Boolean)
internal class Holder { val surface = Surface() }
internal class Surface { val isValid = true }
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
    var taps = 0
    var scrolls = 0
    fun tap(target: Tap, time: Long): Boolean { taps++; return true }
    fun scroll(target: Scroll, time: Long, headMotion: Boolean): Boolean { scrolls++; return true }
    var fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 0, ReceiverUsabilityMode.PROJECTED)
    val choices = mutableListOf<AnchorChoice>()
    fun captureFunctionContext(point: HeadPointerState): FunctionIntentContext? =
        FunctionIntentContext(fact, point).takeIf { point.revision == fact.inputRevision }
    fun chooseAnchorRegistration(offer: AnchorMessage.Offer, choice: AnchorChoice): Boolean {
        choices.add(choice); Calls.events.add("anchor-$choice"); return true
    }
    fun cancelPendingInput() { Calls.events.add("session-cancel") }
    fun navigate(action: Navigation) { navigations.add(action); Calls.events.add("navigate") }
}
internal typealias ProjectionSession = Session
internal class Owner {
    var demand: ReceiverDemand? = null
    fun currentDemand() = demand
    class Snapshot(val state: String = "STREAMING")
    fun snapshot() = Snapshot()
    var onPresentation: (() -> Unit)? = null
    fun presentation(view: Any, usable: Boolean, panel: Boolean) { onPresentation?.invoke(); Calls.events.add("presentation") }
    fun attachSurface(view: Any, surface: Surface?) = Unit
    var revision = 0L
    val canceled = mutableListOf<Pair<ReceiverLaunchScope, String?>>()
    fun advanceInputRevision() = ++revision
    var openAccepted = true
    var onOpen: (() -> Unit)? = null
    fun openAnchorRegistration(source: ProjectionSession, offer: AnchorMessage.Offer, chooserRevision: Long): Boolean {
        onOpen?.invoke()
        Calls.events.add("OPEN")
        if (openAccepted) source.fact = offer.intent.context.usability.copy(inputRevision = chooserRevision, mode = ReceiverUsabilityMode.LOCAL_PANEL)
        return openAccepted
    }
    fun disconnect(scope: ReceiverLaunchScope, activationId: String?) {
        canceled.add(scope to activationId); Calls.events.add("owner-cancel")
    }
}
internal class Binder { val feature = Feature() }
internal class Feature { val incarnation = ReceiverIncarnation("receiver", 1) }
internal class Toast {
    fun show() = Unit
    companion object {
        const val LENGTH_SHORT = 0
        fun makeText(context: Any, text: String, duration: Int): Toast { Calls.events.add("toast:$text"); return Toast() }
    }
}
internal object ReceiverSessionService {
    fun cancelIntent(context: Any, scope: ReceiverLaunchScope, activation: String?) = scope to activation
}
