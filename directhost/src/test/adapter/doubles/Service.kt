package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

class ShizukuHostService
interface IHostBootstrap {
    fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String
    fun status(): String
    fun stop(): String
    fun destroy()
    fun requestScale(requestId: Long, scale: Double): ByteArray
    fun scaleState(): ByteArray
    fun requestPanelOff(requestId: Long): ByteArray
    fun panelState(): ByteArray
    fun asBinder(): android.os.IBinder
    object Stub { fun asInterface(binder: android.os.IBinder): IHostBootstrap = binder as IHostBootstrap }
}

/** The first stop may time out; the test later releases construction independently. */
class FakeService : IHostBootstrap, android.os.IBinder {
    var state = "READY"
    var revoked = false
    var stopReady = true
    var exitOnDestroy = true
    var stops = 0
    var destroys = 0
    var starts = 0
    var sessionEpoch = ""
    var statusReads = 0
    val scaleRequests = mutableListOf<Pair<Long, Double>>()
    var scaleReads = 0
    var scalePayloadOverride: ByteArray? = null
    var currentScale = ScaleState(0, 1.0, Geometry(480, 640), null, ScaleStatus.READY, false)
    val panelRequests = mutableListOf<Long>()
    var panelPayloadOverride: ByteArray? = null
    var currentPanel = PanelPowerState()
    private var alive = true
    private val deaths = mutableListOf<() -> Unit>()
    override val isBinderAlive: Boolean get() = alive
    override fun linkToDeath(recipient: () -> Unit, flags: Int) { deaths.add(recipient) }
    override fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String {
        starts++; this.sessionEpoch = sessionEpoch; return status()
    }
    override fun status(): String {
        check(alive)
        statusReads++
        return "{\"state\":\"$state\",\"sessionRevoked\":$revoked,\"sessionEpoch\":\"$sessionEpoch\"}"
    }
    override fun stop(): String {
        stops++
        state = if (stopReady) "STOPPED" else "STOP_UNVERIFIED"
        revoked = stopReady
        if (stopReady) currentScale = currentScale.copy(observed = null, target = null, status = ScaleStatus.UNAVAILABLE)
        return status()
    }
    override fun destroy() { destroys++; if (exitOnDestroy) die() }
    override fun requestScale(requestId: Long, scale: Double): ByteArray {
        check(alive)
        scaleRequests.add(requestId to scale)
        currentScale = currentScale.copy(requestId = requestId, requested = scale,
            target = LayoutScale.target(scale), status = ScaleStatus.PENDING)
        return scalePayloadOverride ?: currentScale.bytes()
    }
    override fun scaleState(): ByteArray { check(alive); scaleReads++; return scalePayloadOverride ?: currentScale.bytes() }
    override fun requestPanelOff(requestId: Long): ByteArray {
        check(alive)
        panelRequests.add(requestId)
        currentPanel = PanelPowerState(requestId, PanelSubmission.SUBMITTED, PanelRestoration.PENDING, sessionActive = true)
        return panelPayloadOverride ?: currentPanel.bytes()
    }
    override fun panelState(): ByteArray { check(alive); return panelPayloadOverride ?: currentPanel.bytes() }
    fun die() { alive = false; deaths.toList().forEach { it() } }
    override fun asBinder() = this
}
