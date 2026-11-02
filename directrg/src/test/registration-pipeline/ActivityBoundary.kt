package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import com.code2hack.eyeprojector.protocol.*

/** UI widgets/gesture sources only. The runner inserts complete actual Activity methods. */
internal class RegistrationActivityWiring(val feature: ReceiverActivationStore) {
    var owner: ReceiverSessionOwner? = null
    var session: ProjectionSession? = null
    var currentGeometry: Geometry? = Geometry(480, 640)
    var inputGeneration = 0L
    var streaming = true
    var resumed = true
    val presentation = SessionPresentation().apply { streaming() }
    val root = Node()
    lateinit var pointer: Node
    val display = Node()
    val connectionView = Node().apply { visibility = View.GONE }
    lateinit var anchorOverlay: Node
    lateinit var anchorRegistration: AnchorRegistrationController<ProjectionSession>
    var anchorOffer: AnchorMessage.Offer? = null
    val serviceBinder = FeatureBinding(feature)
    val sessionView = object : ReceiverSessionOwner.View {
        override fun state(snapshot: ReceiverSessionOwner.Snapshot) = Unit
        override fun input(source: ProjectionSession, event: ReceiverInput) {
            if (session === source && usable()) completedInput.accept(event)
        }
        override fun anchor(source: ProjectionSession, message: AnchorMessage): Boolean = anchorMessage(source, message)
    }
    lateinit var hidInput: Node
    lateinit var functionInput: Node
    lateinit var classifiedPadLongPress: Node
    lateinit var shellPadContact: Node
    lateinit var gestures: Node
    lateinit var heldOwner: Node
    lateinit var heldInput: Node
    lateinit var padContact: Node
    lateinit var screen: Node
    lateinit var localInput: Node
    lateinit var completedInput: CompletedReceiverInput
    lateinit var hidPanel: Node
    val edgeMotion = Node()
    val confirmTap = Runnable {}
    val edgeTick = Runnable {}
    var edgeScheduled = false
    var appNavigation = 0
    init {
        pointer = Node(); anchorOverlay = Node(); hidInput = Node(); functionInput = Node(); classifiedPadLongPress = Node()
        shellPadContact = Node(); gestures = Node(); heldOwner = Node(); heldInput = Node()
        padContact = Node(); screen = Node(); localInput = Node(); hidPanel = Node()
        // ACTUAL_ANCHOR_INITIALIZER
        // ACTUAL_COMPLETED_INITIALIZER
    }
    private fun usable() = resumed
    private fun nativeInputStage(stage: String) = Unit
    private fun completedNavigation(kind: ReceiverInputKind) {
        if (kind == ReceiverInputKind.BACK) contextualBack() else cancelInput()
    }
    private fun showControlPanel() { appNavigation++ }
    private fun finishProjection() { appNavigation++ }
    private fun resize() = cancelInput()
    private val projectionContext get() = session != null && !presentation.panelVisible
    fun publish() = publishHeadPointer()
    fun captureContext() = functionContext()
    fun choose(action: AnchorRegistrationAction) = chooseAnchorRegistration(action)
    fun back() = contextualBack()
    fun retire() = cancelInput()
    fun active() = registrationActive()
    fun touchThrough() = remoteTap(320f, 420f)
    fun scrollThrough() = remoteScroll(320f, 420f, 100)
    // ACTUAL_PREDICATES
    // ACTUAL_METHODS
}
internal class FeatureBinding(val feature: ReceiverActivationStore)
internal object View { const val VISIBLE = 0; const val GONE = 8 }
internal object Toast {
    val messages = mutableListOf<String>()
    const val LENGTH_SHORT = 0
    fun makeText(context: Any, text: String, length: Int): Toast { messages.add(text); return this }
    fun show() = Unit
}
internal class Node {
    var width = 480; var height = 640; var rotation = 0
    var visibility = View.GONE
    val position = PointerPosition(320f, 420f, true)
    val expiresAtNs get() = System.nanoTime() + 10_000_000_000L
    fun removeCallbacks(task: Runnable) = Unit
    fun cancel() = Unit
    fun cancel(time: Long) = Unit
    fun cancelTouch() = Unit
    fun clear() = Unit
    fun stop() = Unit
    fun bounds(left: Float, top: Float, right: Float, bottom: Float, rotation: Int) = Unit
    fun show(highlight: AnchorRegistrationHighlight) { visibility = View.VISIBLE }
    fun waiting() = Unit
    fun dismiss() { visibility = View.GONE }
    var touches = 0
    var onTouch: ((HeldPointerDispatcher.Event) -> Unit)? = null
    fun touch(event: HeldPointerDispatcher.Event): Boolean { touches++; onTouch?.invoke(event); return true }
    fun scroll(point: PointerPosition, pixels: Int): Boolean { touches++; return true }
}
