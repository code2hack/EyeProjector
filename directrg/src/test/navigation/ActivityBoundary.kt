package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*

/** UI/framework/session boundaries only; runner inserts the complete actual Activity methods. */
internal class ActivityNavigationWiring {
    var session: Session? = Session()
    var currentGeometry: Geometry? = Geometry(500, 400)
    var owner: Owner? = Owner()
    val presentation = SessionPresentation().apply { streaming() }
    val launchScope = ReceiverLaunchScope("original-owner", "original-token", 47)
    val visibleActivation: String? = "original-activation"
    var pendingExit: Pair<ReceiverLaunchScope, String?>? = null
    var appliedDemand: ReceiverDemand? = null
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
    val display = Node()
    lateinit var anchorOverlay: Node
    lateinit var anchorRegistration: AnchorRegistrationController<ProjectionSession>
    var anchorOffer: AnchorMessage.Offer? = null
    val choices get() = session!!.choices.map { AnchorRegistrationAction.valueOf(it.name) }
    val serviceBinder = Binder()
    val sessionView = Any()
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
        anchorOverlay = Node()
        // ACTUAL_ANCHOR_INITIALIZER
        // ACTUAL_COMPLETED_INITIALIZER
    }
    // ACTUAL_CONTEXT_GETTER
    private fun usable() = resumed && focus
    // ACTUAL_PREDICATES
    private fun publishHeadPointer() { Calls.events.add("publish") }
    private fun nativeInputStage(stage: String) = Unit
    private fun resize() { cancelInput() }
    private fun attachPresentation() = Unit
    private fun finish() { finished = true }
    private fun startActivity(intent: Intent) { launch = intent; Calls.events.add("launcher") }
    private fun startForegroundService(intent: Pair<ReceiverLaunchScope, String?>) { serviceCancel = intent }
    fun receive(event: ReceiverInput) = completedInput.accept(event)
    fun completed(kind: ReceiverInputKind) = receive(ReceiverInput(kind, inputGeneration))
    fun retireInput() = cancelInput()
    fun directTap() = remoteTap(200f, 100f)
    fun directScroll() = remoteScroll(200f, 100f, 100)
    fun choose(action: AnchorRegistrationAction) = chooseAnchorRegistration(action)
    fun canCaptureFunction() = functionContext() != null
    fun demand(demand: ReceiverDemand) { owner!!.demand = demand; applyReceiverDemand(demand) }
    fun message(source: Session, message: AnchorMessage) = anchorMessage(source, message)
    fun showChooser() {
        val original = FunctionIntentContext(
            ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, inputGeneration, ReceiverUsabilityMode.PROJECTED),
            HeadPointerState(inputGeneration, 1, true, true, 200f, 100f, Fit.inside(root.width, root.height, currentGeometry!!), currentGeometry))
        session!!.fact = original.usability
        anchorMessage(session!!, AnchorMessage.Offer(1,
            FunctionIntent(FunctionIntentKind.REGISTER, FunctionSource.GLASSES_NATIVE, 1, 1, original),
            AnchorHighlight(10.0, 20.0, 70.0, 60.0)))
    }
    // ACTUAL_METHODS
}
