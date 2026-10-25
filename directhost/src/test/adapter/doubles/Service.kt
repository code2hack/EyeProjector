package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

class ShizukuHostService
interface IHostBootstrap {
    fun phoneHidSnapshot(expectedOwnerEpoch:String,expectedProjectionToken:String):com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor?=null
    fun phoneHidStampedEdge(routeId:Long,typedEdge:ByteArray,expectedOwnerEpoch:String,expectedProjectionToken:String,regionId:String,word:Long) {}

    fun phoneHidEdge(routeId: Long, typedEdge: ByteArray, expectedOwnerEpoch: String, expectedProjectionToken: String) {}
    fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String
    fun status(): String
    fun stop(): String
    fun destroy()
    fun requestScale(requestId: Long, scale: Double, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun requestPanelOff(requestId: Long, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun panelState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun launchApp(requestId: Long, packageName: String, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun projectionDisplayState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun asBinder(): android.os.IBinder
    object Stub { fun asInterface(binder: android.os.IBinder): IHostBootstrap = binder as IHostBootstrap }
}

/** The first stop may time out; the test later releases construction independently. */
class FakeService : IHostBootstrap, android.os.IBinder {
    data class InputCall(val routeId: Long, val edge: PhoneHidEdge, val expected: ProjectionContext)
    val inputCalls = mutableListOf<InputCall>()
    val inputWrites = mutableListOf<InputCall>()
    var beforeInput: (() -> Unit)? = null
    var snapshotProvider: (() -> com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor?)? = null
    var stampedInput: ((Long, PhoneHidEdge, String, Long) -> Unit)? = null
    override fun phoneHidSnapshot(expectedOwnerEpoch:String,expectedProjectionToken:String):com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor? =
        if (ProjectionContext(expectedOwnerEpoch,expectedProjectionToken)==context) snapshotProvider?.invoke() else null
    override fun phoneHidStampedEdge(routeId:Long,typedEdge:ByteArray,expectedOwnerEpoch:String,expectedProjectionToken:String,regionId:String,word:Long) {
        beforeInput?.invoke()
        val call=InputCall(routeId,PhoneHidEdge.from(typedEdge),ProjectionContext(expectedOwnerEpoch,expectedProjectionToken))
        inputCalls.add(call)
        if (call.expected==context) { stampedInput?.invoke(routeId,call.edge,regionId,word); inputWrites.add(call) }
    }
    override fun phoneHidEdge(routeId: Long, typedEdge: ByteArray, expectedOwnerEpoch: String, expectedProjectionToken: String) {
        beforeInput?.invoke()
        val call = InputCall(routeId, PhoneHidEdge.from(typedEdge), ProjectionContext(expectedOwnerEpoch, expectedProjectionToken))
        inputCalls.add(call)
        if (alive && state == "STREAMING" && call.expected == context) inputWrites.add(call)
    }
    @Volatile var state = "READY"
    var revoked = false
    var stopReady = true
    var exitOnDestroy = true
    var stops = 0
    var destroys = 0
    var starts = 0
    @Volatile var sessionEpoch = ""
    var statusReads = 0
    var statusGate:java.util.concurrent.CountDownLatch?=null
    var statusEntered=java.util.concurrent.CountDownLatch(1)
    var stopGate:java.util.concurrent.CountDownLatch?=null
    val scaleRequests = mutableListOf<Pair<Long, Double>>()
    var scaleReads = 0
    var scalePayloadOverride: ByteArray? = null
    var currentScale = ScaleState(0, 1.0, Geometry(480, 640), null, ScaleStatus.READY, false)
    var display = ProjectionDisplayState(generation = "fixture:child", displayId = 47, status = ProjectionLaunchStatus.READY,
        context = ProjectionContext("", "fixture-token"))
    val context get() = display.context.copy(ownerEpoch = sessionEpoch)
    var displayReads = 0
    var displayPayloadOverride: ByteArray? = null
    var beforeLaunch: (() -> Unit)? = null
    val launches = mutableListOf<String>()
    val panelRequests = java.util.concurrent.CopyOnWriteArrayList<Long>()
    val panelContexts = java.util.concurrent.CopyOnWriteArrayList<PanelContext>()
    val panelWrites = java.util.concurrent.CopyOnWriteArrayList<PanelContext>()
    var panelReads = 0
    var panelQueryGate: java.util.concurrent.CountDownLatch? = null
    val queryEntered = java.util.concurrent.CountDownLatch(1)
    var panelPayloadOverride: ByteArray? = null
    @Volatile private var panelValue = PanelPowerState()
    var currentPanel: PanelPowerState
        get() = panelValue.copy(context = PanelContext(context.ownerEpoch, context.projectionToken))
        set(value) {
            panelValue = value
            display = display.copy(context = ProjectionContext(value.context.ownerEpoch, value.context.projectionToken))
        }
    var panelGate: java.util.concurrent.CountDownLatch? = null
    val panelEntered = java.util.concurrent.CountDownLatch(1)
    val stopEntered = java.util.concurrent.CountDownLatch(1)
    @Volatile private var alive = true
    private val deaths = mutableListOf<() -> Unit>()
    override val isBinderAlive: Boolean get() = alive
    override fun linkToDeath(recipient: () -> Unit, flags: Int) { deaths.add(recipient) }
    override fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String {
        starts++; this.sessionEpoch = sessionEpoch
        replaceProjection(sessionEpoch)
        return status()
    }
    override fun status(): String {
        check(alive)
        statusReads++
        val captured="{\"state\":\"$state\",\"sessionRevoked\":$revoked,\"sessionEpoch\":\"$sessionEpoch\",\"projectionToken\":\"${if (state == "STREAMING") currentPanel.context.projectionToken else ""}\"}"
        statusEntered.countDown();statusGate?.let{check(it.await(4,java.util.concurrent.TimeUnit.SECONDS))}
        return captured
    }
    override fun stop(): String {
        stops++; stopEntered.countDown();stopGate?.let{check(it.await(4,java.util.concurrent.TimeUnit.SECONDS))}
        state = if (stopReady) "STOPPED" else "STOP_UNVERIFIED"
        revoked = stopReady
        if (stopReady) currentScale = currentScale.copy(observed = null, target = null, status = ScaleStatus.UNAVAILABLE)
        return status()
    }
    override fun destroy() { destroys++; if (exitOnDestroy) die() }
    override fun requestScale(requestId: Long, scale: Double, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        check(alive)
        if (PanelContext(expectedOwnerEpoch, expectedProjectionToken) != currentPanel.context || state != "STREAMING")
            return ScaleState(requestId, scale, null, null, ScaleStatus.UNAVAILABLE, false).bytes()
        scaleRequests.add(requestId to scale)
        currentScale = currentScale.copy(requestId = requestId, requested = scale,
            target = LayoutScale.target(scale), status = ScaleStatus.PENDING)
        return scalePayloadOverride ?: currentScale.bytes()
    }
    override fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        check(alive); scaleReads++
        if (PanelContext(expectedOwnerEpoch, expectedProjectionToken) != currentPanel.context || state != "STREAMING")
            return ScaleState(0, 1.0, null, null, ScaleStatus.UNAVAILABLE, false).bytes()
        return scalePayloadOverride ?: currentScale.bytes()
    }
    override fun requestPanelOff(requestId: Long, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        check(alive)
        panelRequests.add(requestId)
        val context = PanelContext(expectedOwnerEpoch, expectedProjectionToken)
        panelContexts.add(context)
        panelEntered.countDown(); panelGate?.await()
        if (context != currentPanel.context || state != "STREAMING") return PanelPowerState.unavailable(requestId, context).bytes()
        panelWrites.add(context)
        currentPanel = currentPanel.copy(requestId = requestId, off = PanelSubmission.SUBMITTED,
            restoration = PanelRestoration.PENDING, sessionActive = true)
        return panelPayloadOverride ?: currentPanel.bytes()
    }
    override fun panelState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        check(alive); panelReads++; queryEntered.countDown(); panelQueryGate?.await()
        val context = PanelContext(expectedOwnerEpoch, expectedProjectionToken)
        return panelPayloadOverride ?: if (context == currentPanel.context) currentPanel.bytes()
        else PanelPowerState.unavailable(context = context).bytes()
    }
    override fun launchApp(requestId: Long, packageName: String, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        check(alive); beforeLaunch?.invoke(); check(alive)
        val expected = ProjectionContext(expectedOwnerEpoch, expectedProjectionToken)
        if (expected != context) return ProjectionDisplayState(requestId = requestId, requestedPackage = packageName, context = expected).bytes()
        launches.add(packageName)
        display = display.copy(requestId = requestId, requestedPackage = packageName, status = ProjectionLaunchStatus.STARTED,
            commandExit = 0, launchedComponent = "$packageName/.Main", foregroundPackage = packageName, context = context)
        return displayPayloadOverride ?: display.bytes()
    }
    override fun projectionDisplayState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        check(alive)
        val expected = ProjectionContext(expectedOwnerEpoch, expectedProjectionToken)
        if (expected != context) return ProjectionDisplayState(context = expected).bytes()
        displayReads++
        return displayPayloadOverride ?: display.copy(context = context).bytes()
    }
    fun replaceProjection(ownerEpoch: String = sessionEpoch) {
        sessionEpoch = ownerEpoch
        currentPanel = PanelPowerState(context = PanelContext(ownerEpoch, java.util.UUID.randomUUID().toString()), sessionActive = state == "STREAMING")
    }
    fun die() { alive = false; deaths.toList().forEach { it() } }
    override fun asBinder() = this
}
