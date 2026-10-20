package com.code2hack.eyeprojector.directhost

class ShizukuHostService
interface IHostBootstrap {
    fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?): String
    fun status(): String
    fun stop(): String
    fun destroy()
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
    var statusReads = 0
    private var alive = true
    private val deaths = mutableListOf<() -> Unit>()
    override val isBinderAlive: Boolean get() = alive
    override fun linkToDeath(recipient: () -> Unit, flags: Int) { deaths.add(recipient) }
    override fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?): String {
        starts++; return status()
    }
    override fun status(): String {
        check(alive)
        statusReads++
        return "{\"state\":\"$state\",\"sessionRevoked\":$revoked}"
    }
    override fun stop(): String {
        stops++
        state = if (stopReady) "STOPPED" else "STOP_UNVERIFIED"
        revoked = stopReady
        return status()
    }
    override fun destroy() { destroys++; if (exitOnDestroy) die() }
    fun die() { alive = false; deaths.toList().forEach { it() } }
    override fun asBinder() = this
}
