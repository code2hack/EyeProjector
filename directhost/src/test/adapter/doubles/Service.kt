package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

class ShizukuHostService
interface IHostBootstrap {
    fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String
    fun status(): String
    fun stop(): String
    fun destroy()
    fun requestScale(requestId: Long, scale: Double, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun requestPanelOff(requestId: Long, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun panelState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
    fun asBinder(): android.os.IBinder
    object Stub { fun asInterface(binder: android.os.IBinder): IHostBootstrap = binder as IHostBootstrap }
}

/** The first stop may time out; the test later releases construction independently. */
class FakeService : IHostBootstrap, android.os.IBinder {
    @Volatile var state = "READY"
    var revoked = false
    var stopReady = true
    var exitOnDestroy = true
    var stops = 0
    var destroys = 0
    var starts = 0
    @Volatile var sessionEpoch = ""
    var statusReads = 0
    val scaleRequests = mutableListOf<Pair<Long, Double>>()
    var scaleReads = 0
    var scalePayloadOverride: ByteArray? = null
    var currentScale = ScaleState(0, 1.0, Geometry(480, 640), null, ScaleStatus.READY, false)
    val panelRequests = java.util.concurrent.CopyOnWriteArrayList<Long>()
    val panelContexts = java.util.concurrent.CopyOnWriteArrayList<PanelContext>()
    val panelWrites = java.util.concurrent.CopyOnWriteArrayList<PanelContext>()
    var panelReads = 0
    var panelQueryGate: java.util.concurrent.CountDownLatch? = null
    val queryEntered = java.util.concurrent.CountDownLatch(1)
    var panelPayloadOverride: ByteArray? = null
    @Volatile var currentPanel = PanelPowerState()
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
        return "{\"state\":\"$state\",\"sessionRevoked\":$revoked,\"sessionEpoch\":\"$sessionEpoch\",\"projectionToken\":\"${if (state == "STREAMING") currentPanel.context.projectionToken else ""}\"}"
    }
    override fun stop(): String {
        stops++; stopEntered.countDown()
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
    fun replaceProjection(ownerEpoch: String = sessionEpoch) {
        sessionEpoch = ownerEpoch
        currentPanel = PanelPowerState(context = PanelContext(ownerEpoch, java.util.UUID.randomUUID().toString()), sessionActive = state == "STREAMING")
    }
    fun die() { alive = false; deaths.toList().forEach { it() } }
    override fun asBinder() = this
}
