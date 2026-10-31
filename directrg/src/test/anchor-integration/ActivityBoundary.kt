package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.util.Log
import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Named Android widgets/service boundaries; the runner inserts complete current Activity blocks. */
internal class HeldActivityWiring(val feature: ReceiverActivationStore, val filesDir: File) : HeldLifecycleBoundary() {
    var owner: ReceiverSessionOwner? = null
    var session: ProjectionSession? = null
    var currentGeometry: Geometry? = Geometry(480, 640)
    var inputGeneration = 0L
    var streaming = true
    var resumed = true
    var focused = true
    val presentation = SessionPresentation().apply { streaming() }
    lateinit var root: HeldNode
    lateinit var pointer: HeldNode
    val display = HeldNode()
    val mainControls = HeldNode()
    var pendingExit: Pair<ReceiverLaunchScope, String?>? = null
    var launcherExits = 0
    var finishCount = 0
    var shellSleeps = 0
    val connectionView = HeldNode().apply { visibility = View.GONE }
    val connectButton = HeldNode()
    lateinit var anchorOverlay: HeldNode
    lateinit var anchorRegistration: AnchorRegistrationController<ProjectionSession>
    var anchorOffer: AnchorMessage.Offer? = null
    var serviceBinder: HeldFeatureBinding? = HeldFeatureBinding(feature)
    // ACTUAL_HELD_FIELDS
    // ACTUAL_SESSION_VIEW
    lateinit var hidInput: HeldNode
    lateinit var functionInput: HeldNode
    lateinit var classifiedPadLongPress: HeldNode
    lateinit var shellPadContact: HeldNode
    lateinit var gestures: HeldNode
    lateinit var heldOwner: HeldNode
    lateinit var heldInput: HeldNode
    lateinit var padContact: HeldNode
    lateinit var screen: HeldNode
    lateinit var localInput: HeldNode
    lateinit var completedInput: CompletedReceiverInput
    lateinit var hidPanel: HeldNode
    val hidDevices = HeldNode()
    lateinit var longTouch: HeldNode
    val pairingPanel = HeldNode()
    val edgeMotion = HeldNode()
    val confirmTap = Runnable {}
    val edgeTick = Runnable {}
    var edgeScheduled = false
    var appNavigation = 0
    var connectRequested = false
    var visibleActivation: String? = null
    val registeredActivations = mutableSetOf<String>()
    val cameraPermissionHost = Any()
    val cameraPermissionRequests = mutableMapOf<Int, Any>()
    var bound = false
    var bindingValid = true
    var isFinishing = false
    val serviceConnection = Any()
    val taskId = 7
    var launchScope = ReceiverLaunchScope("hold-scope", "hold-trigger", taskId)
    private var clock = 0L
    private var scheduled: Pair<Long, () -> Unit>? = null
    val produced = mutableListOf<FunctionIntentKind>()
    private val grammar = FunctionGestureRecognizer({ clock }, ::functionContext, { deadline, action ->
        val task = deadline to action
        scheduled = task
        val cancel: () -> Unit = { if (scheduled === task) scheduled = null }
        cancel
    }, { gesture ->
        produced.add(gesture.kind)
        dispatchFunction(FunctionSource.GLASSES_NATIVE, gesture)
    }, capture = ::captureFunction)
    init {
        root = HeldNode(); pointer = HeldNode(); anchorOverlay = HeldNode(); hidInput = HeldNode(); functionInput = HeldNode(); longTouch = HeldNode()
        classifiedPadLongPress = HeldNode(); shellPadContact = HeldNode(); gestures = HeldNode(); heldOwner = HeldNode()
        heldInput = HeldNode(); padContact = HeldNode(); screen = HeldNode(); localInput = HeldNode(); hidPanel = HeldNode()
        // ACTUAL_ANCHOR_INITIALIZER
        // ACTUAL_COMPLETED_INITIALIZER
        // ACTUAL_HELD_INITIALIZER
    }
    private fun hasWindowFocus() = focused
    private fun nativeInputStage(stage: String) = Unit
    private fun registerTask(scope: ReceiverLaunchScope, activation: String?) = Unit
    private fun showStatus(text: String) = Unit
    private fun getString(id: Int) = id.toString()
    private fun unbindService(connection: Any) = Unit
    private fun attachPresentation() { owner?.presentation(sessionView, usable(), localInputVisible()) }
    internal val projectionContext get() = session != null && !presentation.panelVisible
    private fun finish() { finishCount++ }
    private fun startActivity(intent: Intent) { launcherExits++ }
    private fun startForegroundService(intent: Any) = Unit
    fun settings() = showHidBindings()
    fun home() = contextualHome()
    fun lock() = dispatchNavigation(ContextualNavigationAction.LOCK)
    fun tap(x: Float, y: Float) = remoteTap(x, y)
    fun scroll(x: Float, y: Float) = remoteScroll(x, y, 120)
    fun layout(width: Int, height: Int) { root.width = width; root.height = height; resize() }
    fun publish() = publishHeadPointer()
    fun choose(action: AnchorRegistrationAction) = chooseAnchorRegistration(action)
    fun back() = contextualBack()
    fun retire() = cancelInput()
    fun active() = registrationActive()
    fun snapshot(value: ReceiverSessionOwner.Snapshot) = showSnapshot(value)
    fun move(x: Float, y: Float) { pointer.position = PointerPosition(x, y, true); publishHeadPointer() }
    fun short(at: Long) {
        clock = at; grammar.accept(FunctionGestureRecognizer.Report.DOWN, clock)
        clock = at + 40; grammar.accept(FunctionGestureRecognizer.Report.UP, clock)
        grammar.accept(FunctionGestureRecognizer.Report.CLICK, at + 50)
        clock = at + 550
        while (scheduled?.first?.let { it <= clock } == true) {
            val task = scheduled!!; scheduled = null; task.second()
        }
    }
    fun register(at: Long) {
        clock = at; grammar.accept(FunctionGestureRecognizer.Report.DOWN, clock)
        clock = at + 800; grammar.accept(FunctionGestureRecognizer.Report.LONG, clock)
    }
    fun releaseRegister(at: Long) {
        clock = at + 810; grammar.accept(FunctionGestureRecognizer.Report.UP, clock)
        grammar.accept(FunctionGestureRecognizer.Report.CLICK, at + 820)
    }
    val heldVisible get() = anchorHeldLabel.visibility == View.VISIBLE
    val label get() = anchorHeldLabel
    val view: ReceiverSessionOwner.View get() = sessionView
    // ACTUAL_PREDICATES
    // ACTUAL_METHODS
}
internal open class HeldLifecycleBoundary {
    open fun onWindowFocusChanged(hasFocus: Boolean) = Unit
    open fun onPause() = Unit
    open fun onDestroy() = Unit
}
internal class HeldFeatureBinding(val feature: ReceiverActivationStore) {
    fun detachCameraPermissionHost(host: Any) = Unit
}
internal object View { const val VISIBLE = 0; const val GONE = 8 }
internal object Color { const val YELLOW = -256 }
internal object Gravity { const val TOP = 48; const val CENTER_HORIZONTAL = 1; const val CENTER = 17 }
internal object FrameLayout { class LayoutParams(var width: Int, var height: Int, val gravity: Int = 0) }
internal object R { object string { const val disconnect = 1; const val connect = 2; const val connected = 3; const val connecting = 4; const val disconnected = 5 } }
internal object Toast {
    val messages = mutableListOf<String>()
    const val LENGTH_SHORT = 0
    fun makeText(context: Any, text: String, length: Int): Toast { messages.add(text); return this }
    fun show() = Unit
}
internal open class HeldNode {
    var width = 480; var height = 640; var rotation = 0
    var visibility = View.GONE
    var position = PointerPosition(320f, 420f, true)
    var layoutParams = FrameLayout.LayoutParams(480, 640)
    val children = mutableListOf<HeldNode>()
    var text = ""
    val expiresAtNs get() = System.nanoTime() + 10_000_000_000L
    fun addView(child: HeldNode, params: FrameLayout.LayoutParams) { child.layoutParams = params; children.add(child) }
    fun inputPosition() = position
    fun removeCallbacks(task: Runnable) = Unit
    fun cancel() = Unit
    fun cancel(time: Long) = Unit
    fun cancelTouch() = Unit
    fun clear() = Unit
    var sleeps = 0
    fun sleep(): Boolean { sleeps++; return true }
    fun scrollTo(x: Int, y: Int) = Unit
    fun refresh() = Unit
    fun start() = Unit
    fun stop() = Unit
    fun close() = Unit
    fun bounds(left: Float, top: Float, right: Float, bottom: Float, rotation: Int) = Unit
    fun show(highlight: AnchorRegistrationHighlight) { visibility = View.VISIBLE }
    fun waiting() = Unit
    fun dismiss() { visibility = View.GONE }
    var touches = 0
    var onTouch: ((HeldPointerDispatcher.Event) -> Unit)? = null
    fun touch(event: HeldPointerDispatcher.Event): Boolean { touches++; onTouch?.invoke(event); return true }
    fun scroll(point: PointerPosition, pixels: Int): Boolean { touches++; return true }
}
internal class TextView(context: Any) : HeldNode() {
    var textSize = 0f
    var isClickable = true
    var isFocusable = true
    fun setTextColor(color: Int) = Unit
    fun setBackgroundColor(color: Int) = Unit
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) = Unit
}

/** Android activity/power/service invocation boundaries; production navigation bodies are inserted. */
internal class Intent(val action: String) {
    fun addCategory(value: String) = this
    fun addFlags(value: Int) = this
    companion object { const val ACTION_MAIN = "main"; const val CATEGORY_HOME = "home"; const val FLAG_ACTIVITY_NEW_TASK = 1 }
}
internal object ReceiverSessionService {
    fun cancelIntent(context: Any, scope: ReceiverLaunchScope, activation: String?) = Any()
}
