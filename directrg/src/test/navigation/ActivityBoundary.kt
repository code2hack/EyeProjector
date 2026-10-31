package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*

/** UI/framework/session boundaries only; runner inserts the complete actual Activity methods. */
internal class ActivityNavigationWiring {
    var session: Session? = Session()
    var owner: Owner? = Owner()
    val presentation = SessionPresentation().apply { streaming() }
    val launchScope = ReceiverLaunchScope("original-owner", "original-token", 47)
    val visibleActivation: String? = "original-activation"
    var pendingExit: Pair<ReceiverLaunchScope, String?>? = null
    var bindingValid = true
    var resumed = true
    var focus = true
    var streaming = true
    var inputGeneration = 0L
    var edgeScheduled = false
    var finished = false
    var launch: Intent? = null
    var serviceCancel: Pair<ReceiverLaunchScope, String?>? = null
    lateinit var hidPanel: Node
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
    val pointer = Node()
    val hidDevices = Node()
    val longTouch = Node()
    val mainControls = Node()
    val connectionView = Node()
    val root = Node()
    val edgeMotion = Node()
    val confirmTap = Runnable { }
    val edgeTick = Runnable { }
    init {
        hidPanel = Node(); hidInput = Node(); functionInput = Node(); classifiedPadLongPress = Node()
        shellPadContact = Node(); gestures = Node(); heldOwner = Node(); heldInput = Node()
        padContact = Node(); screen = Node(); localInput = Node()
        // ACTUAL_COMPLETED_INITIALIZER
    }
    // ACTUAL_CONTEXT_GETTER
    private fun usable() = resumed && focus
    private fun projectedInput() = streaming && presentation.projectedInput
    private fun publishHeadPointer() = Unit
    private fun nativeInputStage(stage: String) = Unit
    private fun resize() { cancelInput() }
    private fun finish() { finished = true }
    private fun startActivity(intent: Intent) { launch = intent; Calls.events.add("launcher") }
    private fun startForegroundService(intent: Pair<ReceiverLaunchScope, String?>) { serviceCancel = intent }
    fun receive(event: ReceiverInput) = completedInput.accept(event)
    fun completed(kind: ReceiverInputKind) = receive(ReceiverInput(kind, inputGeneration))
    // ACTUAL_METHODS
}
